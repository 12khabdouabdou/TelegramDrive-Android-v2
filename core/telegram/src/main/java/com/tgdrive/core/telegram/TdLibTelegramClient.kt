@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.tgdrive.core.telegram

import android.content.Context
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.security.KeystoreHelper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdLibTelegramClient(
    private val dispatchers: DispatchersProvider,
    @ApplicationContext private val appContext: Context,
    private val keystoreHelper: KeystoreHelper,
) : TelegramClient {

    @Volatile private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    @Volatile private var client: Client? = null
    @Volatile private var initializedApiId: Int? = null
    @Volatile private var initializedApiHash: String? = null
    @Volatile private var myUserIdCache: Long? = null
    @Volatile private var tdlibFilesDir: File? = null

    private val _authState = MutableStateFlow<AuthorizationState>(AuthorizationState.WaitInitialization)
    override val authState: StateFlow<AuthorizationState> = _authState.asStateFlow()

    private val _authCodeInfo = MutableStateFlow<AuthCodeInfo?>(null)
    override val authCodeInfo: StateFlow<AuthCodeInfo?> = _authCodeInfo.asStateFlow()

    private val _authEvents = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 8)
    val authEvents: SharedFlowLike<AuthEvent> = _authEvents.asSharedFlow()

    private val downloadHandlers = ConcurrentHashMap<Int, CopyOnWriteArrayList<(TdApi.File) -> Unit>>()
    private val uploadHandlers = ConcurrentHashMap<Int, CopyOnWriteArrayList<(TdApi.File) -> Unit>>()

    private fun registerFileHandler(
        map: ConcurrentHashMap<Int, CopyOnWriteArrayList<(TdApi.File) -> Unit>>,
        fileId: Int,
        handler: (TdApi.File) -> Unit,
    ) {
        map.computeIfAbsent(fileId) { CopyOnWriteArrayList() }.add(handler)
    }

    private fun unregisterFileHandler(
        map: ConcurrentHashMap<Int, CopyOnWriteArrayList<(TdApi.File) -> Unit>>,
        fileId: Int,
        handler: (TdApi.File) -> Unit,
    ) {
        map[fileId]?.let { list ->
            list.remove(handler)
            if (list.isEmpty()) map.remove(fileId, list)
        }
    }

    private val orphanedSendSucceeded = ConcurrentHashMap<Long, TdApi.Message>()
    private val orphanedSendFailed = ConcurrentHashMap<Long, TdApi.Error>()
    private val pendingSendConfirmations = ConcurrentHashMap<Long, CompletableDeferred<TdApi.Message>>()

    private val _messageIdUpdates = MutableSharedFlow<MessageIdUpdate>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messageIdUpdates: kotlinx.coroutines.flow.SharedFlow<MessageIdUpdate> = _messageIdUpdates.asSharedFlow()

    private val initLock = Mutex()

    private fun <V : Any> putBoundedOrphan(
        map: ConcurrentHashMap<Long, V>,
        key: Long,
        value: V,
    ) {
        if (map.size >= MAX_ORPHAN_CACHE) {
            map.keys.take(MAX_ORPHAN_CACHE / 4).forEach { map.remove(it) }
        }
        map.putIfAbsent(key, value)
    }

    private fun createUpdateHandler(onClosed: (() -> Unit)? = null): Client.ResultHandler =
        Client.ResultHandler { update ->
            try {
                when (update) {
                    is TdApi.UpdateAuthorizationState -> {
                        val st = update.authorizationState
                        _authState.value = st.toDomain()
                        when (st) {
                            is TdApi.AuthorizationStateWaitCode -> {
                                _authCodeInfo.value = st.codeInfo.toDomain()
                                Log.i("TDLib: WaitCode type=${st.codeInfo?.type?.javaClass?.simpleName} next=${st.codeInfo?.nextType?.javaClass?.simpleName} timeout=${st.codeInfo?.timeout}")
                            }
                            is TdApi.AuthorizationStateReady -> {
                                _authCodeInfo.value = null
                                scope.launch { refreshMyUserId() }
                            }
                            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                                _authCodeInfo.value = null
                            }
                            is TdApi.AuthorizationStateClosed -> {
                                _authCodeInfo.value = null
                                onClosed?.invoke()
                            }
                            else -> Unit
                        }
                    }
                    is TdApi.UpdateFile -> {
                        val f = update.file ?: return@ResultHandler
                        downloadHandlers[f.id]?.forEach { it.invoke(f) }
                        uploadHandlers[f.id]?.forEach { it.invoke(f) }
                    }
                    is TdApi.UpdateMessageSendSucceeded -> {
                        val oldId = update.oldMessageId
                        val newMsg = update.message
                        Log.i("TDLib: UpdateMessageSendSucceeded oldId=$oldId → newId=${newMsg.id}")
                        _messageIdUpdates.tryEmit(MessageIdUpdate(oldId, newMsg.id))
                        val d = pendingSendConfirmations.remove(oldId)
                        if (d != null) {
                            d.complete(newMsg)
                        } else {
                            putBoundedOrphan(orphanedSendSucceeded, oldId, newMsg)
                        }
                    }
                    is TdApi.UpdateMessageSendFailed -> {
                        val oldId = update.oldMessageId
                        val err = update.error
                        Log.w("TDLib: UpdateMessageSendFailed oldId=$oldId code=${err.code} msg=${err.message}")
                        val d = pendingSendConfirmations.remove(oldId)
                        if (d != null) {
                            d.completeExceptionally(TelegramException.fromError(err))
                        } else {
                            putBoundedOrphan(orphanedSendFailed, oldId, err)
                        }
                    }
                    is TdApi.UpdateNewMessage -> Unit
                    else -> Unit
                }
            } catch (t: Throwable) {
                Log.w("TDLib update handler error", t)
            }
        }

    override suspend fun initialize(apiId: Int, apiHash: String, sessionDir: String) {
        initLock.withLock {
            if (client != null) {
                if (initializedApiId == apiId && initializedApiHash == apiHash) {
                    return@withLock
                }
                Log.i("TDLib init: API credentials changed — recreating client")
                try { client?.send(TdApi.Close()) { } } catch (_: Throwable) {}
                client = null
                initializedApiId = null
                initializedApiHash = null
                _authCodeInfo.value = null
            }

            val sessionDirFile = File(appContext.filesDir, sessionDir)
            check(sessionDirFile.isDirectory || sessionDirFile.mkdirs()) {
                "Cannot create session dir: ${sessionDirFile.absolutePath}"
            }
            val dbDir = File(sessionDirFile, "db").apply { mkdirs() }
            val filesDir = File(sessionDirFile, "files").apply { mkdirs() }
            tdlibFilesDir = filesDir

            Log.i("TDLib init: sessionDir=${sessionDirFile.absolutePath}, ABIs=${android.os.Build.SUPPORTED_ABIS.toList()}")

            if (!scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive) {
                scope = CoroutineScope(SupervisorJob() + dispatchers.io)
            }

            val closedSignal = CompletableDeferred<Unit>()
            val c = try {
                Client.create(
                    createUpdateHandler { closedSignal.complete(Unit) },
                    { throwable -> Log.w("TDLib error: ${throwable.message}", throwable) },
                    { throwable -> Log.e("TDLib exception", throwable) },
                )
            } catch (t: UnsatisfiedLinkError) {
                Log.e("TDLib: native library load failed! ABIs=${android.os.Build.SUPPORTED_ABIS.toList()}", t)
                throw TelegramException(
                    -1,
                    "Cannot load TDLib native library (libtdjni.so). " +
                        "Device ABIs: ${android.os.Build.SUPPORTED_ABIS.toList()}. " +
                        "Try clearing app data and reinstalling.",
                )
            } catch (t: Throwable) {
                Log.e("TDLib: Client.create() failed", t)
                throw TelegramException(-1, "TDLib Client.create() failed: ${t.message}")
            }

            var activeClient = c
            val langCode = java.util.Locale.getDefault().language.ifBlank { "en" }
            val modelName = "${android.os.Build.MANUFACTURER.orEmpty()} ${android.os.Build.MODEL.orEmpty()}"
                .trim()
                .ifBlank { "Android" }
            val sysVersion = "Android ${android.os.Build.VERSION.RELEASE ?: "14"}"

            val params = TdApi.SetTdlibParameters().apply {
                this.apiId = apiId
                this.apiHash = apiHash
                databaseDirectory = dbDir.absolutePath + "/"
                filesDirectory = filesDir.absolutePath + "/"
                useMessageDatabase = true
                useChatInfoDatabase = true
                useFileDatabase = true
                useSecretChats = false
                systemLanguageCode = langCode
                deviceModel = modelName
                systemVersion = sysVersion
                applicationVersion = "1.0.3"
                useTestDc = false
                databaseEncryptionKey = keystoreHelper.tdlibSessionKey()
            }

            try {
                val result = suspendCancellableCoroutine<TdApi.Object> { cont ->
                    c.send(params) { res ->
                        if (cont.isActive) {
                            if (res is TdApi.Error) {
                                cont.resumeWithException(TelegramException.fromError(res))
                            } else {
                                cont.resume(res)
                            }
                        }
                    }
                }
                if (result is TdApi.Error) {
                    throw TelegramException.fromError(result)
                }
            } catch (t: TelegramException) {
                val msg = t.errorMessage ?: ""
                Log.w("TDLib: SetTdlibParameters failed: code=${t.code}, msg=$msg")
                val isBinlogError = msg.contains("decryption", ignoreCase = true) ||
                    msg.contains("binlog", ignoreCase = true) ||
                    msg.contains("Wrong database encryption key", ignoreCase = true) ||
                    msg.contains("Database disk image is malformed", ignoreCase = true) ||
                    msg.contains("database is malformed", ignoreCase = true)
                if (isBinlogError) {
                    Log.w("TDLib: binlog/DB error — closing client, deleting db/, and regenerating session key")
                    try {
                        try { c.send(TdApi.Close()) { } } catch (_: Throwable) {}
                        withTimeoutOrNull(5_000L) { closedSignal.await() }
                        dbDir.deleteRecursively()
                        dbDir.mkdirs()
                        val freshKey = keystoreHelper.regenerateTdlibSessionKey()
                        val c2 = Client.create(
                            createUpdateHandler(),
                            { throwable -> Log.w("TDLib error: ${throwable.message}", throwable) },
                            { throwable -> Log.e("TDLib exception", throwable) },
                        )
                        val retryParams = TdApi.SetTdlibParameters().apply {
                            this.apiId = apiId
                            this.apiHash = apiHash
                            databaseDirectory = dbDir.absolutePath + "/"
                            filesDirectory = filesDir.absolutePath + "/"
                            useMessageDatabase = true
                            useChatInfoDatabase = true
                            useFileDatabase = true
                            useSecretChats = false
                            systemLanguageCode = langCode
                            deviceModel = modelName
                            systemVersion = sysVersion
                            applicationVersion = "1.0.3"
                            useTestDc = false
                            databaseEncryptionKey = freshKey
                        }
                        val retryResult = suspendCancellableCoroutine<TdApi.Object> { cont ->
                            c2.send(retryParams) { res ->
                                if (cont.isActive) {
                                    if (res is TdApi.Error) {
                                        cont.resumeWithException(TelegramException.fromError(res))
                                    } else {
                                        cont.resume(res)
                                    }
                                }
                            }
                        }
                        if (retryResult is TdApi.Error) {
                            try { c2.send(TdApi.Close()) { } } catch (_: Throwable) {}
                            throw TelegramException.fromError(retryResult)
                        }
                        activeClient = c2
                        Log.i("TDLib: binlog recovery succeeded — user must re-login (API creds preserved)")
                    } catch (retryErr: Throwable) {
                        Log.e("TDLib: binlog recovery failed", retryErr)
                        throw retryErr
                    }
                } else {
                    try { c.send(TdApi.Close()) { } } catch (_: Throwable) {}
                    throw t
                }
            }

            client = activeClient
            initializedApiId = apiId
            initializedApiHash = apiHash
            Log.i("TDLib client initialised (apiId=$apiId)")
        }
    }

    internal suspend fun <T : TdApi.Object> send(function: TdApi.Function<*>): T =
        withContext(dispatchers.io) {
            val c = client ?: throw TelegramException(-1, "TDLib not initialised")
            suspendCancellableCoroutine { cont ->
                c.send(function, { result ->
                    if (!cont.isActive) return@send
                    when (result) {
                        is TdApi.Error -> cont.resumeWithException(TelegramException.fromError(result))
                        else -> @Suppress("UNCHECKED_CAST") cont.resume(result as T)
                    }
                }, { t ->
                    if (cont.isActive) cont.resumeWithException(t)
                })
            }
        }

    // Auth
    override suspend fun sendPhoneNumber(phone: String) {
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            allowMissedCall = false
            isCurrentPhoneNumber = false
            hasUnknownPhoneNumber = false
            // Must be false: this app has no official Telegram Play Services SMS Retriever
            // signature or Firebase token, so setting true causes Telegram to withhold SMS OTP.
            allowSmsRetrieverApi = false
        }
        send<TdApi.Ok>(TdApi.SetAuthenticationPhoneNumber(phone, settings))
        _authEvents.tryEmit(AuthEvent.CodeRequested)
    }

    override suspend fun resendAuthCode() {
        send<TdApi.Ok>(TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()))
    }

    override suspend fun sendAuthCode(code: String) { send<TdApi.Ok>(TdApi.CheckAuthenticationCode(code)) }
    override suspend fun requestQrCodeLogin() { send<TdApi.Ok>(TdApi.RequestQrCodeAuthentication(longArrayOf())) }
    override suspend fun sendEmailAddress(email: String) { send<TdApi.Ok>(TdApi.SetAuthenticationEmailAddress(email)) }
    override suspend fun sendEmailCode(code: String) {
        send<TdApi.Ok>(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code)))
    }
    override suspend fun sendAuthPassword(password: String) { send<TdApi.Ok>(TdApi.CheckAuthenticationPassword(password)) }
    override suspend fun registerNewUser(firstName: String, lastName: String) {
        send<TdApi.Ok>(TdApi.RegisterUser(firstName, lastName, false))
    }

    override suspend fun logout() {
        initLock.withLock {
            try { send<TdApi.Ok>(TdApi.LogOut()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (t: Throwable) { Log.w("Logout error (ignored)", t) }
            finally {
                _authState.value = AuthorizationState.Closed
                _authCodeInfo.value = null
                myUserIdCache = null
                initializedApiId = null
                initializedApiHash = null
                pendingSendConfirmations.values.forEach {
                    runCatching { it.completeExceptionally(TelegramException(-1, "Logged out")) }
                }
                pendingSendConfirmations.clear()
                orphanedSendSucceeded.clear()
                orphanedSendFailed.clear()
                downloadHandlers.clear()
                uploadHandlers.clear()
                withTimeoutOrNull(5_000) {
                    try { send<TdApi.Ok>(TdApi.Close()) } catch (_: Throwable) {}
                }
                client = null
            }
        }
    }

    // File ops
    override suspend fun fetchMessages(chatId: Long, fromMessageId: Long, limit: Int): List<TelegramMessage> = withContext(dispatchers.io) {
        val resp = send<TdApi.Messages>(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))
        val msgs = resp.messages.mapNotNull { it.toDomainFile() }
        val msgById = resp.messages.associateBy { it.id }
        for (msg in msgs) {
            try {
                val tdMsg = msgById[msg.id]
                val thumbFileId = tdMsg?.extractThumbnailFileId()
                if (thumbFileId != null && thumbFileId != 0) {
                    client?.send(TdApi.DownloadFile(thumbFileId, 5, 0, 0, false)) { _ -> }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Throwable) {}
        }
        msgs
    }

    override suspend fun searchMessages(chatId: Long, query: String, limit: Int): List<TelegramMessage> = withContext(dispatchers.io) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val resp = send<TdApi.FoundChatMessages>(TdApi.SearchChatMessages(chatId, null, query, null, 0L, 0, limit, null))
            resp.messages.mapNotNull { it.toDomainFile() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (t: Throwable) { Log.w("searchMessages failed", t); emptyList() }
    }

    override suspend fun searchMessagesGlobal(query: String, limit: Int): List<TelegramMessage> = withContext(dispatchers.io) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            // TDLib 1.8.65: SearchMessages(chatList, query, offset, limit, filter, chatTypeFilter, minDate, maxDate)
            val safeLimit = limit.coerceIn(1, 100)
            val resp = send<TdApi.FoundMessages>(TdApi.SearchMessages(null, query, "", safeLimit, null, null, 0, 0))
            resp.messages.mapNotNull { it.toDomainFile() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (t: Throwable) { Log.w("searchMessagesGlobal failed", t); emptyList() }
    }

    override suspend fun listFolders(): List<TelegramFolder> = withContext(dispatchers.io) {
        val me = myUserId()
        try {
            send<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), 200))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {}
        val resp = send<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), 200))
        val folders = mutableListOf(TelegramFolder(me, "Saved Messages", true, false, System.currentTimeMillis()))
        for (id in resp.chatIds) {
            val chat = send<TdApi.Chat>(TdApi.GetChat(id))
            val type = chat.type
            if (type is TdApi.ChatTypeSupergroup && type.isChannel) {
                val supergroup = send<TdApi.Supergroup>(TdApi.GetSupergroup(type.supergroupId))
                if (supergroup.status is TdApi.ChatMemberStatusCreator) {
                    folders.add(TelegramFolder(chat.id, chat.title, false, true, System.currentTimeMillis()))
                }
            }
        }
        folders
    }

    override suspend fun createFolder(title: String): TelegramFolder = withContext(dispatchers.io) {
        val resp = send<TdApi.Chat>(TdApi.CreateNewSupergroupChat(title, false, true, null, null, 0, false))
        TelegramFolder(resp.id, title, false, true, System.currentTimeMillis())
    }

    override suspend fun renameFolder(chatId: Long, newTitle: String) = withContext(dispatchers.io) {
        send<TdApi.Ok>(TdApi.SetChatTitle(chatId, newTitle)); Unit
    }

    override suspend fun deleteFolder(chatId: Long) = withContext(dispatchers.io) {
        send<TdApi.Ok>(TdApi.DeleteChat(chatId)); Unit
    }

    override suspend fun uploadFile(
        chatId: Long,
        localPath: String,
        fileName: String,
        mimeType: String,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ): UploadResult = withContext(dispatchers.io) {
        val src = File(localPath)
        require(src.isFile) { "Upload source not found: $localPath" }
        val totalSize = src.length()
        require(totalSize <= TelegramClient.MAX_FILE_SIZE) {
            "File exceeds 2 GB Telegram limit"
        }

        Log.i("uploadFile: '$fileName' ($totalSize bytes) → chatId=$chatId")
        onProgress(0L, totalSize)

        try {
            send<TdApi.Chat>(TdApi.GetChat(chatId))
        } catch (t: Throwable) {
            Log.w("uploadFile: GetChat failed (continuing anyway)", t)
        }

        val inputContent = TdApi.InputMessageDocument(
            TdApi.InputDocument(TdApi.InputFileLocal(localPath), null, false),
            TdApi.FormattedText(),
        )

        val sendConfirmation = CompletableDeferred<TdApi.Message>()
        val msg = try {
            send<TdApi.Message>(TdApi.SendMessage(chatId, null, null, null, null, inputContent))
        } catch (t: Throwable) {
            Log.w("uploadFile: SendMessage failed for '$fileName'", t)
            throw t
        }

        val localMsgId = msg.id
        val fileId = msg.extractFileId()

        pendingSendConfirmations[localMsgId] = sendConfirmation

        orphanedSendSucceeded.remove(localMsgId)?.let { orphaned ->
            pendingSendConfirmations.remove(localMsgId)
            onProgress(totalSize, totalSize)
            return@withContext UploadResult(orphaned.id, orphaned.extractRemoteId())
        }
        orphanedSendFailed.remove(localMsgId)?.let { err ->
            pendingSendConfirmations.remove(localMsgId)
            throw TelegramException.fromError(err)
        }

        if (sendConfirmation.isCompleted) {
            val serverMsg = sendConfirmation.await()
            onProgress(totalSize, totalSize)
            return@withContext UploadResult(serverMsg.id, serverMsg.extractRemoteId())
        }

        if (fileId != null) {
            val progressChannel = Channel<TdApi.File>(Channel.BUFFERED)
            val uploadHandler: (TdApi.File) -> Unit = { f -> progressChannel.trySend(f) }
            registerFileHandler(uploadHandlers, fileId, uploadHandler)
            val lastActivityMs = AtomicLong(System.currentTimeMillis())
            val lastUploadedBytes = AtomicLong(-1L)

            val progressJob = launch {
                for (f in progressChannel) {
                    if (f.remote.isUploadingActive || f.remote.uploadedSize > 0L) {
                        val uploaded = f.remote.uploadedSize
                        if (uploaded > lastUploadedBytes.get()) {
                            lastUploadedBytes.set(uploaded)
                            lastActivityMs.set(System.currentTimeMillis())
                        }
                        onProgress(uploaded, f.size.coerceAtLeast(totalSize))
                    }
                }
            }
            val watchdogJob = launch {
                while (!sendConfirmation.isCompleted) {
                    delay(2_000L)
                    if (System.currentTimeMillis() - lastActivityMs.get() >= UPLOAD_STALL_TIMEOUT_MS) {
                        sendConfirmation.completeExceptionally(
                            TelegramException(-1, "Upload stalled (no progress for ${UPLOAD_STALL_TIMEOUT_MS / 1000}s) for $fileName"),
                        )
                        break
                    }
                }
            }

            try {
                val serverMsg = sendConfirmation.await()
                val serverId = serverMsg.id
                val remoteId = serverMsg.extractRemoteId()
                onProgress(totalSize, totalSize)
                return@withContext UploadResult(serverId, remoteId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                try {
                    client?.send(TdApi.DeleteMessages(chatId, longArrayOf(localMsgId), false)) { }
                } catch (_: Throwable) {}
                throw e
            } catch (t: Throwable) {
                try {
                    client?.send(TdApi.DeleteMessages(chatId, longArrayOf(localMsgId), false)) { }
                } catch (_: Throwable) {}
                throw t
            } finally {
                watchdogJob.cancel()
                progressChannel.close()
                progressJob.cancelAndJoin()
                unregisterFileHandler(uploadHandlers, fileId, uploadHandler)
                pendingSendConfirmations.remove(localMsgId)
            }
        } else {
            try {
                val serverMsg = withTimeoutOrNull(UPLOAD_NO_FILE_ID_TIMEOUT_MS) { sendConfirmation.await() }
                if (serverMsg != null) {
                    onProgress(totalSize, totalSize)
                    return@withContext UploadResult(serverMsg.id, serverMsg.extractRemoteId())
                }
                try {
                    client?.send(TdApi.DeleteMessages(chatId, longArrayOf(localMsgId), false)) { }
                } catch (_: Throwable) {}
                throw TelegramException(-1, "Upload timed out for $fileName")
            } catch (e: kotlinx.coroutines.CancellationException) {
                try {
                    client?.send(TdApi.DeleteMessages(chatId, longArrayOf(localMsgId), false)) { }
                } catch (_: Throwable) {}
                throw e
            } finally {
                pendingSendConfirmations.remove(localMsgId)
            }
        }
    }

    override suspend fun deleteMessage(chatId: Long, messageId: Long) = withContext(dispatchers.io) {
        val selfId = try { myUserId() } catch (_: Throwable) { 0L }
        val revoke = chatId != selfId
        Log.i("deleteMessage: chatId=$chatId, msgId=$messageId, revoke=$revoke")
        try {
            send<TdApi.Ok>(TdApi.DeleteMessages(chatId, longArrayOf(messageId), revoke))
        } catch (t: Throwable) {
            Log.w("deleteMessage: retrying with revoke=false", t)
            send<TdApi.Ok>(TdApi.DeleteMessages(chatId, longArrayOf(messageId), false))
        }
        Unit
    }

    override suspend fun renameFile(chatId: Long, messageId: Long, newFileName: String) = withContext(dispatchers.io) {
        Log.i("renameFile: chatId=$chatId, msgId=$messageId, newName='$newFileName'")
        Unit
    }

    override suspend fun moveFile(fromChatId: Long, messageId: Long, toChatId: Long): Long = withContext(dispatchers.io) {
        Log.i("moveFile: from=$fromChatId, msg=$messageId, to=$toChatId")
        val forwarded = try {
            send<TdApi.Messages>(TdApi.ForwardMessages(toChatId, null, fromChatId, longArrayOf(messageId), null, true, false))
        } catch (t: Throwable) {
            Log.w("moveFile: sendCopy=true failed, trying false", t)
            send<TdApi.Messages>(TdApi.ForwardMessages(toChatId, null, fromChatId, longArrayOf(messageId), null, false, false))
        }
        val localNewMsgId = forwarded.messages.firstOrNull()?.id
            ?: throw TelegramException(-1, "Forward failed")

        val sendConfirmation = CompletableDeferred<TdApi.Message>()
        pendingSendConfirmations[localNewMsgId] = sendConfirmation

        orphanedSendSucceeded.remove(localNewMsgId)?.let { orphaned ->
            pendingSendConfirmations.remove(localNewMsgId)
            val serverId = orphaned.id
            deleteOriginalAfterMove(fromChatId, messageId)
            return@withContext serverId
        }
        orphanedSendFailed.remove(localNewMsgId)?.let { err ->
            pendingSendConfirmations.remove(localNewMsgId)
            throw TelegramException.fromError(err)
        }

        val serverMsg = try {
            withTimeoutOrNull(5 * 60 * 1000L) { sendConfirmation.await() }
        } finally {
            pendingSendConfirmations.remove(localNewMsgId)
        }
        if (serverMsg == null) {
            throw TelegramException(-1, "Move timed out before server confirmation; original file preserved")
        }

        val serverId = serverMsg.id
        deleteOriginalAfterMove(fromChatId, messageId)
        serverId
    }

    private suspend fun deleteOriginalAfterMove(fromChatId: Long, messageId: Long) {
        val selfId = try { myUserId() } catch (_: Throwable) { 0L }
        val revoke = fromChatId != selfId
        try {
            send<TdApi.Ok>(TdApi.DeleteMessages(fromChatId, longArrayOf(messageId), revoke))
        } catch (t: Throwable) {
            Log.w("moveFile: delete original with revoke=$revoke failed, trying revoke=false", t)
            try {
                send<TdApi.Ok>(TdApi.DeleteMessages(fromChatId, longArrayOf(messageId), false))
            } catch (t2: Throwable) {
                Log.w("moveFile: could not delete original", t2)
            }
        }
    }

    override fun downloadFile(remoteId: String, localPath: String): Flow<DownloadUpdate> = flow {
        if (remoteId.isEmpty()) {
            emit(DownloadUpdate.Failed("File has no remote ID"))
            return@flow
        }
        val activeClient = client
        if (activeClient == null) {
            emit(DownloadUpdate.Failed("TDLib not initialised"))
            return@flow
        }
        Log.i("downloadFile: remoteId=${remoteId.take(30)}…")

        try {
            val file = send<TdApi.File>(TdApi.GetRemoteFile(remoteId, null))
            val initialLocal = file.local
            if (initialLocal?.isDownloadingCompleted == true) {
                val p = initialLocal.path.orEmpty()
                if (p.isNotEmpty() && File(p).exists()) {
                    emit(DownloadUpdate.Done(p))
                    return@flow
                }
                Log.i("downloadFile: stale cache, re-downloading")
                try { send<TdApi.Ok>(TdApi.DeleteFile(file.id)) } catch (_: Throwable) {}
            }

            val channel = Channel<DownloadUpdate>(Channel.BUFFERED)
            val dlHandler: (TdApi.File) -> Unit = { f ->
                try {
                    val fLocal = f.local
                    if (fLocal != null) {
                        if (fLocal.isDownloadingCompleted) {
                            val pp = fLocal.path.orEmpty()
                            if (pp.isNotEmpty() && File(pp).exists()) {
                                channel.trySend(DownloadUpdate.Done(pp))
                            } else {
                                channel.trySend(DownloadUpdate.Failed("File missing after download"))
                            }
                            channel.close()
                        } else {
                            val total = if (f.size > 0L) f.size else f.expectedSize
                            channel.trySend(DownloadUpdate.Progress(fLocal.downloadedSize, total))
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("TDLib download handler error", t)
                    channel.trySend(DownloadUpdate.Failed(t.message ?: "Download update error"))
                    channel.close()
                }
            }
            registerFileHandler(downloadHandlers, file.id, dlHandler)
            var completed = false

            try {
                activeClient.send(
                    TdApi.DownloadFile(file.id, 8, 0, 0, false),
                    { result ->
                        try {
                            when (result) {
                                is TdApi.Error -> {
                                    channel.trySend(DownloadUpdate.Failed(result.message ?: "Error"))
                                    channel.close()
                                }
                                is TdApi.File -> {
                                    val rLocal = result.local
                                    if (rLocal?.isDownloadingCompleted == true) {
                                        val pp = rLocal.path.orEmpty()
                                        if (pp.isNotEmpty() && File(pp).exists()) {
                                            channel.trySend(DownloadUpdate.Done(pp))
                                        } else {
                                            channel.trySend(DownloadUpdate.Failed("File missing after download"))
                                        }
                                        channel.close()
                                    } else if (rLocal != null && !rLocal.isDownloadingActive && !rLocal.canBeDownloaded) {
                                        channel.trySend(DownloadUpdate.Failed("File cannot be downloaded"))
                                        channel.close()
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w("TDLib download result error", t)
                            channel.trySend(DownloadUpdate.Failed(t.message ?: "Download callback error"))
                            channel.close()
                        }
                    },
                    { t ->
                        Log.w("TDLib download request exception", t)
                        channel.trySend(DownloadUpdate.Failed(t.message ?: "Download failed"))
                        channel.close()
                    },
                )
                withTimeoutOrNull(5 * 60 * 1000L) {
                    for (upd in channel) {
                        if (upd is DownloadUpdate.Done) completed = true
                        emit(upd)
                        if (upd is DownloadUpdate.Done || upd is DownloadUpdate.Failed) break
                    }
                } ?: emit(DownloadUpdate.Failed("Download timed out"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                emit(DownloadUpdate.Failed(t.message ?: "Download failed"))
            } finally {
                unregisterFileHandler(downloadHandlers, file.id, dlHandler)
                channel.close()
                if (!completed) {
                    try { activeClient.send(TdApi.CancelDownloadFile(file.id, false)) { } } catch (_: Throwable) {}
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            emit(DownloadUpdate.Failed(t.message ?: "Download failed"))
        }
    }.flowOn(dispatchers.io)

    override suspend fun clearDownloadCache(): Unit = withContext(dispatchers.io) {
        try {
            val optimize = TdApi.OptimizeStorage().apply {
                size = 0L
                ttl = 0
                count = 0
                immunityDelay = 0
                fileTypes = arrayOf<TdApi.FileType>()
            }
            send<TdApi.StorageStatistics>(optimize)
            Log.i("clearDownloadCache: done")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("clearDownloadCache: OptimizeStorage failed — skipping manual fallback", t)
        }
    }

    override suspend fun myUserId(): Long = withContext(dispatchers.io) {
        myUserIdCache?.let { return@withContext it }
        val me = send<TdApi.User>(TdApi.GetMe())
        myUserIdCache = me.id
        me.id
    }

    private suspend fun refreshMyUserId() {
        try { myUserId() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (t: Throwable) { Log.w("Could not fetch myUserId", t) }
    }

    override suspend fun generateShareLink(chatId: Long, messageId: Long): String? = withContext(dispatchers.io) {
        try {
            val chat = send<TdApi.Chat>(TdApi.GetChat(chatId))
            val type = chat.type
            if (type is TdApi.ChatTypeSupergroup && type.isChannel) {
                val req = TdApi.GetMessageLink().apply {
                    this.chatId = chatId
                    this.messageId = messageId
                }
                val linkObj = send<TdApi.MessageLink>(req)
                linkObj.link.takeIf { it.isNotBlank() }
            } else {
                Log.i("generateShareLink: chat $chatId is not a shareable channel")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("generateShareLink failed", t)
            null
        }
    }

    override suspend fun close() = withContext(dispatchers.io) {
        initLock.withLock {
            withTimeoutOrNull(5_000) {
                try { send<TdApi.Ok>(TdApi.Close()) } catch (_: Throwable) {}
            }
            pendingSendConfirmations.values.forEach {
                runCatching { it.completeExceptionally(TelegramException(-1, "Client closed")) }
            }
            pendingSendConfirmations.clear()
            orphanedSendSucceeded.clear()
            orphanedSendFailed.clear()
            downloadHandlers.clear()
            uploadHandlers.clear()
            scope.cancel()
            client = null
            initializedApiId = null
            initializedApiHash = null
            myUserIdCache = null
            _authCodeInfo.value = null
            _authState.value = AuthorizationState.Closed
            Log.i("TDLib client closed")
            Unit
        }
    }

    companion object {
        private const val UPLOAD_STALL_TIMEOUT_MS = 90_000L
        private const val UPLOAD_NO_FILE_ID_TIMEOUT_MS = 60_000L
        private const val MAX_ORPHAN_CACHE = 256
    }
}

sealed interface AuthEvent {
    data object CodeRequested : AuthEvent
    data object PasswordRequired : AuthEvent
    data class Error(val code: Int, val message: String) : AuthEvent
}

typealias SharedFlowLike<T> = kotlinx.coroutines.flow.SharedFlow<T>

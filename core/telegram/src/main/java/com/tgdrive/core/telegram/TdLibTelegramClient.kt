@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.tgdrive.core.telegram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.io.FileOutputStream
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
    @Volatile private var myUserIsPremiumCache: Boolean? = null
    @Volatile private var systemChannelIdCache: Long? = null
    @Volatile private var tdlibFilesDir: File? = null

    private val _authState = MutableStateFlow<AuthorizationState>(AuthorizationState.WaitInitialization)
    override val authState: StateFlow<AuthorizationState> = _authState.asStateFlow()

    private val _authCodeInfo = MutableStateFlow<AuthCodeInfo?>(null)
    override val authCodeInfo: StateFlow<AuthCodeInfo?> = _authCodeInfo.asStateFlow()

    private val _authEvents = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 8)
    val authEvents: SharedFlowLike<AuthEvent> = _authEvents.asSharedFlow()

    private val downloadHandlers = ConcurrentHashMap<Int, CopyOnWriteArrayList<(TdApi.File) -> Unit>>()
    private val uploadHandlers = ConcurrentHashMap<Int, CopyOnWriteArrayList<(TdApi.File) -> Unit>>()
    private val pendingThumbnails = ConcurrentHashMap<Int, CopyOnWriteArrayList<Pair<Long, Long>>>()

    private val _thumbnailUpdates = MutableSharedFlow<ThumbnailUpdate>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val thumbnailUpdates: kotlinx.coroutines.flow.SharedFlow<ThumbnailUpdate> =
        _thumbnailUpdates.asSharedFlow()

    private fun registerPendingThumbnail(
        fileId: Int,
        chatId: Long,
        messageId: Long,
    ) {
        val list = pendingThumbnails.computeIfAbsent(fileId) { CopyOnWriteArrayList() }
        val key = chatId to messageId
        if (!list.contains(key)) {
            list.add(key)
        }
    }

    private fun emitThumbnailCompleted(file: TdApi.File) {
        val local = file.local ?: return
        val path = local.path.orEmpty()
        if (!local.isDownloadingCompleted || path.isEmpty()) return
        val f = File(path)
        if (!f.isFile || f.length() <= 0L) return
        val targets = pendingThumbnails.remove(file.id) ?: return
        targets.forEach { (chatId, msgId) ->
            _thumbnailUpdates.tryEmit(ThumbnailUpdate(chatId, msgId, path))
        }
    }

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
                        if (f.local?.isDownloadingCompleted == true && pendingThumbnails.containsKey(f.id)) {
                            emitThumbnailCompleted(f)
                        }
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
                myUserIsPremiumCache = null
                systemChannelIdCache = null
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
                pendingThumbnails.clear()
                withTimeoutOrNull(5_000) {
                    try { send<TdApi.Ok>(TdApi.Close()) } catch (_: Throwable) {}
                }
                client = null
            }
        }
    }

    // File ops
    private fun prepareMessageThumbnails(
        tdMessages: Array<TdApi.Message>,
        domainMsgs: List<TelegramMessage>,
    ): List<TelegramMessage> {
        val msgById = tdMessages.associateBy { it.id }
        val miniDir = File(appContext.cacheDir, "minithumbs")
        return domainMsgs.map { msg ->
            val tdMsg = msgById[msg.id]
            val thumbFileId = tdMsg?.extractThumbnailFileId()
            if (thumbFileId != null && thumbFileId != 0 && tdMsg != null && !tdMsg.isThumbnailDownloaded()) {
                try {
                    registerPendingThumbnail(thumbFileId, msg.chatId, msg.id)
                    client?.send(TdApi.DownloadFile(thumbFileId, 16, 0, 0, false)) { res ->
                        if (res is TdApi.File) {
                            emitThumbnailCompleted(res)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            if (msg.thumbnailPath == null && msg.minithumbnailBytes != null && msg.minithumbnailBytes.isNotEmpty()) {
                val miniFile = File(miniDir, "${msg.chatId}_${msg.id}.jpg")
                if (!miniFile.isFile || miniFile.length() <= 0L) {
                    runCatching {
                        miniDir.mkdirs()
                        val tmp = File(miniDir, "${msg.chatId}_${msg.id}.tmp")
                        tmp.writeBytes(msg.minithumbnailBytes)
                        if (miniFile.exists()) miniFile.delete()
                        if (!tmp.renameTo(miniFile)) {
                            tmp.copyTo(miniFile, overwrite = true)
                            tmp.delete()
                        }
                    }
                }
                if (miniFile.isFile && miniFile.length() > 0L) {
                    return@map msg.copy(thumbnailPath = miniFile.absolutePath)
                }
            }
            msg
        }
    }

    override suspend fun fetchMessages(chatId: Long, fromMessageId: Long, limit: Int): List<TelegramMessage> = withContext(dispatchers.io) {
        val resp = send<TdApi.Messages>(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))
        val msgs = resp.messages.mapNotNull { it.toDomainFile() }
        prepareMessageThumbnails(resp.messages, msgs)
    }

    override suspend fun searchMessages(chatId: Long, query: String, limit: Int): List<TelegramMessage> = withContext(dispatchers.io) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val resp = send<TdApi.FoundChatMessages>(TdApi.SearchChatMessages(chatId, null, query, null, 0L, 0, limit, null))
            val msgs = resp.messages.mapNotNull { it.toDomainFile() }
            prepareMessageThumbnails(resp.messages, msgs)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (t: Throwable) { Log.w("searchMessages failed", t); emptyList() }
    }

    override suspend fun searchMessagesGlobal(query: String, limit: Int): List<TelegramMessage> = withContext(dispatchers.io) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            // TDLib 1.8.65: SearchMessages(chatList, query, offset, limit, filter, chatTypeFilter, minDate, maxDate)
            val safeLimit = limit.coerceIn(1, 100)
            val resp = send<TdApi.FoundMessages>(TdApi.SearchMessages(null, query, "", safeLimit, null, null, 0, 0))
            val msgs = resp.messages.mapNotNull { it.toDomainFile() }
            prepareMessageThumbnails(resp.messages, msgs)
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
        val extraFolders = coroutineScope {
            resp.chatIds.map { id ->
                async {
                    try {
                        val chat = send<TdApi.Chat>(TdApi.GetChat(id))
                        val type = chat.type
                        if (type is TdApi.ChatTypeSupergroup && type.isChannel) {
                            val supergroup = send<TdApi.Supergroup>(TdApi.GetSupergroup(type.supergroupId))
                            if (supergroup.status is TdApi.ChatMemberStatusCreator) {
                                if (chat.title == TelegramClient.SYSTEM_CHANNEL_TITLE) {
                                    systemChannelIdCache = chat.id
                                    null
                                } else {
                                    TelegramFolder(chat.id, chat.title, false, true, System.currentTimeMillis())
                                }
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        folders.addAll(extraFolders)
        folders
    }

    override suspend fun getOrCreateSystemChannel(): Long? = withContext(dispatchers.io) {
        systemChannelIdCache?.let { return@withContext it }
        try {
            try {
                send<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), 200))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {}
            val resp = send<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), 200))
            for (id in resp.chatIds) {
                val chat = runCatching { send<TdApi.Chat>(TdApi.GetChat(id)) }.getOrNull() ?: continue
                val type = chat.type
                if (type is TdApi.ChatTypeSupergroup && type.isChannel && chat.title == TelegramClient.SYSTEM_CHANNEL_TITLE) {
                    val supergroup = runCatching { send<TdApi.Supergroup>(TdApi.GetSupergroup(type.supergroupId)) }.getOrNull()
                    if (supergroup?.status is TdApi.ChatMemberStatusCreator) {
                        systemChannelIdCache = chat.id
                        return@withContext chat.id
                    }
                }
            }
            val created = send<TdApi.Chat>(
                TdApi.CreateNewSupergroupChat(TelegramClient.SYSTEM_CHANNEL_TITLE, false, true, null, null, 0, false),
            )
            systemChannelIdCache = created.id
            created.id
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("getOrCreateSystemChannel failed", t)
            null
        }
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
        val maxLimit = maxUploadSizeBytes()
        require(totalSize <= maxLimit) {
            "File exceeds ${maxLimit / (1024L * 1024 * 1024)} GB Telegram limit"
        }

        Log.i("uploadFile: '$fileName' ($totalSize bytes) → chatId=$chatId")
        onProgress(0L, totalSize)

        try {
            send<TdApi.Chat>(TdApi.GetChat(chatId))
        } catch (t: Throwable) {
            Log.w("uploadFile: GetChat failed (continuing anyway)", t)
        }

        val thumbTriple = buildUploadThumbnail(localPath, fileName, mimeType)
        val uploadThumbFile = thumbTriple?.first
        val inputThumb = thumbTriple?.let { (f, w, h) ->
            TdApi.InputThumbnail(TdApi.InputFileLocal(f.absolutePath), w, h)
        }
        val inputContent = TdApi.InputMessageDocument(
            TdApi.InputDocument(TdApi.InputFileLocal(localPath), inputThumb, false),
            TdApi.FormattedText(),
        )

        val sendConfirmation = CompletableDeferred<TdApi.Message>()
        try {
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
        } finally {
            runCatching { uploadThumbFile?.delete() }
        }
    }

    private fun buildUploadThumbnail(
        localPath: String,
        fileName: String,
        mimeType: String,
    ): Triple<File, Int, Int>? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        val rawBmp: Bitmap? = try {
            when {
                mime.startsWith("image/") || ext in IMAGE_THUMB_EXTS -> decodeUploadImageThumb(localPath)
                mime.startsWith("video/") || ext in VIDEO_THUMB_EXTS -> decodeUploadVideoThumb(localPath)
                mime == "application/pdf" || ext == "pdf" -> decodeUploadPdfThumb(localPath)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
        if (rawBmp == null || rawBmp.isRecycled) return null

        val dir = File(appContext.cacheDir, "upload_thumbs").apply { mkdirs() }
        val outFile = File(dir, "thumb_${System.nanoTime()}.jpg")
        return try {
            val scaled = scaleDownBitmap(rawBmp, 320)
            val w = scaled.width.coerceAtLeast(1)
            val h = scaled.height.coerceAtLeast(1)
            FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, fos)
            }
            if (outFile.length() > 180 * 1024) {
                FileOutputStream(outFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 60, fos)
                }
            }
            if (scaled !== rawBmp && !rawBmp.isRecycled) rawBmp.recycle()
            if (!scaled.isRecycled) scaled.recycle()
            if (outFile.isFile && outFile.length() > 0L) Triple(outFile, w, h) else {
                outFile.delete()
                null
            }
        } catch (_: Throwable) {
            runCatching { outFile.delete() }
            if (!rawBmp.isRecycled) rawBmp.recycle()
            null
        }
    }

    private fun decodeUploadImageThumb(localPath: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(localPath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            localPath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val orientation = runCatching {
            ExifInterface(localPath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return decoded
        }
        return try {
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded && !decoded.isRecycled) decoded.recycle()
            rotated
        } catch (_: Throwable) {
            decoded
        }
    }

    private fun decodeUploadVideoThumb(localPath: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(localPath)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeUploadPdfThumb(localPath: String): Bitmap? {
        val pfd = ParcelFileDescriptor.open(File(localPath), ParcelFileDescriptor.MODE_READ_ONLY)
        return pfd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount <= 0) return@use null
                renderer.openPage(0).use { page ->
                    val maxDim = 320f
                    val scale = minOf(maxDim / page.width.coerceAtLeast(1), maxDim / page.height.coerceAtLeast(1))
                    val w = (page.width * scale).toInt().coerceIn(80, 320)
                    val h = (page.height * scale).toInt().coerceIn(80, 320)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    private fun scaleDownBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = minOf(maxDim.toFloat() / w.coerceAtLeast(1), maxDim.toFloat() / h.coerceAtLeast(1))
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
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

    override suspend fun openStreamFile(remoteId: String): StreamFileInfo? = withContext(dispatchers.io) {
        if (remoteId.isEmpty() || client == null) return@withContext null
        try {
            val file = send<TdApi.File>(TdApi.GetRemoteFile(remoteId, null))
            val path = file.local?.path.orEmpty()
            val done = file.local?.isDownloadingCompleted == true && path.isNotEmpty() && File(path).exists()
            val total = if (file.size > 0L) file.size else file.expectedSize
            StreamFileInfo(
                fileId = file.id,
                size = total,
                localPath = path,
                isCompleted = done,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("openStreamFile failed", t)
            null
        }
    }

    override suspend fun ensureFileRangeDownloaded(fileId: Int, offset: Long, length: Long): StreamFileState? = withContext(dispatchers.io) {
        if (client == null || fileId == 0) return@withContext null
        fun toStreamState(f: TdApi.File): StreamFileState? {
            val loc = f.local ?: return null
            val path = loc.path.orEmpty()
            val done = loc.isDownloadingCompleted && path.isNotEmpty() && File(path).exists()
            return StreamFileState(
                localPath = path,
                downloadOffset = loc.downloadOffset,
                downloadedPrefixSize = loc.downloadedPrefixSize,
                isCompleted = done,
            )
        }
        try {
            val current = send<TdApi.File>(TdApi.GetFile(fileId))
            val currentState = toStreamState(current)
            val safeLength = length.coerceAtLeast(1L)
            val covered = currentState != null && (
                currentState.isCompleted || (
                    currentState.localPath.isNotEmpty() &&
                        currentState.downloadOffset <= offset &&
                        currentState.downloadOffset + currentState.downloadedPrefixSize >= offset + safeLength
                    )
                )
            if (covered) return@withContext currentState

            val updated = withTimeoutOrNull(45_000L) {
                send<TdApi.File>(TdApi.DownloadFile(fileId, 32, offset.coerceAtLeast(0L), safeLength, true))
            } ?: send<TdApi.File>(TdApi.GetFile(fileId))
            toStreamState(updated)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("ensureFileRangeDownloaded failed (fileId=$fileId, offset=$offset)", t)
            null
        }
    }

    override suspend fun cancelFileStream(fileId: Int) {
        if (fileId == 0) return
        try {
            client?.send(TdApi.CancelDownloadFile(fileId, false)) { _ -> }
        } catch (_: Throwable) {}
    }

    override suspend fun downloadFileRangeBytes(
        remoteId: String,
        offset: Long,
        length: Long,
    ): ByteArray? = withContext(dispatchers.io) {
        if (remoteId.isBlank() || length <= 0L) return@withContext null
        try {
            val info = openStreamFile(remoteId) ?: return@withContext null
            val safeOffset = offset.coerceAtLeast(0L)
            val maxAvail = if (info.size > 0L) (info.size - safeOffset).coerceAtLeast(0L) else length
            val safeLen = minOf(length, maxAvail)
            if (safeLen <= 0L) return@withContext null

            val state = ensureFileRangeDownloaded(info.fileId, safeOffset, safeLen)
                ?: return@withContext null
            if (state.localPath.isEmpty()) return@withContext null

            val availPrefix = if (state.isCompleted) {
                safeLen
            } else {
                (state.downloadOffset + state.downloadedPrefixSize - safeOffset).coerceIn(0L, safeLen)
            }
            if (availPrefix <= 0L) return@withContext null

            val out = ByteArray(availPrefix.toInt())
            java.io.RandomAccessFile(state.localPath, "r").use { raf ->
                raf.seek(safeOffset)
                var readTotal = 0
                while (readTotal < out.size) {
                    val n = raf.read(out, readTotal, out.size - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                if (readTotal == out.size) out else out.copyOf(readTotal)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("downloadFileRangeBytes failed", t)
            null
        }
    }

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
        myUserIsPremiumCache = me.isPremium
        me.id
    }

    override suspend fun isPremiumUser(): Boolean = withContext(dispatchers.io) {
        myUserIsPremiumCache?.let { return@withContext it }
        try {
            val me = send<TdApi.User>(TdApi.GetMe())
            myUserIdCache = me.id
            myUserIsPremiumCache = me.isPremium
            me.isPremium
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("isPremiumUser failed", t)
            false
        }
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
            pendingThumbnails.clear()
            scope.cancel()
            client = null
            initializedApiId = null
            initializedApiHash = null
            myUserIdCache = null
            myUserIsPremiumCache = null
            systemChannelIdCache = null
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
        private val IMAGE_THUMB_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        private val VIDEO_THUMB_EXTS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp")
    }
}

sealed interface AuthEvent {
    data object CodeRequested : AuthEvent
    data object PasswordRequired : AuthEvent
    data class Error(val code: Int, val message: String) : AuthEvent
}

typealias SharedFlowLike<T> = kotlinx.coroutines.flow.SharedFlow<T>

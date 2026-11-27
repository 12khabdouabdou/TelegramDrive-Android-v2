@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.tgdrive.core.telegram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.tgdrive.core.common.BitmapUtils
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import com.tgdrive.core.common.CrashLogStore
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
    @Volatile internal var rawSendOverride: ((TdApi.Function<*>, (TdApi.Object?) -> Unit, (Throwable?) -> Unit) -> Unit)? = null
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
    private val completedThumbPaths = ConcurrentHashMap<Pair<Long, Long>, String>()

    private val _newMessages = MutableSharedFlow<TelegramMessage>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val newMessages: kotlinx.coroutines.flow.SharedFlow<TelegramMessage> =
        _newMessages.asSharedFlow()

    private val _thumbnailUpdates = MutableSharedFlow<ThumbnailUpdate>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val thumbnailUpdates: kotlinx.coroutines.flow.SharedFlow<ThumbnailUpdate> =
        _thumbnailUpdates.asSharedFlow()

    private fun recordCompletedThumb(chatId: Long, messageId: Long, path: String) {
        if (completedThumbPaths.size >= MAX_COMPLETED_THUMBS) {
            completedThumbPaths.keys.take(MAX_COMPLETED_THUMBS / 4).forEach { completedThumbPaths.remove(it) }
        }
        completedThumbPaths[chatId to messageId] = path
    }

    override fun getCompletedThumbnailPath(chatId: Long, messageId: Long): String? {
        val path = completedThumbPaths[chatId to messageId] ?: return null
        val f = File(path)
        return if (f.isFile && f.length() > 0L) {
            path
        } else {
            completedThumbPaths.remove(chatId to messageId, path)
            null
        }
    }

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
            recordCompletedThumb(chatId, msgId, path)
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
    private val verifiedChatIds = ConcurrentHashMap.newKeySet<Long>()
    private data class PendingUploadMeta(val caption: String, val prebuiltThumbnailPath: String?)
    private val pendingUploadMeta = ConcurrentHashMap<String, PendingUploadMeta>()
    private val lastUploadThumbPruneMs = AtomicLong(0L)

    private val _messageIdUpdates = MutableSharedFlow<MessageIdUpdate>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messageIdUpdates: kotlinx.coroutines.flow.SharedFlow<MessageIdUpdate> = _messageIdUpdates.asSharedFlow()

    private val initLock = Mutex()
    private val systemChannelLock = Mutex()
    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

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
                    is TdApi.UpdateNewMessage -> {
                        update.message?.toDomainFile()?.let { _newMessages.tryEmit(it) }
                    }
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
                applicationVersion = "1.0.4"
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
                            applicationVersion = "1.0.4"
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
        withContext<T>(dispatchers.io) {
            val bypassGate = function is TdApi.Close || function is TdApi.LogOut
            val retrySafe = isFloodRetrySafe(function)
            for (attempt in 0..MAX_FLOOD_WAIT_RETRIES) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    return@withContext sendGated(bypassGate, function) as T
                } catch (e: TelegramException) {
                    val waitSec = parseFloodWaitSeconds(e.code, e.errorMessage)
                    if (waitSec != null &&
                        waitSec in 1..MAX_AUTO_FLOOD_WAIT_SEC &&
                        attempt < MAX_FLOOD_WAIT_RETRIES &&
                        retrySafe
                    ) {
                        Log.w(
                            "TDLib: FLOOD_WAIT_${waitSec}s on ${function.javaClass.simpleName} " +
                                "(retry ${attempt + 1}/$MAX_FLOOD_WAIT_RETRIES)",
                        )
                        delay((waitSec + 1L) * 1000L)
                    } else {
                        throw e
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("TDLib send(${function.javaClass.simpleName}) failed unexpectedly", t)
                    CrashLogStore.recordNonFatal(appContext, "tdlib_send:${function.javaClass.simpleName}", t)
                    throw t
                }
            }
            throw TelegramException(-1, "Unexpected retry termination for ${function.javaClass.simpleName}")
        }

    private suspend fun sendGated(
        bypassGate: Boolean,
        function: TdApi.Function<*>,
    ): TdApi.Object {
        if (bypassGate) return sendOnce(function)
        requestSemaphore.acquire()
        try {
            return sendOnce(function)
        } finally {
            requestSemaphore.release()
        }
    }

    private suspend fun sendOnce(function: TdApi.Function<*>): TdApi.Object {
        val override = rawSendOverride
        val c = if (override == null) {
            client ?: initLock.withLock { client }
                ?: throw TelegramException(-1, "TDLib not initialised")
        } else {
            null
        }
        return suspendCancellableCoroutine<TdApi.Object> { cont ->
            val onResult: (TdApi.Object?) -> Unit = { result ->
                if (cont.isActive) {
                    when (result) {
                        null -> cont.resumeWithException(
                            IllegalStateException("TDLib returned null response for ${function.javaClass.simpleName}"),
                        )
                        is TdApi.Error -> cont.resumeWithException(TelegramException.fromError(result))
                        else -> cont.resume(result)
                    }
                }
            }
            val onException: (Throwable?) -> Unit = { t ->
                if (cont.isActive) {
                    val cause = t ?: IllegalStateException(
                        "TDLib raised unknown exception for ${function.javaClass.simpleName}",
                    )
                    cont.resumeWithException(cause)
                }
            }
            if (override != null) {
                override(function, onResult, onException)
            } else {
                c!!.send(function, onResult, onException)
            }
        }
    }

    private fun isFloodRetrySafe(function: TdApi.Function<*>): Boolean = when (function) {
        is TdApi.Close,
        is TdApi.LogOut,
        is TdApi.SetAuthenticationPhoneNumber,
        is TdApi.ResendAuthenticationCode,
        is TdApi.CheckAuthenticationCode,
        is TdApi.CheckAuthenticationPassword,
        is TdApi.CheckAuthenticationEmailCode,
        is TdApi.SetAuthenticationEmailAddress,
        is TdApi.RegisterUser,
        is TdApi.RequestQrCodeAuthentication,
        -> false
        else -> true
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
                completedThumbPaths.clear()
                verifiedChatIds.clear()
                pendingUploadMeta.clear()
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
        var eagerDownloads = 0
        return domainMsgs.map { msg ->
            val tdMsg = msgById[msg.id]
            val thumbFileId = tdMsg?.extractThumbnailFileId()
            if (eagerDownloads < 24 &&
                thumbFileId != null &&
                thumbFileId != 0 &&
                tdMsg != null &&
                !tdMsg.isThumbnailDownloaded()
            ) {
                eagerDownloads++
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

    override suspend fun fetchMessagesPage(chatId: Long, fromMessageId: Long, limit: Int): MessagePage =
        withContext(dispatchers.io) {
            if (chatId !in verifiedChatIds) {
                runCatching {
                    send<TdApi.Chat>(TdApi.GetChat(chatId))
                    verifiedChatIds.add(chatId)
                }
            }
            var cursor = fromMessageId
            var reachedEnd = false
            val allRaw = ArrayList<TdApi.Message>(limit)
            val collectedDomain = ArrayList<TelegramMessage>(limit)
            var rounds = 0
            while (collectedDomain.size < limit && rounds < 5) {
                rounds++
                val remaining = (limit - collectedDomain.size).coerceIn(1, 100)
                val resp = send<TdApi.Messages>(TdApi.GetChatHistory(chatId, cursor, 0, remaining, false))
                if (resp.messages.isEmpty()) {
                    reachedEnd = true
                    break
                }
                val oldestRaw = resp.messages.minOfOrNull { it.id } ?: 0L
                allRaw.addAll(resp.messages)
                for (m in resp.messages) {
                    m.toDomainFile()?.let { collectedDomain.add(it) }
                }
                if (oldestRaw == 0L || oldestRaw == cursor) {
                    reachedEnd = true
                    break
                }
                cursor = oldestRaw
            }
            val prepared = prepareMessageThumbnails(allRaw.toTypedArray(), collectedDomain)
            MessagePage(
                messages = prepared,
                nextFromMessageId = cursor,
                reachedEnd = reachedEnd,
            )
        }

    override suspend fun fetchMessages(chatId: Long, fromMessageId: Long, limit: Int): List<TelegramMessage> =
        fetchMessagesPage(chatId, fromMessageId, limit).messages

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

    private suspend fun fetchMainChats(): TdApi.Chats {
        try {
            send<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), 200))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("TDLib: LoadChats failed", t)
        }
        return send<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), 200))
    }

    override suspend fun listFolders(): List<TelegramFolder> = withContext(dispatchers.io) {
        val me = myUserId()
        var resp = fetchMainChats()
        if (resp.chatIds.isEmpty()) {
            delay(500L)
            resp = fetchMainChats()
        }
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

    override suspend fun findAllSystemChannelIds(): List<Long> = withContext(dispatchers.io) {
        var mainResp = fetchMainChats()
        if (mainResp.chatIds.isEmpty()) {
            delay(500L)
            mainResp = fetchMainChats()
        }
        val serverResp = runCatching {
            send<TdApi.Chats>(TdApi.SearchChatsOnServer(TelegramClient.SYSTEM_CHANNEL_TITLE, null, 20))
        }.getOrNull()
        val candidateIds = buildList {
            mainResp.chatIds.forEach { add(it) }
            serverResp?.chatIds?.forEach { add(it) }
        }.distinct()

        val ownedChannels = mutableListOf<Long>()
        for (id in candidateIds) {
            val chat = runCatching { send<TdApi.Chat>(TdApi.GetChat(id)) }.getOrNull() ?: continue
            val type = chat.type
            if (type is TdApi.ChatTypeSupergroup && type.isChannel && chat.title == TelegramClient.SYSTEM_CHANNEL_TITLE) {
                val supergroup = runCatching { send<TdApi.Supergroup>(TdApi.GetSupergroup(type.supergroupId)) }.getOrNull()
                if (supergroup?.status is TdApi.ChatMemberStatusCreator) {
                    ownedChannels.add(chat.id)
                }
            }
        }
        ownedChannels.sorted()
    }

    override suspend fun getOrCreateSystemChannel(): Long? = withContext(dispatchers.io) {
        systemChannelIdCache?.let { return@withContext it }
        systemChannelLock.withLock {
            systemChannelIdCache?.let { return@withLock it }
            try {
                val existing = findAllSystemChannelIds()
                if (existing.isNotEmpty()) {
                    val primary = existing.first()
                    systemChannelIdCache = primary
                    return@withLock primary
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

    override fun registerUploadMetadata(
        localPath: String,
        caption: String,
        prebuiltThumbnailPath: String?,
    ) {
        if (localPath.isBlank()) return
        if (caption.isBlank() && prebuiltThumbnailPath.isNullOrBlank()) {
            pendingUploadMeta.remove(localPath)
        } else {
            pendingUploadMeta[localPath] = PendingUploadMeta(caption, prebuiltThumbnailPath)
        }
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

        if (chatId !in verifiedChatIds) {
            try {
                send<TdApi.Chat>(TdApi.GetChat(chatId))
                verifiedChatIds.add(chatId)
            } catch (t: Throwable) {
                Log.w("uploadFile: GetChat failed (continuing anyway)", t)
            }
        }

        pruneStaleUploadThumbs()
        val meta = pendingUploadMeta.remove(localPath)
        val prebuiltThumbFile = meta?.prebuiltThumbnailPath
            ?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0L }
        val thumbTriple = if (prebuiltThumbFile != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(prebuiltThumbFile.absolutePath, bounds)
            val w = if (bounds.outWidth > 0) bounds.outWidth.coerceIn(1, 320) else 320
            val h = if (bounds.outHeight > 0) bounds.outHeight.coerceIn(1, 320) else 320
            Triple(prebuiltThumbFile, w, h)
        } else {
            buildUploadThumbnail(localPath, fileName, mimeType)
        }
        val uploadThumbFile = thumbTriple?.first
        val createdByClient = prebuiltThumbFile == null && uploadThumbFile != null
        var keepGeneratedThumb = false
        val inputThumb = thumbTriple?.let { (f, w, h) ->
            TdApi.InputThumbnail(TdApi.InputFileLocal(f.absolutePath), w, h)
        }
        val captionText = meta?.caption.orEmpty()
        val inputContent = TdApi.InputMessageDocument(
            TdApi.InputDocument(TdApi.InputFileLocal(localPath), inputThumb, false),
            TdApi.FormattedText(captionText, emptyArray()),
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

            fun readyThumbPath(): String? =
                uploadThumbFile?.takeIf { it.isFile && it.length() > 0L }?.absolutePath

            orphanedSendSucceeded.remove(localMsgId)?.let { orphaned ->
                pendingSendConfirmations.remove(localMsgId)
                keepGeneratedThumb = true
                onProgress(totalSize, totalSize)
                return@withContext UploadResult(orphaned.id, orphaned.extractRemoteId(), readyThumbPath())
            }
            orphanedSendFailed.remove(localMsgId)?.let { err ->
                pendingSendConfirmations.remove(localMsgId)
                throw TelegramException.fromError(err)
            }

            if (sendConfirmation.isCompleted) {
                val serverMsg = sendConfirmation.await()
                keepGeneratedThumb = true
                onProgress(totalSize, totalSize)
                return@withContext UploadResult(serverMsg.id, serverMsg.extractRemoteId(), readyThumbPath())
            }

            if (fileId != null) {
                val progressChannel = Channel<TdApi.File>(Channel.BUFFERED)
                val uploadHandler: (TdApi.File) -> Unit = { f -> progressChannel.trySend(f) }
                registerFileHandler(uploadHandlers, fileId, uploadHandler)
                val lastActivityMs = AtomicLong(System.currentTimeMillis())
                val lastUploadedBytes = AtomicLong(-1L)
                val bytesCompletedAtMs = AtomicLong(0L)
                var uploadConfirmed = false

                val progressJob = launch {
                    for (f in progressChannel) {
                        if (!isActive) break
                        val remote = f.remote ?: continue
                        val uploaded = remote.uploadedSize
                        val remoteTotal = f.size.coerceAtLeast(totalSize)
                        if (remote.isUploadingCompleted || (remoteTotal > 0L && uploaded >= remoteTotal)) {
                            bytesCompletedAtMs.compareAndSet(0L, System.currentTimeMillis())
                            lastActivityMs.set(System.currentTimeMillis())
                            if (!sendConfirmation.isCompleted) {
                                // Reserve the final tick until TDLib confirms UpdateMessageSendSucceeded.
                                val pendingSent = if (remoteTotal > 1L) remoteTotal - 1L else 0L
                                onProgress(pendingSent, remoteTotal)
                            }
                        } else if (remote.isUploadingActive || uploaded > 0L) {
                            if (uploaded > lastUploadedBytes.get()) {
                                lastUploadedBytes.set(uploaded)
                                lastActivityMs.set(System.currentTimeMillis())
                            }
                            val reported = if (remoteTotal > 1L) uploaded.coerceAtMost(remoteTotal - 1L) else uploaded
                            onProgress(reported, remoteTotal)
                        }
                    }
                }
                val watchdogJob = launch {
                    var lastPollAtMs = 0L
                    while (!sendConfirmation.isCompleted) {
                        delay(2_000L)
                        if (sendConfirmation.isCompleted) break

                        val orphaned = orphanedSendSucceeded.remove(localMsgId)
                        if (orphaned != null) {
                            sendConfirmation.complete(orphaned)
                            break
                        }
                        val orphanedErr = orphanedSendFailed.remove(localMsgId)
                        if (orphanedErr != null) {
                            sendConfirmation.completeExceptionally(TelegramException.fromError(orphanedErr))
                            break
                        }

                        val now = System.currentTimeMillis()
                        val completedAt = bytesCompletedAtMs.get()
                        if (completedAt > 0L) {
                            if (now - lastPollAtMs >= UPLOAD_FINALIZE_POLL_INTERVAL_MS) {
                                lastPollAtMs = now
                                val polled = runCatching {
                                    withTimeoutOrNull(8_000L) {
                                        send<TdApi.Message>(TdApi.GetMessage(chatId, localMsgId))
                                    }
                                }.getOrNull()
                                if (polled != null) {
                                    when (val st = polled.sendingState) {
                                        null -> {
                                            if (polled.id != 0L) {
                                                sendConfirmation.complete(polled)
                                                break
                                            }
                                        }
                                        is TdApi.MessageSendingStateFailed -> {
                                            val err = st.error ?: TdApi.Error().apply {
                                                code = -1
                                                message = "Upload failed on Telegram"
                                            }
                                            sendConfirmation.completeExceptionally(TelegramException.fromError(err))
                                            break
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                            if (now - completedAt >= UPLOAD_FINALIZE_TIMEOUT_MS) {
                                sendConfirmation.completeExceptionally(
                                    TelegramException(
                                        -1,
                                        "Upload finalization timed out (${UPLOAD_FINALIZE_TIMEOUT_MS / 1000}s) for $fileName",
                                    ),
                                )
                                break
                            }
                        } else if (now - lastActivityMs.get() >= UPLOAD_STALL_TIMEOUT_MS) {
                            sendConfirmation.completeExceptionally(
                                TelegramException(
                                    -1,
                                    "Upload stalled (no progress for ${UPLOAD_STALL_TIMEOUT_MS / 1000}s) for $fileName",
                                ),
                            )
                            break
                        }
                    }
                }

                try {
                    val serverMsg = sendConfirmation.await()
                    uploadConfirmed = true
                    keepGeneratedThumb = true
                    val serverId = serverMsg.id
                    val remoteId = serverMsg.extractRemoteId()
                    onProgress(totalSize, totalSize)
                    return@withContext UploadResult(serverId, remoteId, readyThumbPath())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!uploadConfirmed && localMsgId != 0L) {
                        try {
                            client?.send(TdApi.DeleteMessages(chatId, longArrayOf(localMsgId), true)) { }
                        } catch (_: Throwable) {}
                    }
                    throw e
                } catch (t: Throwable) {
                    if (!uploadConfirmed && localMsgId != 0L) {
                        try {
                            client?.send(TdApi.DeleteMessages(chatId, longArrayOf(localMsgId), true)) { }
                        } catch (_: Throwable) {}
                    }
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
                        keepGeneratedThumb = true
                        onProgress(totalSize, totalSize)
                        return@withContext UploadResult(serverMsg.id, serverMsg.extractRemoteId(), readyThumbPath())
                    }
                    if (localMsgId != 0L) {
                        try {
                            client?.send(TdApi.DeleteMessages(chatId, longArrayOf(localMsgId), true)) { }
                        } catch (_: Throwable) {}
                    }
                    throw TelegramException(-1, "Upload timed out for $fileName")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (localMsgId != 0L) {
                        try {
                            client?.send(TdApi.DeleteMessages(chatId, longArrayOf(localMsgId), true)) { }
                        } catch (_: Throwable) {}
                    }
                    throw e
                } finally {
                    pendingSendConfirmations.remove(localMsgId)
                }
            }
        } finally {
            if (createdByClient && !keepGeneratedThumb) {
                runCatching { uploadThumbFile?.delete() }
            }
        }
    }

    private fun pruneStaleUploadThumbs() {
        val now = System.currentTimeMillis()
        val prev = lastUploadThumbPruneMs.get()
        if (now - prev < 30L * 60 * 1000L || !lastUploadThumbPruneMs.compareAndSet(prev, now)) return
        val dir = File(appContext.cacheDir, "upload_thumbs")
        if (!dir.isDirectory) return
        val cutoff = now - 60L * 60 * 1000L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
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
                mime == "application/zip" || ext == "zip" -> decodeUploadZipCoverThumb(localPath)
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
        val sample = BitmapUtils.computeInSampleSize(bounds.outWidth, bounds.outHeight, 640)
        val decoded = BitmapFactory.decodeFile(
            localPath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return BitmapUtils.applyExifOrientationFromFile(localPath, decoded)
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

    private fun decodeUploadPdfThumb(localPath: String): Bitmap? = synchronized(PDF_RENDER_LOCK) {
        val pfd = ParcelFileDescriptor.open(File(localPath), ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount <= 0) return@synchronized null
                renderer.openPage(0).use { page ->
                    val maxDim = 320f
                    val scale = minOf(maxDim / page.width.coerceAtLeast(1), maxDim / page.height.coerceAtLeast(1))
                    val w = (page.width * scale).toInt().coerceIn(48, 320)
                    val h = (page.height * scale).toInt().coerceIn(48, 320)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    private fun decodeUploadZipCoverThumb(localPath: String): Bitmap? {
        return ZipFile(File(localPath)).use { zip ->
            val entries = zip.entries()
            var candidate: java.util.zip.ZipEntry? = null
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (ext in IMAGE_THUMB_EXTS && entry.size in 1L..MAX_ZIP_COVER_BYTES) {
                    candidate = entry
                    break
                }
            }
            val hit = candidate ?: return@use null
            val bytes = zip.getInputStream(hit).use { input ->
                val buf = ByteArray(16 * 1024)
                val out = ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_ZIP_COVER_BYTES) return@use null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return@use null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
            var sample = 1
            while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
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

    override suspend fun refreshMessageRemoteId(chatId: Long, messageId: Long): String? = withContext(dispatchers.io) {
        if (chatId == 0L || messageId == 0L || client == null) return@withContext null
        try {
            runCatching { send<TdApi.Chat>(TdApi.GetChat(chatId)) }
            val msg = send<TdApi.Message>(TdApi.GetMessage(chatId, messageId))
            msg.extractRemoteId().takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("refreshMessageRemoteId failed for chat=$chatId msg=$messageId", t)
            null
        }
    }

    override suspend fun requestMessageThumbnail(chatId: Long, messageId: Long): String? = withContext(dispatchers.io) {
        val activeClient = client ?: return@withContext null
        if (chatId == 0L || messageId == 0L) return@withContext null
        try {
            if (chatId !in verifiedChatIds) {
                runCatching {
                    send<TdApi.Chat>(TdApi.GetChat(chatId))
                    verifiedChatIds.add(chatId)
                }
            }
            val tdMsg = send<TdApi.Message>(TdApi.GetMessage(chatId, messageId))
            val thumbFileId = tdMsg.extractThumbnailFileId()
            if (thumbFileId != null && thumbFileId != 0) {
                if (tdMsg.isThumbnailDownloaded()) {
                    val existing = tdMsg.toDomainFile()?.thumbnailPath
                    if (!existing.isNullOrBlank()) {
                        val f = File(existing)
                        if (f.isFile && f.length() > 0L) {
                            recordCompletedThumb(chatId, messageId, existing)
                            return@withContext existing
                        }
                    }
                }
                val thumbDeferred = CompletableDeferred<String?>()
                val dlHandler: (TdApi.File) -> Unit = { f ->
                    val loc = f.local
                    if (loc?.isDownloadingCompleted == true) {
                        val p = loc.path.orEmpty()
                        val verified = if (p.isNotEmpty() && File(p).let { it.isFile && it.length() > 0L }) p else null
                        thumbDeferred.complete(verified)
                    } else if (loc != null && !loc.isDownloadingActive && !loc.canBeDownloaded) {
                        thumbDeferred.complete(null)
                    }
                }
                registerFileHandler(downloadHandlers, thumbFileId, dlHandler)
                val path = try {
                    activeClient.send(TdApi.DownloadFile(thumbFileId, 24, 0, 0, false)) { res ->
                        when (res) {
                            is TdApi.Error -> thumbDeferred.complete(null)
                            is TdApi.File -> dlHandler(res)
                        }
                    }
                    withTimeoutOrNull(8_000L) { thumbDeferred.await() }
                } finally {
                    unregisterFileHandler(downloadHandlers, thumbFileId, dlHandler)
                    if (!thumbDeferred.isCompleted && downloadHandlers[thumbFileId].isNullOrEmpty()) {
                        runCatching {
                            activeClient.send(TdApi.CancelDownloadFile(thumbFileId, false)) { }
                        }
                    }
                }
                if (!path.isNullOrEmpty()) {
                    recordCompletedThumb(chatId, messageId, path)
                    _thumbnailUpdates.tryEmit(ThumbnailUpdate(chatId, messageId, path))
                    return@withContext path
                }
            }
            val domain = tdMsg.toDomainFile()
            val miniBytes = domain?.minithumbnailBytes
            if (miniBytes != null && miniBytes.isNotEmpty()) {
                val miniDir = File(appContext.cacheDir, "minithumbs").apply { mkdirs() }
                val miniFile = File(miniDir, "${chatId}_${messageId}.jpg")
                if (!miniFile.isFile || miniFile.length() <= 0L) {
                    val tmp = File(miniDir, "${chatId}_${messageId}.tmp")
                    tmp.writeBytes(miniBytes)
                    if (miniFile.exists()) miniFile.delete()
                    if (!tmp.renameTo(miniFile)) {
                        tmp.copyTo(miniFile, overwrite = true)
                        tmp.delete()
                    }
                }
                if (miniFile.isFile && miniFile.length() > 0L) {
                    return@withContext miniFile.absolutePath
                }
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("requestMessageThumbnail failed for chat=$chatId msg=$messageId", t)
            null
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
                val cachedFile = if (p.isNotEmpty()) File(p) else null
                if (cachedFile != null && cachedFile.isFile && cachedFile.length() > 0L) {
                    emit(DownloadUpdate.Done(p))
                    return@flow
                }
                Log.i("downloadFile: stale cache, re-downloading")
                try { send<TdApi.Ok>(TdApi.DeleteFile(file.id)) } catch (_: Throwable) {}
            }

            val progressChannel = Channel<DownloadUpdate.Progress>(
                capacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val terminal = CompletableDeferred<DownloadUpdate>()
            val dlHandler: (TdApi.File) -> Unit = { f ->
                try {
                    val fLocal = f.local
                    if (fLocal != null) {
                        if (fLocal.isDownloadingCompleted) {
                            val pp = fLocal.path.orEmpty()
                            val downloadedFile = if (pp.isNotEmpty()) File(pp) else null
                            val doneOrFail = if (downloadedFile != null && downloadedFile.isFile && downloadedFile.length() > 0L) {
                                DownloadUpdate.Done(pp)
                            } else {
                                DownloadUpdate.Failed("File missing or empty after download")
                            }
                            terminal.complete(doneOrFail)
                            progressChannel.close()
                        } else {
                            val total = if (f.size > 0L) f.size else f.expectedSize
                            progressChannel.trySend(DownloadUpdate.Progress(fLocal.downloadedSize, total))
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("TDLib download handler error", t)
                    terminal.complete(DownloadUpdate.Failed(t.message ?: "Download update error"))
                    progressChannel.close()
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
                                    terminal.complete(DownloadUpdate.Failed(result.message ?: "Error"))
                                    progressChannel.close()
                                }
                                is TdApi.File -> {
                                    val rLocal = result.local
                                    if (rLocal?.isDownloadingCompleted == true) {
                                        val pp = rLocal.path.orEmpty()
                                        val downloadedFile = if (pp.isNotEmpty()) File(pp) else null
                                        val doneOrFail = if (downloadedFile != null && downloadedFile.isFile && downloadedFile.length() > 0L) {
                                            DownloadUpdate.Done(pp)
                                        } else {
                                            DownloadUpdate.Failed("File missing or empty after download")
                                        }
                                        terminal.complete(doneOrFail)
                                        progressChannel.close()
                                    } else if (rLocal != null && !rLocal.isDownloadingActive && !rLocal.canBeDownloaded) {
                                        terminal.complete(DownloadUpdate.Failed("File cannot be downloaded"))
                                        progressChannel.close()
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w("TDLib download result error", t)
                            terminal.complete(DownloadUpdate.Failed(t.message ?: "Download callback error"))
                            progressChannel.close()
                        }
                    },
                    { t ->
                        Log.w("TDLib download request exception", t)
                        terminal.complete(DownloadUpdate.Failed(t.message ?: "Download failed"))
                        progressChannel.close()
                    },
                )
                val stallTimeoutMs = 120_000L
                var lastActivityAt = System.currentTimeMillis()
                while (!terminal.isCompleted) {
                    val remaining = (stallTimeoutMs - (System.currentTimeMillis() - lastActivityAt)).coerceAtLeast(1_000L)
                    val step = withTimeoutOrNull(remaining) {
                        select<DownloadUpdate?> {
                            terminal.onAwait { it }
                            progressChannel.onReceiveCatching { res -> res.getOrNull() }
                        }
                    }
                    if (step == null) {
                        if (terminal.isCompleted) break
                        emit(DownloadUpdate.Failed("Download stalled"))
                        break
                    }
                    lastActivityAt = System.currentTimeMillis()
                    if (step is DownloadUpdate.Done) {
                        completed = true
                        emit(step)
                        break
                    }
                    if (step is DownloadUpdate.Failed) {
                        emit(step)
                        break
                    }
                    emit(step)
                }
                if (!completed && terminal.isCompleted) {
                    val finalUpd = terminal.await()
                    if (finalUpd is DownloadUpdate.Done) completed = true
                    emit(finalUpd)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                emit(DownloadUpdate.Failed(t.message ?: "Download failed"))
            } finally {
                unregisterFileHandler(downloadHandlers, file.id, dlHandler)
                progressChannel.close()
                if (!completed && downloadHandlers[file.id].isNullOrEmpty()) {
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

    override suspend fun optimizeStorage(maxBytes: Long): Unit = withContext(dispatchers.io) {
        try {
            val safeLimit = maxBytes.coerceAtLeast(50L * 1024 * 1024)
            val optimize = TdApi.OptimizeStorage().apply {
                size = safeLimit
                ttl = 30 * 24 * 3600
                count = -1
                immunityDelay = 3600
                fileTypes = arrayOf()
            }
            send<TdApi.StorageStatistics>(optimize)
            Log.i("optimizeStorage: trimmed cache toward $safeLimit bytes")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("optimizeStorage: OptimizeStorage failed", t)
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
            completedThumbPaths.clear()
            verifiedChatIds.clear()
            pendingUploadMeta.clear()
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
        private val PDF_RENDER_LOCK = Any()
        private const val UPLOAD_STALL_TIMEOUT_MS = 90_000L
        private const val UPLOAD_FINALIZE_POLL_INTERVAL_MS = 10_000L
        private const val UPLOAD_FINALIZE_TIMEOUT_MS = 180_000L
        private const val UPLOAD_NO_FILE_ID_TIMEOUT_MS = 60_000L
        private const val MAX_ORPHAN_CACHE = 256
        private const val MAX_COMPLETED_THUMBS = 4000
        private const val MAX_ZIP_COVER_BYTES = 10L * 1024 * 1024
        private const val MAX_CONCURRENT_REQUESTS = 16
        private const val MAX_FLOOD_WAIT_RETRIES = 2
        private const val MAX_AUTO_FLOOD_WAIT_SEC = 30
        private val FLOOD_WAIT_REGEX = Regex("""FLOOD_WAIT_(\d+)""", RegexOption.IGNORE_CASE)
        private val RETRY_AFTER_REGEX = Regex("""retry[\s_-]*after[\s:=]*(\d+)""", RegexOption.IGNORE_CASE)
        private val IMAGE_THUMB_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        private val VIDEO_THUMB_EXTS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp")

        internal fun parseFloodWaitSeconds(code: Int, message: String?): Int? {
            if (message.isNullOrBlank()) return null
            FLOOD_WAIT_REGEX.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { sec ->
                if (sec > 0) return sec
            }
            if (code == 429 || message.contains("TOO_MANY_REQUESTS", ignoreCase = true)) {
                RETRY_AFTER_REGEX.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { sec ->
                    if (sec > 0) return sec
                }
            }
            return null
        }
    }
}

sealed interface AuthEvent {
    data object CodeRequested : AuthEvent
    data object PasswordRequired : AuthEvent
    data class Error(val code: Int, val message: String) : AuthEvent
}

typealias SharedFlowLike<T> = kotlinx.coroutines.flow.SharedFlow<T>

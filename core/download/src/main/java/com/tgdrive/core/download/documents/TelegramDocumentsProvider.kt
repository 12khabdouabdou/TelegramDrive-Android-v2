package com.tgdrive.core.download.documents

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.OfflinePinStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only DocumentsProvider exposing TelegramDrive's cached files via Android SAF.
 *
 * Security hardening:
 *   - Enforces biometric lock state (`biometricLockEnabled`) before listing or opening files.
 *   - Canonical path validation prevents directory traversal attacks via filenames.
 *   - Throws [FileNotFoundException] when missing or blocked (never returns null).
 *   - Advertises read-only capabilities only (no unimplemented create/delete flags).
 */
class TelegramDocumentsProvider : DocumentsProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DocumentsProviderEntryPoint {
        fun fileCacheDao(): FileCacheDao
        fun folderDao(): FolderDao
        fun appPreferences(): AppPreferences
        fun offlinePinStore(): OfflinePinStore
    }

    private fun entryPoint(): DocumentsProviderEntryPoint? = try {
        context?.applicationContext?.let { appCtx ->
            EntryPointAccessors.fromApplication(
                appCtx,
                DocumentsProviderEntryPoint::class.java,
            )
        }
    } catch (t: Throwable) {
        Log.w("DocumentsProvider: failed to get EntryPoint", t)
        null
    }

    private suspend fun isBiometricLocked(ep: DocumentsProviderEntryPoint): Boolean = try {
        ep.appPreferences().data.first().biometricLockEnabled
    } catch (_: Throwable) {
        false
    }

    companion object {
        const val ROOT_ID = "tgdrive"
        const val ROOT_DISPLAY_NAME = "TelegramDrive"

        private val TDLIB_SUBDIRS = listOf("", "documents", "photos", "videos", "music", "voice")

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = cursor.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_FLAGS, 0)
        row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_save)
        row.add(DocumentsContract.Root.COLUMN_TITLE, ROOT_DISPLAY_NAME)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (documentId == ROOT_ID) {
            addFolderRow(cursor, ROOT_ID, ROOT_DISPLAY_NAME, 0)
            return cursor
        }
        val ep = entryPoint() ?: return cursor
        runBlocking {
            try {
                if (isBiometricLocked(ep)) return@runBlocking
                if (documentId.startsWith("chat_")) {
                    val chatId = documentId.removePrefix("chat_").toLongOrNull() ?: return@runBlocking
                    val title = if (chatId == 0L) {
                        "Saved Messages"
                    } else {
                        ep.folderDao().getByChannelId(chatId)?.title ?: "Folder $chatId"
                    }
                    addFolderRow(cursor, documentId, title, 0)
                } else if (documentId.startsWith("file_")) {
                    val parts = documentId.removePrefix("file_").split("_")
                    if (parts.size == 2) {
                        val chatId = parts[0].toLongOrNull() ?: return@runBlocking
                        val messageId = parts[1].toLongOrNull() ?: return@runBlocking
                        val file = ep.fileCacheDao().getById(chatId, messageId)
                        if (file != null) {
                            addFileRow(cursor, documentId, file)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("DocumentsProvider: queryDocument failed", t)
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val ep = entryPoint() ?: return cursor
        runBlocking {
            try {
                if (isBiometricLocked(ep)) return@runBlocking
                when {
                    parentDocumentId == ROOT_ID -> {
                        val chatIds = ep.fileCacheDao().getAllChatIds()
                        val foldersByChat = ep.folderDao().getAll().associateBy { it.channelId }
                        for (chatId in chatIds) {
                            val title = if (chatId == 0L) {
                                "Saved Messages"
                            } else {
                                foldersByChat[chatId]?.title ?: "Folder $chatId"
                            }
                            addFolderRow(cursor, "chat_$chatId", title, 0)
                        }
                    }
                    parentDocumentId.startsWith("chat_") -> {
                        val chatId = parentDocumentId.removePrefix("chat_").toLongOrNull() ?: return@runBlocking
                        val files = ep.fileCacheDao().forChat(chatId)
                        for (file in files) {
                            addFileRow(cursor, "file_${chatId}_${file.messageId}", file)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("DocumentsProvider: queryChildDocuments failed", t)
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Read-only provider: unsupported mode '$mode'")
        }
        if (!documentId.startsWith("file_")) {
            throw FileNotFoundException("Invalid document ID: $documentId")
        }
        val parts = documentId.removePrefix("file_").split("_")
        if (parts.size != 2) {
            throw FileNotFoundException("Malformed document ID: $documentId")
        }
        val chatId = parts[0].toLongOrNull() ?: throw FileNotFoundException("Invalid chat ID")
        val messageId = parts[1].toLongOrNull() ?: throw FileNotFoundException("Invalid message ID")

        val ctx = context ?: throw FileNotFoundException("Provider context unavailable")
        val ep = entryPoint() ?: throw FileNotFoundException("Application graph unavailable")

        val file = runBlocking {
            if (isBiometricLocked(ep)) {
                throw FileNotFoundException("TelegramDrive is locked")
            }
            ep.fileCacheDao().getById(chatId, messageId)
        } ?: throw FileNotFoundException("Document not in cache: $documentId")

        val rawName = file.fileName
        if (rawName.contains('/') || rawName.contains('\\') || rawName.contains('\u0000')) {
            throw FileNotFoundException("Unsafe filename rejected")
        }
        val safeName = File(rawName).name
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            throw FileNotFoundException("Invalid filename")
        }

        ep.offlinePinStore().getPinnedFilePathSync(chatId, messageId)?.let { pinnedPath ->
            val pinnedFile = File(pinnedPath)
            if (pinnedFile.isFile) {
                return ParcelFileDescriptor.open(pinnedFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        val downloadsRoot = File(ctx.cacheDir, "downloads")
        findContainedFile(downloadsRoot, "", safeName)?.let { safeFile ->
            return ParcelFileDescriptor.open(safeFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val tdlibRoot = File(ctx.filesDir, "tgdrive_tdlib/files")
        for (subdir in TDLIB_SUBDIRS) {
            findContainedFile(tdlibRoot, subdir, safeName)?.let { safeFile ->
                return ParcelFileDescriptor.open(safeFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        throw FileNotFoundException("Cached file not downloaded locally: $documentId")
    }

    private fun findContainedFile(root: File, subdir: String, fileName: String): File? {
        return try {
            val rootCanonical = root.canonicalPath + File.separator
            val parent = if (subdir.isEmpty()) root else File(root, subdir)
            val candidate = File(parent, fileName)
            val candidateCanonical = candidate.canonicalPath
            if (candidateCanonical.startsWith(rootCanonical) && candidate.isFile) {
                candidate
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun addFolderRow(cursor: MatrixCursor, docId: String, name: String, lastModified: Long) {
        val row = cursor.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_SIZE, 0)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, lastModified)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
    }

    private fun addFileRow(cursor: MatrixCursor, docId: String, file: FileCacheEntity) {
        val row = cursor.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.fileName)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, file.mimeType ?: "application/octet-stream")
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.fileSize)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.date)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
    }
}

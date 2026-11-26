package com.tgdrive.core.telegram

import org.drinkless.tdlib.TdApi
import java.io.File

data class TelegramMessage(
    val id: Long,
    val chatId: Long,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val date: Long,
    val thumbnailPath: String?,
    val fileRemoteId: String,
    val isVideo: Boolean,
    val isImage: Boolean,
    val isAudio: Boolean,
    val isDocument: Boolean,
    val minithumbnailBytes: ByteArray? = null,
    val caption: String = "",
    val virtualPathFromCaption: String = "",
)

/** Encodes/decodes normalized subfolder paths inside Telegram message captions as self-describing fallback metadata. */
object TelegramPathTag {
    const val PREFIX = "tgdrive:path="

    fun normalize(raw: String?): String =
        raw.orEmpty()
            .trim()
            .trim('/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    fun formatCaption(virtualPath: String): String {
        val clean = normalize(virtualPath)
        return if (clean.isEmpty()) "" else "$PREFIX$clean"
    }

    fun parseVirtualPath(caption: String?): String {
        if (caption.isNullOrBlank()) return ""
        for (line in caption.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(PREFIX, ignoreCase = true)) {
                return normalize(trimmed.substring(PREFIX.length))
            }
        }
        return ""
    }
}

private fun completedLocalPath(file: TdApi.File?): String? {
    val local = file?.local ?: return null
    val path = local.path.orEmpty()
    if (!local.isDownloadingCompleted || path.isEmpty()) return null
    val f = File(path)
    return path.takeIf { f.isFile && f.length() > 0L }
}

internal fun TdApi.Photo.pickThumbnailPhotoSize(): TdApi.PhotoSize? =
    sizes.filter { it.width >= 240 }.minByOrNull { it.width }
        ?: sizes.maxByOrNull { it.width }

internal fun TdApi.Message.toDomainFile(): TelegramMessage? {
    val dateMs = date.toLong() * 1000L
    return when (val c = content) {
        is TdApi.MessageDocument -> {
            val doc = c.document
            val file = doc.document
            val imageFallback = if (doc.mimeType.startsWith("image/")) completedLocalPath(file) else null
            val cap = c.caption?.text.orEmpty()
            TelegramMessage(
                id = id,
                chatId = chatId,
                fileName = doc.fileName.ifEmpty { "file_${id}" },
                fileSize = file.size,
                mimeType = doc.mimeType,
                date = dateMs,
                thumbnailPath = completedLocalPath(doc.thumbnail?.file) ?: imageFallback,
                fileRemoteId = file.remote.id,
                isVideo = doc.mimeType.startsWith("video/"),
                isImage = doc.mimeType.startsWith("image/"),
                isAudio = doc.mimeType.startsWith("audio/"),
                isDocument = true,
                minithumbnailBytes = doc.minithumbnail?.data,
                caption = cap,
                virtualPathFromCaption = TelegramPathTag.parseVirtualPath(cap),
            )
        }
        is TdApi.MessagePhoto -> {
            val largest = c.photo.sizes.maxByOrNull { it.width } ?: return null
            val thumbSize = c.photo.pickThumbnailPhotoSize()
            val file = largest.photo
            val cap = c.caption?.text.orEmpty()
            TelegramMessage(
                id = id,
                chatId = chatId,
                fileName = "photo_${id}.jpg",
                fileSize = file.size,
                mimeType = "image/jpeg",
                date = dateMs,
                thumbnailPath = completedLocalPath(thumbSize?.photo) ?: completedLocalPath(file),
                fileRemoteId = file.remote.id,
                isVideo = false,
                isImage = true,
                isAudio = false,
                isDocument = false,
                minithumbnailBytes = c.photo.minithumbnail?.data,
                caption = cap,
                virtualPathFromCaption = TelegramPathTag.parseVirtualPath(cap),
            )
        }
        is TdApi.MessageVideo -> {
            val v = c.video
            val file = v.video
            val cap = c.caption?.text.orEmpty()
            TelegramMessage(
                id = id,
                chatId = chatId,
                fileName = v.fileName.ifEmpty { "video_${id}.mp4" },
                fileSize = file.size,
                mimeType = v.mimeType.ifEmpty { "video/mp4" },
                date = dateMs,
                thumbnailPath = completedLocalPath(v.thumbnail?.file),
                fileRemoteId = file.remote.id,
                isVideo = true,
                isImage = false,
                isAudio = false,
                isDocument = false,
                minithumbnailBytes = v.minithumbnail?.data,
                caption = cap,
                virtualPathFromCaption = TelegramPathTag.parseVirtualPath(cap),
            )
        }
        is TdApi.MessageAudio -> {
            val a = c.audio
            val file = a.audio
            val cap = c.caption?.text.orEmpty()
            TelegramMessage(
                id = id,
                chatId = chatId,
                fileName = "${a.performer.ifEmpty { "Unknown" }} - ${a.title.ifEmpty { "track_${id}" }}.mp3",
                fileSize = file.size,
                mimeType = a.mimeType.ifEmpty { "audio/mpeg" },
                date = dateMs,
                thumbnailPath = completedLocalPath(a.albumCoverThumbnail?.file),
                fileRemoteId = file.remote.id,
                isVideo = false,
                isImage = false,
                isAudio = true,
                isDocument = false,
                caption = cap,
                virtualPathFromCaption = TelegramPathTag.parseVirtualPath(cap),
            )
        }
        is TdApi.MessageAnimation -> {
            val anim = c.animation
            val file = anim.animation
            val cap = c.caption?.text.orEmpty()
            TelegramMessage(
                id = id,
                chatId = chatId,
                fileName = anim.fileName.ifEmpty { "animation_${id}.mp4" },
                fileSize = file.size,
                mimeType = anim.mimeType.ifEmpty { "video/mp4" },
                date = dateMs,
                thumbnailPath = completedLocalPath(anim.thumbnail?.file),
                fileRemoteId = file.remote.id,
                isVideo = true,
                isImage = false,
                isAudio = false,
                isDocument = false,
                minithumbnailBytes = anim.minithumbnail?.data,
                caption = cap,
                virtualPathFromCaption = TelegramPathTag.parseVirtualPath(cap),
            )
        }
        else -> null
    }
}

internal fun TdApi.Message.extractRemoteId(): String = when (val c = content) {
    is TdApi.MessageDocument -> c.document?.document?.remote?.id ?: ""
    is TdApi.MessageVideo -> c.video?.video?.remote?.id ?: ""
    is TdApi.MessageAudio -> c.audio?.audio?.remote?.id ?: ""
    is TdApi.MessageAnimation -> c.animation?.animation?.remote?.id ?: ""
    is TdApi.MessagePhoto -> c.photo?.sizes?.maxByOrNull { it.width }?.photo?.remote?.id ?: ""
    else -> ""
}

internal fun TdApi.Message.extractFileId(): Int? = when (val c = content) {
    is TdApi.MessageDocument -> c.document?.document?.id
    is TdApi.MessageVideo -> c.video?.video?.id
    is TdApi.MessageAudio -> c.audio?.audio?.id
    is TdApi.MessageAnimation -> c.animation?.animation?.id
    is TdApi.MessagePhoto -> c.photo?.sizes?.maxByOrNull { it.width }?.photo?.id
    else -> null
}

internal fun TdApi.Message.extractFileSize(): Long = when (val c = content) {
    is TdApi.MessageDocument -> c.document?.document?.size ?: 0L
    is TdApi.MessageVideo -> c.video?.video?.size ?: 0L
    is TdApi.MessageAudio -> c.audio?.audio?.size ?: 0L
    is TdApi.MessageAnimation -> c.animation?.animation?.size ?: 0L
    is TdApi.MessagePhoto -> c.photo?.sizes?.maxByOrNull { it.width }?.photo?.size ?: 0L
    else -> 0L
}

internal fun TdApi.Message.extractThumbnailFileId(): Int? = when (val c = content) {
    is TdApi.MessageDocument -> c.document?.thumbnail?.file?.id
    is TdApi.MessagePhoto -> c.photo?.pickThumbnailPhotoSize()?.photo?.id
    is TdApi.MessageVideo -> c.video?.thumbnail?.file?.id
    is TdApi.MessageAnimation -> c.animation?.thumbnail?.file?.id
    is TdApi.MessageAudio -> c.audio?.albumCoverThumbnail?.file?.id
    else -> null
}

internal fun TdApi.Message.isThumbnailDownloaded(): Boolean {
    val file = when (val c = content) {
        is TdApi.MessageDocument -> c.document?.thumbnail?.file
        is TdApi.MessagePhoto -> c.photo?.pickThumbnailPhotoSize()?.photo
        is TdApi.MessageVideo -> c.video?.thumbnail?.file
        is TdApi.MessageAnimation -> c.animation?.thumbnail?.file
        is TdApi.MessageAudio -> c.audio?.albumCoverThumbnail?.file
        else -> null
    } ?: return false
    return completedLocalPath(file) != null
}

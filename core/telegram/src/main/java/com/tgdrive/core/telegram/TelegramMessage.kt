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
)

internal fun TdApi.Message.toDomainFile(): TelegramMessage? {
    val dateMs = date.toLong() * 1000L
    return when (val c = content) {
        is TdApi.MessageDocument -> {
            val doc = c.document
            val file = doc.document
            TelegramMessage(
                id = id, chatId = chatId,
                fileName = doc.fileName.ifEmpty { "file_${id}" },
                fileSize = file.size,
                mimeType = doc.mimeType,
                date = dateMs,
                thumbnailPath = doc.thumbnail?.file?.local?.path?.takeIf { it.isNotEmpty() },
                fileRemoteId = file.remote.id,
                isVideo = doc.mimeType.startsWith("video/"),
                isImage = doc.mimeType.startsWith("image/"),
                isAudio = doc.mimeType.startsWith("audio/"),
                isDocument = true,
            )
        }
        is TdApi.MessagePhoto -> {
            val largest = c.photo.sizes.maxByOrNull { it.width } ?: return null
            val file = largest.photo
            TelegramMessage(
                id = id, chatId = chatId,
                fileName = "photo_${id}.jpg",
                fileSize = file.size,
                mimeType = "image/jpeg",
                date = dateMs,
                thumbnailPath = file.local.path.takeIf { it.isNotEmpty() },
                fileRemoteId = file.remote.id,
                isVideo = false, isImage = true, isAudio = false, isDocument = false,
            )
        }
        is TdApi.MessageVideo -> {
            val v = c.video
            val file = v.video
            TelegramMessage(
                id = id, chatId = chatId,
                fileName = v.fileName.ifEmpty { "video_${id}.mp4" },
                fileSize = file.size,
                mimeType = v.mimeType.ifEmpty { "video/mp4" },
                date = dateMs,
                thumbnailPath = v.thumbnail?.file?.local?.path?.takeIf { it.isNotEmpty() },
                fileRemoteId = file.remote.id,
                isVideo = true, isImage = false, isAudio = false, isDocument = false,
            )
        }
        is TdApi.MessageAudio -> {
            val a = c.audio
            val file = a.audio
            TelegramMessage(
                id = id, chatId = chatId,
                fileName = "${a.performer.ifEmpty { "Unknown" }} - ${a.title.ifEmpty { "track_${id}" }}.mp3",
                fileSize = file.size,
                mimeType = a.mimeType.ifEmpty { "audio/mpeg" },
                date = dateMs,
                thumbnailPath = a.albumCoverThumbnail?.file?.local?.path?.takeIf { it.isNotEmpty() },
                fileRemoteId = file.remote.id,
                isVideo = false, isImage = false, isAudio = true, isDocument = false,
            )
        }
        is TdApi.MessageAnimation -> {
            val anim = c.animation
            val file = anim.animation
            TelegramMessage(
                id = id, chatId = chatId,
                fileName = anim.fileName.ifEmpty { "animation_${id}.mp4" },
                fileSize = file.size,
                mimeType = anim.mimeType.ifEmpty { "video/mp4" },
                date = dateMs,
                thumbnailPath = anim.thumbnail?.file?.local?.path?.takeIf { it.isNotEmpty() },
                fileRemoteId = file.remote.id,
                isVideo = true, isImage = false, isAudio = false, isDocument = false,
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
    is TdApi.MessageVideo -> c.video?.thumbnail?.file?.id
    is TdApi.MessageAnimation -> c.animation?.thumbnail?.file?.id
    is TdApi.MessageAudio -> c.audio?.albumCoverThumbnail?.file?.id
    else -> null
}

internal fun TdApi.Message.isThumbnailDownloaded(): Boolean {
    val local = when (val c = content) {
        is TdApi.MessageDocument -> c.document?.thumbnail?.file?.local
        is TdApi.MessageVideo -> c.video?.thumbnail?.file?.local
        is TdApi.MessageAnimation -> c.animation?.thumbnail?.file?.local
        is TdApi.MessageAudio -> c.audio?.albumCoverThumbnail?.file?.local
        else -> null
    } ?: return false
    val path = local.path.orEmpty()
    return local.isDownloadingCompleted && path.isNotEmpty() && File(path).exists()
}

package com.tgdrive.feature.browser

import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.db.entity.FileCacheEntity

sealed interface FilePreviewRoute {
    data class Video(
        val messageId: Long,
    ) : FilePreviewRoute

    data class Image(
        val localPath: String,
    ) : FilePreviewRoute

    data class Pdf(
        val messageId: Long,
    ) : FilePreviewRoute

    data class Audio(
        val localPath: String,
        val fileName: String,
    ) : FilePreviewRoute

    data class External(
        val localPath: String,
    ) : FilePreviewRoute

    data class Download(
        val messageId: Long,
    ) : FilePreviewRoute

    data class Zip(
        val messageId: Long,
    ) : FilePreviewRoute
}

fun routeForFile(
    item: FileCacheEntity,
    localPath: String?,
): FilePreviewRoute {
    val bucket = classifyFile(item.mimeType, item.fileName)
    return when {
        bucket == FileBucket.ARCHIVES && item.fileName.lowercase().endsWith(".zip") ->
            FilePreviewRoute.Zip(item.messageId)
        bucket == FileBucket.VIDEOS && localPath != null -> FilePreviewRoute.Video(item.messageId)
        bucket == FileBucket.IMAGES && localPath != null -> FilePreviewRoute.Image(localPath)
        bucket == FileBucket.PDF && localPath != null -> FilePreviewRoute.Pdf(item.messageId)
        bucket == FileBucket.AUDIO && localPath != null -> FilePreviewRoute.Audio(localPath, item.fileName)
        localPath != null -> FilePreviewRoute.External(localPath)
        else -> FilePreviewRoute.Download(item.messageId)
    }
}

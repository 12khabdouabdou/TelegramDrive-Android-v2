package com.tgdrive.app.coil

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.tgdrive.core.download.EncryptedThumbRequest
import com.tgdrive.core.download.MediaThumbnailCache
import okio.Buffer
import java.io.File

class EncryptedThumbKeyer : Keyer<EncryptedThumbRequest> {
    override fun key(data: EncryptedThumbRequest, options: Options): String =
        "enc_thumb:${data.chatId}:${data.messageId}:${data.passphraseFingerprint}"
}

/**
 * Custom Coil [Fetcher] that decrypts `.tgethumb` PassVault previews on demand via
 * [MediaThumbnailCache] (restoring from Telegram System channel when absent locally),
 * eliminating duplicate per-screen `ByteArray` maps.
 */
class EncryptedThumbFetcher(
    private val data: EncryptedThumbRequest,
    private val options: Options,
    private val mediaThumbnailCache: MediaThumbnailCache,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val local = data.localPath
            ?.takeIf { it.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true) && File(it).isFile }
            ?: mediaThumbnailCache.getEncryptedThumbnailPath(data.chatId, data.messageId)
            ?: mediaThumbnailCache.restoreEncryptedThumbFromCloud(data.chatId, data.messageId)
            ?: return null

        val plainBytes = mediaThumbnailCache.decryptThumbnailToBytes(local) ?: return null
        if (plainBytes.isEmpty()) return null

        return SourceResult(
            source = ImageSource(Buffer().write(plainBytes), options.context),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val mediaThumbnailCacheProvider: () -> MediaThumbnailCache,
    ) : Fetcher.Factory<EncryptedThumbRequest> {
        override fun create(
            data: EncryptedThumbRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = EncryptedThumbFetcher(
            data = data,
            options = options,
            mediaThumbnailCache = mediaThumbnailCacheProvider(),
        )
    }
}

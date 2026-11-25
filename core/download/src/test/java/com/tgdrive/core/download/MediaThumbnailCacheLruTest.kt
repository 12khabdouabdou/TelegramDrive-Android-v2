package com.tgdrive.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MediaThumbnailCacheLruTest {

    @Test
    fun `evictOldestFilesToCap returns 0 when under cap`(@TempDir tmp: File) {
        val plainDir = File(tmp, "media_thumbs").apply { mkdirs() }
        val f1 = File(plainDir, "1_1.jpg").apply { writeBytes(ByteArray(100)) }
        val f2 = File(plainDir, "1_2.jpg").apply { writeBytes(ByteArray(150)) }

        val evicted = MediaThumbnailCache.evictOldestFilesToCap(listOf(plainDir), maxBytes = 500L)
        assertThat(evicted).isEqualTo(0)
        assertThat(f1.exists()).isTrue()
        assertThat(f2.exists()).isTrue()
    }

    @Test
    fun `evictOldestFilesToCap deletes oldest files across directories down to hysteresis target`(@TempDir tmp: File) {
        val plainDir = File(tmp, "media_thumbs").apply { mkdirs() }
        val encDir = File(tmp, "encrypted_thumbs").apply { mkdirs() }

        val oldest = File(plainDir, "1_1.jpg").apply {
            writeBytes(ByteArray(400))
            setLastModified(1_000L)
        }
        val olderEnc = File(encDir, "1_2.tgethumb").apply {
            writeBytes(ByteArray(400))
            setLastModified(2_000L)
        }
        val newer = File(plainDir, "1_3.jpg").apply {
            writeBytes(ByteArray(400))
            setLastModified(3_000L)
        }
        val newest = File(encDir, "1_4.tgethumb").apply {
            writeBytes(ByteArray(400))
            setLastModified(4_000L)
        }

        // Total = 1600 bytes, cap = 1000 bytes -> 85% hysteresis target = 850 bytes.
        // Deleting oldest (400) + olderEnc (400) leaves 800 <= 850 bytes.
        val evicted = MediaThumbnailCache.evictOldestFilesToCap(
            dirs = listOf(plainDir, encDir),
            maxBytes = 1_000L,
        )
        assertThat(evicted).isEqualTo(2)
        assertThat(oldest.exists()).isFalse()
        assertThat(olderEnc.exists()).isFalse()
        assertThat(newer.exists()).isTrue()
        assertThat(newest.exists()).isTrue()
    }
}

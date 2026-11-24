package com.tgdrive.core.download

import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DecryptedTempSweeperTest {

    private fun sweeperFor(cacheRoot: File): DecryptedTempSweeper {
        val ctx = object : ContextWrapper(null) {
            override fun getCacheDir(): File = cacheRoot
            override fun getApplicationContext(): Context = this
        }
        return DecryptedTempSweeper(ctx)
    }

    @Test
    fun `sweepExpired deletes expired files while honoring TTL and excluded paths`(@TempDir tmp: File) {
        val cacheDir = File(tmp, "cache").apply { mkdirs() }
        val decryptedDir = File(cacheDir, DecryptedTempSweeper.DECRYPTED_DIR).apply { mkdirs() }
        val extractsDir = File(cacheDir, DecryptedTempSweeper.ZIP_EXTRACTS_DIR).apply { mkdirs() }

        val now = 1_000_000_000L
        val stale = File(decryptedDir, "old_video.mp4").apply {
            writeBytes("stale".toByteArray())
            setLastModified(now - 45 * 60_000L)
        }
        val fresh = File(decryptedDir, "fresh.pdf").apply {
            writeBytes("fresh".toByteArray())
            setLastModified(now - 5 * 60_000L)
        }
        val excluded = File(extractsDir, "playing.mp3").apply {
            writeBytes("audio".toByteArray())
            setLastModified(now - 120 * 60_000L)
        }
        val outside = File(tmp, "Downloads/keep.mp4").apply {
            parentFile?.mkdirs()
            writeBytes("keep".toByteArray())
        }

        val sweeper = sweeperFor(cacheDir)

        // Manual mode (-1) must not delete anything.
        assertThat(sweeper.sweepExpired(ttlMinutes = -1, nowMs = now)).isEqualTo(0)
        assertThat(stale.exists()).isTrue()

        // 30-min TTL deletes stale only, preserving fresh and excluded playing audio.
        val removed30 = sweeper.sweepExpired(ttlMinutes = 30, excludePaths = setOf(excluded.absolutePath), nowMs = now)
        assertThat(removed30).isEqualTo(1)
        assertThat(stale.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
        assertThat(excluded.exists()).isTrue()

        // Stats reflect remaining temp files.
        val (count, bytes) = sweeper.tempCacheStats()
        assertThat(count).isEqualTo(2)
        assertThat(bytes).isEqualTo(fresh.length() + excluded.length())

        // Immediate wipe (ttl=0) removes remaining non-excluded temp files.
        assertThat(sweeper.sweepExpired(ttlMinutes = 0, excludePaths = setOf(excluded.absolutePath), nowMs = now)).isEqualTo(1)
        assertThat(fresh.exists()).isFalse()
        assertThat(excluded.exists()).isTrue()

        // Boundary check prevents deleting files outside cacheDir/decrypted/.
        assertThat(sweeper.deleteIfTempDecrypted(outside.absolutePath)).isFalse()
        assertThat(outside.exists()).isTrue()
    }
}

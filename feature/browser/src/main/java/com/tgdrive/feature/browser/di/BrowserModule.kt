package com.tgdrive.feature.browser.di

import com.tgdrive.core.zip.ZipUploader
import com.tgdrive.feature.browser.ZipAllExtractor
import com.tgdrive.feature.browser.ZipExtractor
import com.tgdrive.feature.browser.ZipLister
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Wires [ZipLister] / [ZipExtractor] / [ZipAllExtractor] to [ZipUploader].
 *
 * Tests substitute these by constructing the ViewModel directly with
 * in-memory fakes (see [com.tgdrive.feature.browser.TestZipUploader]).
 */
@Module
@InstallIn(SingletonComponent::class)
object BrowserModule {
    @Provides
    @Singleton
    fun provideZipLister(): ZipLister =
        ZipLister { zipPath ->
            ZipUploader.listEntries(zipPath)
        }

    @Provides
    @Singleton
    fun provideZipExtractor(): ZipExtractor =
        ZipExtractor { zipPath, entryName, outputDir, password ->
            extractSingleEntry(zipPath, entryName, outputDir, password)
        }

    @Provides
    @Singleton
    fun provideZipAllExtractor(): ZipAllExtractor =
        ZipAllExtractor { zipPath, outputDir, password ->
            ZipUploader.extractAll(zipPath, outputDir, password)
        }

    /**
     * Extract a single entry from a ZIP archive preserving the entry's internal subpath.
     */
    private fun extractSingleEntry(
        zipPath: String,
        entryName: String,
        outputDir: File,
        password: String?,
    ): String? {
        return try {
            outputDir.mkdirs()
            val outRoot = outputDir.canonicalFile.canonicalPath + File.separator
            val openZip =
                if (!password.isNullOrBlank()) {
                    net.lingala.zip4j.ZipFile(File(zipPath), password.toCharArray())
                } else {
                    net.lingala.zip4j.ZipFile(File(zipPath))
                }
            openZip.use { zip ->
                val header = zip.getFileHeader(entryName) ?: return null
                if (header.isDirectory) return null
                val uncomp = header.uncompressedSize.coerceAtLeast(0L)
                if (uncomp > 2L * 1024 * 1024 * 1024) {
                    com.tgdrive.core.common.Log.w("ZipExtractor: entry '$entryName' exceeds 2 GB limit")
                    return null
                }
                if (uncomp > 10L * 1024 * 1024 && header.compressedSize > 0L && (uncomp / header.compressedSize) > 100L) {
                    com.tgdrive.core.common.Log.w("ZipExtractor: suspicious compression ratio in '$entryName'")
                    return null
                }
                val targetFile = File(outputDir, header.fileName)
                if (!targetFile.canonicalFile.canonicalPath.startsWith(outRoot)) {
                    com.tgdrive.core.common.Log.w("ZipExtractor: Zip-Slip entry rejected: '${header.fileName}'")
                    return null
                }
                zip.extractFile(header, outputDir.absolutePath)
                targetFile.absolutePath
            }
        } catch (t: Throwable) {
            com.tgdrive.core.common.Log
                .w("ZipExtractor: failed to extract '$entryName'", t)
            null
        }
    }
}

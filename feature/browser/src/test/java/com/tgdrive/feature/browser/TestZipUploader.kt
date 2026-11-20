package com.tgdrive.feature.browser

import com.tgdrive.core.zip.ZipEntryInfo
import java.io.File

/**
 * Test double for ZipUploader — in-memory entry list + extractable bytes.
 *
 * Real ZipUploader is a stateless `object` calling into zip4j, which is
 * awkward to mock. We instead inject `zipLister` / `zipExtractor`
 * lambdas into the ViewModel so tests can substitute them.
 */
class TestZipUploader(
    private var entries: List<ZipEntryInfo> = emptyList(),
    private var throwOnList: Throwable? = null,
    private var throwOnExtract: Throwable? = null,
    private var extractFailure: Boolean = false,
) : ZipLister,
    ZipExtractor {
    fun setEntries(
        entries: List<ZipEntryInfo>,
        throwOnList: Throwable? = null,
    ) {
        this.entries = entries
        this.throwOnList = throwOnList
    }

    fun setExtractFailure(fail: Boolean) {
        this.extractFailure = fail
    }

    /** Reset all state between tests. */
    fun reset() {
        entries = emptyList()
        throwOnList = null
        throwOnExtract = null
        extractFailure = false
    }

    override fun listEntries(zipPath: String): List<ZipEntryInfo> {
        throwOnList?.let { throw it }
        return entries
    }

    override fun extractEntry(
        zipPath: String,
        entryName: String,
        outputDir: File,
        password: String?,
    ): String? {
        throwOnExtract?.let { throw it }
        if (extractFailure) return null
        val f = File(outputDir, entryName.substringAfterLast('/'))
        f.parentFile?.mkdirs()
        f.writeBytes(byteArrayOf(1, 2, 3))
        return f.absolutePath
    }
}

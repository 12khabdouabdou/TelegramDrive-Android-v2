package com.tgdrive.feature.browser

import com.tgdrive.core.zip.ZipEntryInfo
import java.io.File

/**
 * Test double for ZipUploader — in-memory entry list + extractable bytes.
 */
class TestZipUploader(
    private var entries: List<ZipEntryInfo> = emptyList(),
    private var throwOnList: Throwable? = null,
    private var throwOnExtract: Throwable? = null,
    private var extractFailure: Boolean = false,
    private var requiredPassword: String? = null,
) : ZipLister,
    ZipExtractor,
    ZipAllExtractor {
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

    fun setRequiredPassword(password: String?) {
        this.requiredPassword = password
    }

    /** Reset all state between tests. */
    fun reset() {
        entries = emptyList()
        throwOnList = null
        throwOnExtract = null
        extractFailure = false
        requiredPassword = null
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
        val req = requiredPassword
        if (req != null && password != req) return null
        val f = File(outputDir, entryName.substringAfterLast('/'))
        f.parentFile?.mkdirs()
        f.writeBytes(byteArrayOf(1, 2, 3))
        return f.absolutePath
    }

    override fun extractAll(
        zipPath: String,
        outputDir: File,
        password: String?,
    ): List<File> {
        throwOnExtract?.let { throw it }
        if (extractFailure) throw IllegalStateException("extractAll failed")
        val req = requiredPassword
        if (req != null && password != req) throw IllegalStateException("Wrong password")
        outputDir.mkdirs()
        return entries.filterNot { it.isDirectory }.map { e ->
            val out = File(outputDir, e.name.trimStart('/'))
            out.parentFile?.mkdirs()
            out.writeBytes(byteArrayOf(1, 2, 3))
            out
        }
    }
}

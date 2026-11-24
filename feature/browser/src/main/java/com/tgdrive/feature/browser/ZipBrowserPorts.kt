package com.tgdrive.feature.browser

import com.tgdrive.core.zip.ZipEntryInfo
import java.io.File

/**
 * Lists entries inside a ZIP archive without extracting.
 *
 * Indirection over [com.tgdrive.core.zip.ZipUploader.listEntries] so
 * tests can substitute an in-memory fake. Production code wires the
 * default implementation in [com.tgdrive.feature.browser.di.BrowserModule].
 */
fun interface ZipLister {
    fun listEntries(zipPath: String): List<ZipEntryInfo>
}

/**
 * Extracts a single entry from a ZIP archive into [outputDir].
 *
 * Returns the absolute path of the extracted file, or null on failure.
 * Indirection over [com.tgdrive.core.zip.ZipUploader] for testability.
 */
fun interface ZipExtractor {
    fun extractEntry(
        zipPath: String,
        entryName: String,
        outputDir: File,
        password: String?,
    ): String?
}

/**
 * Extracts all entries from a ZIP archive into [outputDir].
 */
fun interface ZipAllExtractor {
    fun extractAll(
        zipPath: String,
        outputDir: File,
        password: String?,
    ): List<File>
}

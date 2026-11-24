package com.tgdrive.feature.browser.search

import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.core.security.BackupCrypto
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

enum class SearchScopeFilter {
    ALL,
    IN_ZIPS,
    IMAGES,
    VIDEOS,
    AUDIO,
    DOCS,
    OFFLINE,
    STARRED,
    ENCRYPTED,
}

enum class SearchSortOrder {
    RELEVANCE,
    NEWEST,
    LARGEST,
    NAME,
}

data class ParsedSearchQuery(
    val rawQuery: String,
    val textQuery: String,
    val extensions: Set<String> = emptySet(),
    val folderFilter: String? = null,
    val afterEpochMs: Long? = null,
    val beforeEpochMs: Long? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val requireStarred: Boolean = false,
    val requireOffline: Boolean = false,
    val requireEncrypted: Boolean = false,
) {
    val hasOperators: Boolean
        get() = extensions.isNotEmpty() ||
            folderFilter != null ||
            afterEpochMs != null ||
            beforeEpochMs != null ||
            minSizeBytes != null ||
            maxSizeBytes != null ||
            requireStarred ||
            requireOffline ||
            requireEncrypted

    val highlightTokens: List<String>
        get() = SmartSearchEngine.tokenize(textQuery)
}

data class SearchFilterCriteria(
    val folderChatId: Long? = null,
    val folderNames: Map<Long, String> = emptyMap(),
    val afterEpochMs: Long? = null,
    val beforeEpochMs: Long? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val sortOrder: SearchSortOrder = SearchSortOrder.RELEVANCE,
) {
    val hasUiFilters: Boolean
        get() = folderChatId != null ||
            afterEpochMs != null ||
            beforeEpochMs != null ||
            minSizeBytes != null ||
            maxSizeBytes != null ||
            sortOrder != SearchSortOrder.RELEVANCE
}

sealed interface SmartSearchHit {
    val score: Int
    val displayName: String
    val dateMs: Long
    val sizeBytes: Long

    data class CloudFileHit(
        val entity: FileCacheEntity,
        override val score: Int,
        val matchHint: String? = null,
    ) : SmartSearchHit {
        override val displayName: String = entity.fileName
        override val dateMs: Long = entity.date
        override val sizeBytes: Long = entity.fileSize
    }

    data class ZipMemberHit(
        val entry: ZipEntryEntity,
        override val score: Int,
        val matchHint: String? = null,
    ) : SmartSearchHit {
        override val displayName: String = entry.entryName
        override val dateMs: Long = entry.indexedAt
        override val sizeBytes: Long = entry.entrySize
    }
}

/**
 * On-device zero-network smart search engine supporting:
 * - Multi-field matching (filename, notes, folder path, ML Kit AI tags & OCR text, ZIP members)
 * - CamelCase / separator tokenization + bounded Levenshtein typo tolerance
 * - Inline operators (`ext:`, `in:`, `after:`, `before:`, `size:>`, `size:<`, `is:starred|offline|encrypted`)
 * - Structured filters & sorting while suppressing locked items and `.tgethumb` sidecars
 */
object SmartSearchEngine {

    private val TGETHUMB_SUFFIX = ".tgethumb"

    fun parseQuery(rawQuery: String): ParsedSearchQuery {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return ParsedSearchQuery(rawQuery = rawQuery, textQuery = "")

        val parts = splitRespectingQuotes(trimmed)
        val freeTokens = ArrayList<String>(parts.size)
        val extensions = LinkedHashSet<String>()
        var folderFilter: String? = null
        var afterMs: Long? = null
        var beforeMs: Long? = null
        var minSize: Long? = null
        var maxSize: Long? = null
        var reqStarred = false
        var reqOffline = false
        var reqEncrypted = false

        for (token in parts) {
            val lower = token.lowercase()
            when {
                lower.startsWith("ext:") && token.length > 4 -> {
                    token.substring(4).split(',', '|').forEach { ext ->
                        val cleaned = ext.trim().trimStart('.').lowercase()
                        if (cleaned.isNotEmpty()) extensions.add(cleaned)
                    }
                }
                lower.startsWith("in:") && token.length > 3 -> {
                    val value = token.substring(3).trim('"').trim()
                    if (value.isNotEmpty()) folderFilter = value
                }
                lower.startsWith("after:") && token.length > 6 -> {
                    parseDateStartMs(token.substring(6))?.let { afterMs = it }
                }
                lower.startsWith("before:") && token.length > 7 -> {
                    parseDateEndMs(token.substring(7))?.let { beforeMs = it }
                }
                lower.startsWith("size:") && token.length > 5 -> {
                    val expr = token.substring(5).trim()
                    when {
                        expr.startsWith(">") -> parseByteSize(expr.substring(1))?.let { minSize = it }
                        expr.startsWith("<") -> parseByteSize(expr.substring(1))?.let { maxSize = it }
                        else -> parseByteSize(expr)?.let { minSize = it }
                    }
                }
                lower == "is:starred" || lower == "is:fav" || lower == "is:favorite" -> reqStarred = true
                lower == "is:offline" || lower == "is:pinned" -> reqOffline = true
                lower == "is:encrypted" || lower == "is:vault" -> reqEncrypted = true
                else -> freeTokens.add(token)
            }
        }

        return ParsedSearchQuery(
            rawQuery = rawQuery,
            textQuery = freeTokens.joinToString(" ").trim(),
            extensions = extensions,
            folderFilter = folderFilter,
            afterEpochMs = afterMs,
            beforeEpochMs = beforeMs,
            minSizeBytes = minSize,
            maxSizeBytes = maxSize,
            requireStarred = reqStarred,
            requireOffline = reqOffline,
            requireEncrypted = reqEncrypted,
        )
    }

    fun search(
        query: String,
        files: List<FileCacheEntity>,
        zipEntries: List<ZipEntryEntity>,
        scope: SearchScopeFilter = SearchScopeFilter.ALL,
        excludedChatIds: Set<Long> = emptySet(),
        criteria: SearchFilterCriteria = SearchFilterCriteria(),
    ): List<SmartSearchHit> {
        val parsed = parseQuery(query)
        val text = parsed.textQuery
        val queryTokens = tokenize(text)
        val hasActiveCriteria = scope != SearchScopeFilter.ALL || parsed.hasOperators || criteria.hasUiFilters
        if (text.isEmpty() && !hasActiveCriteria) return emptyList()

        val effectiveAfterMs = listOfNotNull(parsed.afterEpochMs, criteria.afterEpochMs).maxOrNull()
        val effectiveBeforeMs = listOfNotNull(parsed.beforeEpochMs, criteria.beforeEpochMs).minOrNull()
        val effectiveMinSize = listOfNotNull(parsed.minSizeBytes, criteria.minSizeBytes).maxOrNull()
        val effectiveMaxSize = listOfNotNull(parsed.maxSizeBytes, criteria.maxSizeBytes).minOrNull()

        val hits = ArrayList<SmartSearchHit>()

        if (scope != SearchScopeFilter.IN_ZIPS) {
            for (file in files) {
                if (file.chatId in excludedChatIds) continue
                if (file.isLocked) continue
                if (file.fileName.endsWith(TGETHUMB_SUFFIX, ignoreCase = true)) continue
                if (!matchesScope(file, scope)) continue
                if (!matchesStructuredFilters(
                        file = file,
                        parsed = parsed,
                        criteria = criteria,
                        effectiveAfterMs = effectiveAfterMs,
                        effectiveBeforeMs = effectiveBeforeMs,
                        effectiveMinSize = effectiveMinSize,
                        effectiveMaxSize = effectiveMaxSize,
                    )
                ) {
                    continue
                }

                if (text.isEmpty()) {
                    hits += SmartSearchHit.CloudFileHit(file, 10)
                    continue
                }

                var bestScore = scoreCandidate(text, queryTokens, file.fileName)
                var hint: String? = null
                val note = file.userNote
                if (!note.isNullOrBlank()) {
                    val noteScore = scoreCandidate(text, queryTokens, note)
                    if (noteScore > 0) {
                        val weighted = (noteScore * 0.9f).toInt().coerceAtLeast(1)
                        if (weighted > bestScore) {
                            bestScore = weighted
                            hint = "Note: ${note.replace('\n', ' ').take(48)}"
                        } else if (hint == null) {
                            hint = "Note: ${note.replace('\n', ' ').take(48)}"
                        }
                    }
                }
                if (file.virtualPath.isNotBlank()) {
                    val pathScore = scoreCandidate(text, queryTokens, file.virtualPath)
                    if (pathScore > 0) {
                        val weighted = (pathScore * 0.85f).toInt().coerceAtLeast(1)
                        if (weighted > bestScore) {
                            bestScore = weighted
                            hint = "Folder: ${file.virtualPath}"
                        } else if (hint == null) {
                            hint = "Folder: ${file.virtualPath}"
                        }
                    }
                }
                val tags = file.aiTags
                if (!tags.isNullOrBlank()) {
                    val tagScore = scoreCandidate(text, queryTokens, tags)
                    if (tagScore > 0) {
                        val weighted = (tagScore * 0.85f).toInt().coerceAtLeast(1)
                        if (weighted > bestScore) {
                            bestScore = weighted
                            hint = "AI tag: ${tags.take(48)}"
                        } else if (hint == null) {
                            hint = "AI tag: ${tags.take(48)}"
                        }
                    }
                }
                val ocr = file.ocrText
                if (!ocr.isNullOrBlank()) {
                    val ocrScore = scoreCandidate(text, queryTokens, ocr)
                    if (ocrScore > 0) {
                        val weighted = (ocrScore * 0.8f).toInt().coerceAtLeast(1)
                        if (weighted > bestScore) {
                            bestScore = weighted
                            hint = "OCR: ${ocr.replace('\n', ' ').take(48)}"
                        } else if (hint == null) {
                            hint = "OCR: ${ocr.replace('\n', ' ').take(48)}"
                        }
                    }
                }
                if (bestScore > 0) {
                    hits += SmartSearchHit.CloudFileHit(file, bestScore, hint)
                }
            }
        }

        val includeZips = scope == SearchScopeFilter.ALL || scope == SearchScopeFilter.IN_ZIPS
        if (includeZips && !parsed.requireStarred && !parsed.requireOffline) {
            for (entry in zipEntries) {
                if (entry.chatId in excludedChatIds) continue
                if (entry.entryName.endsWith(TGETHUMB_SUFFIX, ignoreCase = true)) continue
                if (criteria.folderChatId != null && entry.chatId != criteria.folderChatId) continue
                if (parsed.requireEncrypted && !entry.isEncrypted) continue
                if (parsed.extensions.isNotEmpty()) {
                    val ext = entry.entryName.substringAfterLast('.', "").lowercase()
                    if (ext !in parsed.extensions) continue
                }
                if (parsed.folderFilter != null) {
                    val folderName = criteria.folderNames[entry.chatId].orEmpty()
                    val fNeedle = parsed.folderFilter
                    if (!folderName.contains(fNeedle, ignoreCase = true) &&
                        !entry.entryPath.contains(fNeedle, ignoreCase = true)
                    ) {
                        continue
                    }
                }
                if (effectiveMinSize != null && entry.entrySize < effectiveMinSize) continue
                if (effectiveMaxSize != null && entry.entrySize > effectiveMaxSize) continue

                val target = "${entry.entryName} ${entry.entryPath}"
                val score = if (text.isEmpty()) 5 else scoreCandidate(text, queryTokens, target)
                if (score > 0) {
                    val pathHint = entry.entryPath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
                    hits += SmartSearchHit.ZipMemberHit(entry, score, pathHint?.let { "ZIP path: $it" })
                }
            }
        }

        return when (criteria.sortOrder) {
            SearchSortOrder.RELEVANCE -> hits.sortedWith(
                compareByDescending<SmartSearchHit> { it.score }
                    .thenByDescending { it.dateMs }
                    .thenBy { it.displayName.lowercase() },
            )
            SearchSortOrder.NEWEST -> hits.sortedWith(
                compareByDescending<SmartSearchHit> { it.dateMs }
                    .thenByDescending { it.score },
            )
            SearchSortOrder.LARGEST -> hits.sortedWith(
                compareByDescending<SmartSearchHit> { it.sizeBytes }
                    .thenByDescending { it.score },
            )
            SearchSortOrder.NAME -> hits.sortedWith(
                compareBy<SmartSearchHit> { it.displayName.lowercase() }
                    .thenByDescending { it.score },
            )
        }
    }

    private fun matchesStructuredFilters(
        file: FileCacheEntity,
        parsed: ParsedSearchQuery,
        criteria: SearchFilterCriteria,
        effectiveAfterMs: Long?,
        effectiveBeforeMs: Long?,
        effectiveMinSize: Long?,
        effectiveMaxSize: Long?,
    ): Boolean {
        if (criteria.folderChatId != null && file.chatId != criteria.folderChatId) return false
        if (parsed.requireStarred && !file.isStarred) return false
        if (parsed.requireOffline && !file.isPinnedOffline) return false
        if (parsed.requireEncrypted && !isEncrypted(file)) return false

        if (parsed.extensions.isNotEmpty()) {
            val ext = effectiveExtension(file.fileName)
            if (ext !in parsed.extensions) return false
        }
        if (parsed.folderFilter != null) {
            val folderTitle = criteria.folderNames[file.chatId].orEmpty()
            val needle = parsed.folderFilter
            if (!folderTitle.contains(needle, ignoreCase = true) &&
                !file.virtualPath.contains(needle, ignoreCase = true)
            ) {
                return false
            }
        }
        val dateMs = normalizeEpochMs(file.date)
        if (effectiveAfterMs != null && dateMs < effectiveAfterMs) return false
        if (effectiveBeforeMs != null && dateMs > effectiveBeforeMs) return false
        if (effectiveMinSize != null && file.fileSize < effectiveMinSize) return false
        if (effectiveMaxSize != null && file.fileSize > effectiveMaxSize) return false
        return true
    }

    private fun isEncrypted(file: FileCacheEntity): Boolean {
        return file.isEncryptedVault || BackupCrypto.isEncryptedFileName(file.fileName)
    }

    private fun effectiveExtension(fileName: String): String {
        val stripped = if (BackupCrypto.isEncryptedFileName(fileName)) {
            fileName.dropLast(".tgenc".length)
        } else {
            fileName
        }
        return stripped.substringAfterLast('.', "").lowercase()
    }

    private fun normalizeEpochMs(rawDate: Long): Long {
        return if (rawDate in 1L..9_999_999_999L) rawDate * 1000L else rawDate
    }

    private fun matchesScope(file: FileCacheEntity, scope: SearchScopeFilter): Boolean = when (scope) {
        SearchScopeFilter.ALL -> true
        SearchScopeFilter.IN_ZIPS -> file.fileName.endsWith(".zip", ignoreCase = true)
        SearchScopeFilter.IMAGES -> classifyFile(file.mimeType, file.fileName) == FileBucket.IMAGES
        SearchScopeFilter.VIDEOS -> classifyFile(file.mimeType, file.fileName) == FileBucket.VIDEOS
        SearchScopeFilter.AUDIO -> classifyFile(file.mimeType, file.fileName) == FileBucket.AUDIO
        SearchScopeFilter.DOCS -> {
            val b = classifyFile(file.mimeType, file.fileName)
            b == FileBucket.PDF || b == FileBucket.DOCUMENTS
        }
        SearchScopeFilter.OFFLINE -> file.isPinnedOffline
        SearchScopeFilter.STARRED -> file.isStarred
        SearchScopeFilter.ENCRYPTED -> isEncrypted(file)
    }

    fun tokenize(text: String): List<String> {
        val rawParts = text.split('/', '\\', '_', '-', '.', ' ', '\t', ',', ':')
        val out = ArrayList<String>(rawParts.size)
        for (part in rawParts) {
            if (part.isEmpty()) continue
            var start = 0
            for (idx in 1 until part.length) {
                val prev = part[idx - 1]
                val cur = part[idx]
                if ((prev.isLowerCase() && cur.isUpperCase()) || (prev.isLetter() && cur.isDigit())) {
                    val sub = part.substring(start, idx).lowercase()
                    if (sub.isNotEmpty()) out.add(sub)
                    start = idx
                }
            }
            val tail = part.substring(start).lowercase()
            if (tail.isNotEmpty()) out.add(tail)
        }
        return out.distinct()
    }

    private fun splitRespectingQuotes(input: String): List<String> {
        val tokens = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in input) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    cur.append(ch)
                }
                ch.isWhitespace() && !inQuotes -> {
                    if (cur.isNotEmpty()) {
                        tokens.add(cur.toString())
                        cur.setLength(0)
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())
        return tokens
    }

    private fun parseDateStartMs(raw: String): Long? = runCatching {
        LocalDate.parse(raw.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun parseDateEndMs(raw: String): Long? = runCatching {
        LocalDate.parse(raw.trim())
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli() - 1L
    }.getOrNull()

    internal fun parseByteSize(raw: String): Long? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        val numEnd = s.indexOfFirst { !it.isDigit() && it != '.' }
        val numStr = if (numEnd < 0) s else s.substring(0, numEnd)
        val unit = if (numEnd < 0) "b" else s.substring(numEnd).trim()
        val num = numStr.toDoubleOrNull() ?: return null
        if (num < 0.0) return null
        val mult = when (unit) {
            "", "b" -> 1.0
            "k", "kb" -> 1024.0
            "m", "mb" -> 1024.0 * 1024.0
            "g", "gb" -> 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return (num * mult).toLong()
    }

    private fun scoreCandidate(
        rawQuery: String,
        queryTokens: List<String>,
        candidateText: String,
    ): Int {
        val candidateLower = candidateText.lowercase()
        val qLower = rawQuery.lowercase()
        if (candidateLower == qLower) return 1000
        if (candidateLower.startsWith(qLower)) return 850
        if (candidateLower.contains(qLower)) return 700
        if (queryTokens.isEmpty()) return 0

        val candidateTokens = tokenize(candidateText)
        if (candidateTokens.isEmpty()) return 0

        var matched = 0
        var totalScore = 0
        for (qt in queryTokens) {
            var bestTokenScore = 0
            for (ct in candidateTokens) {
                val score = when {
                    ct == qt -> 120
                    ct.startsWith(qt) -> 95
                    ct.contains(qt) -> 75
                    qt.length >= 4 -> {
                        val maxDist = if (qt.length >= 7) 2 else 1
                        val d = levenshtein(qt, ct)
                        if (d <= maxDist) 65 - (d * 15) else 0
                    }
                    else -> 0
                }
                if (score > bestTokenScore) bestTokenScore = score
            }
            if (bestTokenScore > 0) {
                matched++
                totalScore += bestTokenScore
            }
        }

        val minRequired = if (queryTokens.size <= 2) queryTokens.size else (queryTokens.size + 1) / 2
        if (matched < minRequired) return 0
        return totalScore + (matched * 20)
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (abs(a.length - b.length) > 3) return 4
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prevDiag = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prevDiag + cost)
                prevDiag = tmp
            }
        }
        return dp[b.length]
    }
}

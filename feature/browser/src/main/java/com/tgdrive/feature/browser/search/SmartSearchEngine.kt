package com.tgdrive.feature.browser.search

import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.ZipEntryEntity

enum class SearchScopeFilter {
    ALL,
    IN_ZIPS,
    IMAGES,
    VIDEOS,
    AUDIO,
    DOCS,
    OFFLINE,
    STARRED,
}

sealed interface SmartSearchHit {
    val score: Int
    val displayName: String

    data class CloudFileHit(
        val entity: FileCacheEntity,
        override val score: Int,
    ) : SmartSearchHit {
        override val displayName: String = entity.fileName
    }

    data class ZipMemberHit(
        val entry: ZipEntryEntity,
        override val score: Int,
    ) : SmartSearchHit {
        override val displayName: String = entry.entryName
    }
}

/**
 * On-device zero-network smart search engine combining tokenization (`_`, `-`, `.`,
 * spaces, camelCase) and bounded Levenshtein typo-tolerant matching across normal
 * cloud files and indexed ZIP entries.
 */
object SmartSearchEngine {

    fun search(
        query: String,
        files: List<FileCacheEntity>,
        zipEntries: List<ZipEntryEntity>,
        scope: SearchScopeFilter = SearchScopeFilter.ALL,
        excludedChatIds: Set<Long> = emptySet(),
    ): List<SmartSearchHit> {
        val trimmed = query.trim()
        val queryTokens = tokenize(trimmed)
        if (trimmed.isEmpty() && scope == SearchScopeFilter.ALL) return emptyList()

        val hits = ArrayList<SmartSearchHit>()

        if (scope != SearchScopeFilter.IN_ZIPS) {
            for (file in files) {
                if (file.chatId in excludedChatIds) continue
                if (!matchesScope(file, scope)) continue
                val score = if (trimmed.isEmpty()) 10 else scoreCandidate(trimmed, queryTokens, file.fileName)
                if (score > 0) {
                    hits += SmartSearchHit.CloudFileHit(file, score)
                }
            }
        }

        if (scope == SearchScopeFilter.ALL || scope == SearchScopeFilter.IN_ZIPS) {
            for (entry in zipEntries) {
                if (entry.chatId in excludedChatIds) continue
                val target = "${entry.entryName} ${entry.entryPath}"
                val score = if (trimmed.isEmpty()) 5 else scoreCandidate(trimmed, queryTokens, target)
                if (score > 0) {
                    // Slight preference when exact filename inside ZIP matches strongly
                    hits += SmartSearchHit.ZipMemberHit(entry, score)
                }
            }
        }

        return hits.sortedWith(
            compareByDescending<SmartSearchHit> { it.score }
                .thenBy { it.displayName.lowercase() },
        )
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
    }

    fun tokenize(text: String): List<String> {
        val rawParts = text.split('/', '\\', '_', '-', '.', ' ', '\t')
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

        // Require all query tokens to match when <=2 tokens, or at least half for longer queries
        val minRequired = if (queryTokens.size <= 2) queryTokens.size else (queryTokens.size + 1) / 2
        if (matched < minRequired) return 0
        return totalScore + (matched * 20)
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 3) return 4
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

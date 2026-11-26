package com.tgdrive.feature.browser.search

import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.core.security.BackupCrypto
import java.time.Instant
import java.time.LocalDate
import java.time.Month
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
    val inferredScope: SearchScopeFilter? = null,
    val inferredSort: SearchSortOrder? = null,
    val interpretedChips: List<String> = emptyList(),
    val hasNaturalIntents: Boolean = false,
) {
    val hasOperators: Boolean
        get() = hasExplicitOperators

    private var hasExplicitOperators: Boolean =
        extensions.isNotEmpty() ||
            folderFilter != null ||
            afterEpochMs != null ||
            beforeEpochMs != null ||
            minSizeBytes != null ||
            maxSizeBytes != null ||
            requireStarred ||
            requireOffline ||
            requireEncrypted

    internal fun withExplicitFlag(explicit: Boolean): ParsedSearchQuery {
        hasExplicitOperators = explicit
        return this
    }

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
 * - Natural language query understanding (dates, media types, sizes, folders, vault/starred/offline flags)
 * - Cross-field semantic fusion (filename, notes, folder path, EXIF place tags, audio ID3 tags,
 *   ML Kit AI labels, OCR/text snippets, date tokens, and ZIP members)
 * - Bilingual EN/FR stemming, diacritic folding, concept synonym expansion, and typo tolerance
 * - Full support for PassVault `.tgenc` effective file types and local-only Vault search
 */
object SmartSearchEngine {

    private const val TGETHUMB_SUFFIX = ".tgethumb"

    private enum class MatchField(val weight: Float) {
        FILENAME(1.0f),
        NOTE(0.95f),
        LOCATION(0.92f),
        AUDIO(0.90f),
        FOLDER(0.88f),
        AI_TAGS(0.85f),
        OCR(0.82f),
        DATE(0.75f),
    }

    private data class QueryTokenMeta(
        val raw: String,
        val norm: String,
        val stem: String,
        val concepts: Set<String>,
    )

    private data class CandidateField(
        val field: MatchField,
        val rawText: String,
        val normalizedText: String,
        val tokens: List<String>,
        val stems: List<String>,
    )

    private data class TokenFieldMatch(
        val field: MatchField,
        val score: Int,
        val conceptMatchedTerm: String? = null,
    )

    fun parseQuery(
        rawQuery: String,
        knownFolderNames: Collection<String> = emptyList(),
        nowDate: LocalDate = LocalDate.now(),
    ): ParsedSearchQuery {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return ParsedSearchQuery(rawQuery = rawQuery, textQuery = "")

        val parts = splitRespectingQuotes(trimmed)
        val freeTokens = ArrayList<String>(parts.size)
        val extensions = LinkedHashSet<String>()
        var explicitFolderFilter: String? = null
        var explicitAfterMs: Long? = null
        var explicitBeforeMs: Long? = null
        var explicitMinSize: Long? = null
        var explicitMaxSize: Long? = null
        var explicitStarred = false
        var explicitOffline = false
        var explicitEncrypted = false

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
                    if (value.isNotEmpty()) explicitFolderFilter = value
                }
                lower.startsWith("after:") && token.length > 6 -> {
                    parseDateStartMs(token.substring(6))?.let { explicitAfterMs = it }
                }
                lower.startsWith("before:") && token.length > 7 -> {
                    parseDateEndMs(token.substring(7))?.let { explicitBeforeMs = it }
                }
                lower.startsWith("size:") && token.length > 5 -> {
                    val expr = token.substring(5).trim()
                    when {
                        expr.startsWith(">") -> parseByteSize(expr.substring(1))?.let { explicitMinSize = it }
                        expr.startsWith("<") -> parseByteSize(expr.substring(1))?.let { explicitMaxSize = it }
                        else -> parseByteSize(expr)?.let { explicitMinSize = it }
                    }
                }
                lower == "is:starred" || lower == "is:fav" || lower == "is:favorite" -> explicitStarred = true
                lower == "is:offline" || lower == "is:pinned" -> explicitOffline = true
                lower == "is:encrypted" || lower == "is:vault" -> explicitEncrypted = true
                else -> freeTokens.add(token)
            }
        }

        val hasExplicit = extensions.isNotEmpty() ||
            explicitFolderFilter != null ||
            explicitAfterMs != null ||
            explicitBeforeMs != null ||
            explicitMinSize != null ||
            explicitMaxSize != null ||
            explicitStarred ||
            explicitOffline ||
            explicitEncrypted

        val nl = NaturalLanguageQueryParser.parseNaturalLanguage(
            freeText = freeTokens.joinToString(" "),
            knownFolderNames = knownFolderNames,
            nowDate = nowDate,
        )

        val mergedExts = LinkedHashSet<String>(extensions.size + nl.inferredExtensions.size).apply {
            addAll(extensions)
            addAll(nl.inferredExtensions)
        }

        return ParsedSearchQuery(
            rawQuery = rawQuery,
            textQuery = nl.cleanedTextQuery,
            extensions = mergedExts,
            folderFilter = explicitFolderFilter ?: nl.inferredFolderFilter,
            afterEpochMs = listOfNotNull(explicitAfterMs, nl.inferredAfterMs).maxOrNull(),
            beforeEpochMs = listOfNotNull(explicitBeforeMs, nl.inferredBeforeMs).minOrNull(),
            minSizeBytes = listOfNotNull(explicitMinSize, nl.inferredMinSizeBytes).maxOrNull(),
            maxSizeBytes = listOfNotNull(explicitMaxSize, nl.inferredMaxSizeBytes).minOrNull(),
            requireStarred = explicitStarred || nl.requireStarred,
            requireOffline = explicitOffline || nl.requireOffline,
            requireEncrypted = explicitEncrypted || nl.requireEncrypted,
            inferredScope = nl.inferredScope,
            inferredSort = nl.inferredSort,
            interpretedChips = nl.interpretedChips,
            hasNaturalIntents = nl.hasIntents,
        ).withExplicitFlag(hasExplicit)
    }

    fun search(
        query: String,
        files: List<FileCacheEntity>,
        zipEntries: List<ZipEntryEntity>,
        scope: SearchScopeFilter = SearchScopeFilter.ALL,
        excludedChatIds: Set<Long> = emptySet(),
        criteria: SearchFilterCriteria = SearchFilterCriteria(),
        includeLocked: Boolean = false,
    ): List<SmartSearchHit> {
        val parsed = parseQuery(query, knownFolderNames = criteria.folderNames.values)
        val text = parsed.textQuery
        val queryTokens = tokenize(text)
        val hasActiveCriteria = scope != SearchScopeFilter.ALL ||
            parsed.hasOperators ||
            parsed.hasNaturalIntents ||
            criteria.hasUiFilters
        if (text.isEmpty() && !hasActiveCriteria) return emptyList()

        val queryMeta = buildQueryTokenMeta(queryTokens)
        val normFullQuery = normalizeForSearch(text)

        val effectiveAfterMs = listOfNotNull(parsed.afterEpochMs, criteria.afterEpochMs).maxOrNull()
        val effectiveBeforeMs = listOfNotNull(parsed.beforeEpochMs, criteria.beforeEpochMs).minOrNull()
        val effectiveMinSize = listOfNotNull(parsed.minSizeBytes, criteria.minSizeBytes).maxOrNull()
        val effectiveMaxSize = listOfNotNull(parsed.maxSizeBytes, criteria.maxSizeBytes).minOrNull()

        val hits = ArrayList<SmartSearchHit>()

        if (scope != SearchScopeFilter.IN_ZIPS && parsed.inferredScope != SearchScopeFilter.IN_ZIPS) {
            for (file in files) {
                if (file.chatId in excludedChatIds) continue
                if (!includeLocked && file.isLocked) continue
                if (file.fileName.endsWith(TGETHUMB_SUFFIX, ignoreCase = true)) continue
                if (!matchesScope(file, scope)) continue
                val nlScope = parsed.inferredScope
                if (nlScope != null && !matchesScope(file, nlScope)) continue
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

                if (queryMeta.isEmpty()) {
                    hits += SmartSearchHit.CloudFileHit(file, 10)
                    continue
                }

                val scored = scoreCloudFileCrossField(
                    file = file,
                    normFullQuery = normFullQuery,
                    queryMeta = queryMeta,
                    criteria = criteria,
                )
                if (scored != null) {
                    hits += SmartSearchHit.CloudFileHit(file, scored.first, scored.second)
                }
            }
        }

        val includeZips = (scope == SearchScopeFilter.ALL || scope == SearchScopeFilter.IN_ZIPS) &&
            (parsed.inferredScope == null || parsed.inferredScope == SearchScopeFilter.IN_ZIPS)
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
                val score = if (queryMeta.isEmpty()) {
                    5
                } else {
                    scoreZipTarget(normFullQuery, queryMeta, target)
                }
                if (score > 0) {
                    val pathHint = entry.entryPath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
                    hits += SmartSearchHit.ZipMemberHit(entry, score, pathHint?.let { "ZIP path: $it" })
                }
            }
        }

        val effectiveSort = if (criteria.sortOrder != SearchSortOrder.RELEVANCE) {
            criteria.sortOrder
        } else {
            parsed.inferredSort ?: SearchSortOrder.RELEVANCE
        }

        return when (effectiveSort) {
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

    private fun buildQueryTokenMeta(queryTokens: List<String>): List<QueryTokenMeta> =
        queryTokens.map { raw ->
            val norm = normalizeForSearch(raw)
            QueryTokenMeta(
                raw = raw,
                norm = norm,
                stem = stemToken(norm),
                concepts = SearchConcepts.expandConcepts(norm),
            )
        }

    private fun scoreCloudFileCrossField(
        file: FileCacheEntity,
        normFullQuery: String,
        queryMeta: List<QueryTokenMeta>,
        criteria: SearchFilterCriteria,
    ): Pair<Int, String?>? {
        val fields = ArrayList<CandidateField>(8)
        val effName = effectiveFileName(file.fileName)
        fields += buildField(MatchField.FILENAME, effName)
        if (!file.userNote.isNullOrBlank()) {
            fields += buildField(MatchField.NOTE, file.userNote!!)
        }
        val tagParts = SearchConcepts.parseAiTagParts(file.aiTags)
        if (tagParts.locations.isNotEmpty()) {
            fields += buildField(MatchField.LOCATION, tagParts.locations.joinToString(", "))
        }
        if (tagParts.audioMeta.isNotEmpty()) {
            fields += buildField(MatchField.AUDIO, tagParts.audioMeta.joinToString(" · "))
        }
        val folderText = buildString {
            criteria.folderNames[file.chatId]?.let { append(it) }
            if (file.virtualPath.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(file.virtualPath)
            }
        }
        if (folderText.isNotEmpty()) {
            fields += buildField(MatchField.FOLDER, folderText)
        }
        if (tagParts.visualTags.isNotEmpty()) {
            fields += buildField(MatchField.AI_TAGS, tagParts.visualTags.joinToString(", "))
        }
        if (!file.ocrText.isNullOrBlank()) {
            fields += buildField(MatchField.OCR, file.ocrText!!)
        }
        buildDateText(file.date)?.let { dateStr ->
            fields += buildField(MatchField.DATE, dateStr)
        }

        var matchedCount = 0
        var totalScore = 0
        var bestEvidence: TokenFieldMatch? = null
        var filenameMatchedAny = false
        var bestConceptTerm: String? = null

        for (qt in queryMeta) {
            var bestForToken: TokenFieldMatch? = null
            for (field in fields) {
                val m = scoreTokenAgainstField(qt, field) ?: continue
                if (bestForToken == null || m.score > bestForToken.score) {
                    bestForToken = m
                }
            }
            if (bestForToken != null) {
                matchedCount++
                totalScore += bestForToken.score
                if (bestForToken.field == MatchField.FILENAME) {
                    filenameMatchedAny = true
                } else if (bestForToken.field != MatchField.DATE) {
                    if (bestEvidence == null || bestForToken.score > bestEvidence.score) {
                        bestEvidence = bestForToken
                    }
                }
                if (bestConceptTerm == null && bestForToken.conceptMatchedTerm != null) {
                    bestConceptTerm = bestForToken.conceptMatchedTerm
                }
            }
        }

        val minRequired = if (queryMeta.size <= 2) queryMeta.size else (queryMeta.size + 1) / 2
        if (matchedCount < minRequired) return null

        // Multi-token cross-field completeness + full-phrase bonus
        var phraseBonus = matchedCount * 20
        if (normFullQuery.isNotEmpty()) {
            val fileNorm = fields[0].normalizedText
            when {
                fileNorm == normFullQuery -> phraseBonus += 800
                fileNorm.startsWith(normFullQuery) -> phraseBonus += 650
                fileNorm.contains(normFullQuery) -> phraseBonus += 450
            }
            for (f in fields.drop(1)) {
                if (f.normalizedText.contains(normFullQuery)) {
                    phraseBonus = maxOf(phraseBonus, (500 * f.field.weight).toInt())
                    if (f.field != MatchField.DATE && (bestEvidence == null || bestEvidence.field != f.field)) {
                        bestEvidence = TokenFieldMatch(f.field, (500 * f.field.weight).toInt())
                    }
                }
            }
        }

        val hint = formatMatchHint(
            file = file,
            tagParts = tagParts,
            bestEvidence = bestEvidence,
            filenameMatchedAny = filenameMatchedAny,
            bestConceptTerm = bestConceptTerm,
        )
        return (totalScore + phraseBonus).coerceAtLeast(1) to hint
    }

    private fun formatMatchHint(
        file: FileCacheEntity,
        tagParts: AiTagParts,
        bestEvidence: TokenFieldMatch?,
        filenameMatchedAny: Boolean,
        bestConceptTerm: String?,
    ): String? {
        if (bestEvidence != null) {
            return when (bestEvidence.field) {
                MatchField.LOCATION -> "Location: ${tagParts.locations.joinToString(", ").take(48)}"
                MatchField.AUDIO -> "Audio: ${tagParts.audioMeta.joinToString(" · ").take(48)}"
                MatchField.NOTE -> "Note: ${file.userNote.orEmpty().replace('\n', ' ').take(48)}"
                MatchField.FOLDER -> file.virtualPath.takeIf { it.isNotBlank() }?.let { "Folder: $it" }
                MatchField.AI_TAGS -> "AI tag: ${tagParts.visualTags.joinToString(", ").take(48)}"
                MatchField.OCR -> "OCR: ${file.ocrText.orEmpty().replace('\n', ' ').take(48)}"
                MatchField.FILENAME, MatchField.DATE -> null
            }
        }
        if (!filenameMatchedAny && bestConceptTerm != null) {
            return "Related: $bestConceptTerm"
        }
        if (bestConceptTerm != null) {
            return "Concept: $bestConceptTerm"
        }
        return null
    }

    private fun buildField(field: MatchField, rawText: String): CandidateField {
        val tokens = tokenizeNormalized(rawText)
        val stems = tokens.map { stemToken(it) }
        return CandidateField(
            field = field,
            rawText = rawText,
            normalizedText = normalizeForSearch(rawText),
            tokens = tokens,
            stems = stems,
        )
    }

    private fun scoreTokenAgainstField(
        qt: QueryTokenMeta,
        field: CandidateField,
    ): TokenFieldMatch? {
        if (field.tokens.isEmpty() || qt.norm.isEmpty()) return null
        var bestRaw = 0
        var conceptTerm: String? = null

        for (i in field.tokens.indices) {
            val ct = field.tokens[i]
            val ctStem = field.stems[i]
            val base = when {
                ct == qt.norm -> 120
                qt.stem.length >= 3 && ctStem == qt.stem -> 110
                ct.startsWith(qt.norm) -> 95
                ct in qt.concepts -> {
                    if (conceptTerm == null) conceptTerm = ct
                    82
                }
                ctStem in qt.concepts -> {
                    if (conceptTerm == null) conceptTerm = ct
                    78
                }
                ct.contains(qt.norm) -> 75
                qt.norm.length >= 4 -> {
                    val maxDist = if (qt.norm.length >= 7) 2 else 1
                    val d = levenshtein(qt.norm, ct)
                    if (d <= maxDist) 65 - (d * 15) else 0
                }
                else -> 0
            }
            if (base > bestRaw) {
                bestRaw = base
                if (base >= 95) conceptTerm = null
            }
        }
        if (bestRaw <= 0) return null
        val weighted = (bestRaw * field.field.weight).toInt().coerceAtLeast(1)
        return TokenFieldMatch(field.field, weighted, conceptTerm)
    }

    private fun scoreZipTarget(
        normFullQuery: String,
        queryMeta: List<QueryTokenMeta>,
        target: String,
    ): Int {
        val field = buildField(MatchField.FILENAME, target)
        if (normFullQuery.isNotEmpty()) {
            when {
                field.normalizedText == normFullQuery -> return 1000
                field.normalizedText.startsWith(normFullQuery) -> return 850
                field.normalizedText.contains(normFullQuery) -> return 700
            }
        }
        var matched = 0
        var totalScore = 0
        for (qt in queryMeta) {
            val m = scoreTokenAgainstField(qt, field)
            if (m != null) {
                matched++
                totalScore += m.score
            }
        }
        val minRequired = if (queryMeta.size <= 2) queryMeta.size else (queryMeta.size + 1) / 2
        if (matched < minRequired) return 0
        return totalScore + (matched * 20)
    }

    private fun buildDateText(rawDate: Long): String? {
        val ms = normalizeEpochMs(rawDate)
        if (ms <= 0L) return null
        val ld = runCatching {
            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull() ?: return null
        val frMonth = FRENCH_MONTHS[ld.month].orEmpty()
        return "${ld.year} ${ld.month.name.lowercase()} $frMonth"
    }

    private val FRENCH_MONTHS: Map<Month, String> = mapOf(
        Month.JANUARY to "janvier",
        Month.FEBRUARY to "fevrier",
        Month.MARCH to "mars",
        Month.APRIL to "avril",
        Month.MAY to "mai",
        Month.JUNE to "juin",
        Month.JULY to "juillet",
        Month.AUGUST to "aout",
        Month.SEPTEMBER to "septembre",
        Month.OCTOBER to "octobre",
        Month.NOVEMBER to "novembre",
        Month.DECEMBER to "decembre",
    )

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
            val folderTitle = normalizeForSearch(criteria.folderNames[file.chatId].orEmpty())
            val vpath = normalizeForSearch(file.virtualPath)
            val needle = normalizeForSearch(parsed.folderFilter)
            if (!folderTitle.contains(needle) && !vpath.contains(needle)) {
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

    fun isEncrypted(file: FileCacheEntity): Boolean {
        return file.isEncryptedVault || BackupCrypto.isEncryptedFileName(file.fileName)
    }

    fun effectiveFileName(fileName: String): String {
        return if (BackupCrypto.isEncryptedFileName(fileName)) {
            BackupCrypto.stripEncryptedExtension(fileName)
        } else {
            fileName
        }
    }

    fun effectiveBucket(file: FileCacheEntity): FileBucket {
        val name = effectiveFileName(file.fileName)
        val mime = if (isEncrypted(file)) null else file.mimeType
        return classifyFile(mime, name)
    }

    private fun effectiveExtension(fileName: String): String {
        return effectiveFileName(fileName).substringAfterLast('.', "").lowercase()
    }

    private fun normalizeEpochMs(rawDate: Long): Long {
        return if (rawDate in 1L..9_999_999_999L) rawDate * 1000L else rawDate
    }

    private fun matchesScope(file: FileCacheEntity, scope: SearchScopeFilter): Boolean = when (scope) {
        SearchScopeFilter.ALL -> true
        SearchScopeFilter.IN_ZIPS -> effectiveFileName(file.fileName).endsWith(".zip", ignoreCase = true)
        SearchScopeFilter.IMAGES -> effectiveBucket(file) == FileBucket.IMAGES
        SearchScopeFilter.VIDEOS -> effectiveBucket(file) == FileBucket.VIDEOS
        SearchScopeFilter.AUDIO -> effectiveBucket(file) == FileBucket.AUDIO
        SearchScopeFilter.DOCS -> {
            val b = effectiveBucket(file)
            b == FileBucket.PDF || b == FileBucket.DOCUMENTS
        }
        SearchScopeFilter.OFFLINE -> file.isPinnedOffline
        SearchScopeFilter.STARRED -> file.isStarred
        SearchScopeFilter.ENCRYPTED -> isEncrypted(file)
    }

    fun tokenize(text: String): List<String> = tokenizeNormalized(text)

    private fun tokenizeNormalized(text: String): List<String> {
        val rawParts = text.split('/', '\\', '_', '-', '.', ' ', '\t', ',', ':')
        val out = ArrayList<String>(rawParts.size)
        for (part in rawParts) {
            if (part.isEmpty()) continue
            var start = 0
            for (idx in 1 until part.length) {
                val prev = part[idx - 1]
                val cur = part[idx]
                if ((prev.isLowerCase() && cur.isUpperCase()) || (prev.isLetter() && cur.isDigit())) {
                    val sub = normalizeForSearch(part.substring(start, idx))
                    if (sub.isNotEmpty()) out.add(sub)
                    start = idx
                }
            }
            val tail = normalizeForSearch(part.substring(start))
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

package com.tgdrive.feature.browser.search

import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId

data class NaturalLanguageResult(
    val cleanedTextQuery: String,
    val inferredScope: SearchScopeFilter? = null,
    val inferredExtensions: Set<String> = emptySet(),
    val inferredFolderFilter: String? = null,
    val inferredAfterMs: Long? = null,
    val inferredBeforeMs: Long? = null,
    val inferredMinSizeBytes: Long? = null,
    val inferredMaxSizeBytes: Long? = null,
    val requireStarred: Boolean = false,
    val requireOffline: Boolean = false,
    val requireEncrypted: Boolean = false,
    val inferredSort: SearchSortOrder? = null,
    val interpretedChips: List<String> = emptyList(),
) {
    val hasIntents: Boolean
        get() = inferredScope != null ||
            inferredExtensions.isNotEmpty() ||
            inferredFolderFilter != null ||
            inferredAfterMs != null ||
            inferredBeforeMs != null ||
            inferredMinSizeBytes != null ||
            inferredMaxSizeBytes != null ||
            requireStarred ||
            requireOffline ||
            requireEncrypted ||
            inferredSort != null
}

/**
 * Translates conversational English/French queries (e.g., `"big pdf invoices from last month"`,
 * `"encrypted photos in Paris"`, `"starred videos"`) into structured filters, date bounds,
 * size bounds, folder hints, and clean topic keywords.
 */
object NaturalLanguageQueryParser {

    private val WHITESPACE_REGEX = Regex("\\s+")

    private val MONTH_MAP: Map<String, Month> = mapOf(
        "january" to Month.JANUARY, "jan" to Month.JANUARY, "janvier" to Month.JANUARY,
        "february" to Month.FEBRUARY, "feb" to Month.FEBRUARY, "fevrier" to Month.FEBRUARY,
        "march" to Month.MARCH, "mar" to Month.MARCH, "mars" to Month.MARCH,
        "april" to Month.APRIL, "apr" to Month.APRIL, "avril" to Month.APRIL,
        "may" to Month.MAY, "mai" to Month.MAY,
        "june" to Month.JUNE, "jun" to Month.JUNE, "juin" to Month.JUNE,
        "july" to Month.JULY, "jul" to Month.JULY, "juillet" to Month.JULY,
        "august" to Month.AUGUST, "aug" to Month.AUGUST, "aout" to Month.AUGUST,
        "september" to Month.SEPTEMBER, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER, "septembre" to Month.SEPTEMBER,
        "october" to Month.OCTOBER, "oct" to Month.OCTOBER, "octobre" to Month.OCTOBER,
        "november" to Month.NOVEMBER, "nov" to Month.NOVEMBER, "novembre" to Month.NOVEMBER,
        "december" to Month.DECEMBER, "dec" to Month.DECEMBER, "decembre" to Month.DECEMBER,
    )

    private val SIZE_GT_REGEX = Regex(
        """\b(?:over|above|bigger than|larger than|more than|greater than|at least|plus de|sup(?:erieur)? a)\s+(\d+(?:\.\d+)?)\s*(gb|go|mb|mo|kb|ko|b|g|m|k)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SIZE_LT_REGEX = Regex(
        """\b(?:under|below|smaller than|less than|at most|moins de|inf(?:erieur)? a)\s+(\d+(?:\.\d+)?)\s*(gb|go|mb|mo|kb|ko|b|g|m|k)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val RELATIVE_COUNT_REGEX = Regex(
        """\b(?:(?:from|in|of|the|dans|des|les)\s+)?(?:last|past)\s+(\d+)\s+(day|days|week|weeks|month|months|year|years)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val FR_RELATIVE_COUNT_REGEX = Regex(
        """\b(\d+)\s+(?:dernier|derniers|derniere|dernieres)\s+(jour|jours|semaine|semaines|mois|an|ans|annee|annees)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PREPOSITION_YEAR_REGEX = Regex(
        """\b(in|from|since|after|before|en|depuis|avant|apres)\s+(19\d{2}|20\d{2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MONTH_YEAR_REGEX = Regex(
        """\b(?:(?:in|from|during|of|en|de|dans)\s+)?([a-z]+)\s+(19\d{2}|20\d{2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PREPOSITION_MONTH_REGEX = Regex(
        """\b(?:in|from|during|of|en|de|dans)\s+([a-z]+)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val FOLDER_PHRASE_REGEX = Regex(
        """\b(?:in|inside|from|dans)\s+(?:folder\s+|dossier\s+)?([a-z0-9_.\-]+(?:\s+[a-z0-9_.\-]+)?)""",
        RegexOption.IGNORE_CASE,
    )

    private val CONVERSATIONAL_STOPWORDS = setOf(
        "find", "me", "show", "search", "look", "get", "give", "list",
        "my", "all", "any", "the", "a", "an", "of", "for", "with", "about",
        "from", "in", "on", "inside", "during", "that", "which", "have", "has",
        "containing", "named", "called", "files", "file", "items", "item",
        "trouve", "cherche", "montre", "affiche", "moi", "mes", "mon", "ma",
        "les", "des", "de", "du", "la", "le", "un", "une", "avec", "sur", "pour",
        "dans", "fichier", "fichiers", "elements",
    )

    fun parseNaturalLanguage(
        freeText: String,
        knownFolderNames: Collection<String> = emptyList(),
        nowDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): NaturalLanguageResult {
        val raw = freeText.trim()
        if (raw.isEmpty()) return NaturalLanguageResult(cleanedTextQuery = "")

        // Normalize accents on working copy for pattern extraction while keeping readable chips.
        var working = normalizeForSearch(raw).replace(WHITESPACE_REGEX, " ").trim()
        val chips = ArrayList<String>()

        var inferredScope: SearchScopeFilter? = null
        val inferredExts = LinkedHashSet<String>()
        var folderFilter: String? = null
        var afterMs: Long? = null
        var beforeMs: Long? = null
        var minSize: Long? = null
        var maxSize: Long? = null
        var reqStarred = false
        var reqOffline = false
        var reqEncrypted = false
        var inferredSort: SearchSortOrder? = null

        fun setDateRange(startDate: LocalDate?, endDate: LocalDate?, chipLabel: String) {
            startDate?.let { d ->
                val ms = d.atStartOfDay(zoneId).toInstant().toEpochMilli()
                afterMs = maxOf(afterMs ?: ms, ms)
            }
            endDate?.let { d ->
                val ms = d.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                beforeMs = minOf(beforeMs ?: ms, ms)
            }
            chips.add(chipLabel)
        }

        // 1. Explicit numeric size expressions ("over 10mb", "under 500kb")
        SIZE_GT_REGEX.find(working)?.let { m ->
            parseNormalizedByteSize(m.groupValues[1], m.groupValues[2])?.let { bytes ->
                minSize = maxOf(minSize ?: bytes, bytes)
                chips.add("> ${formatCompactSize(bytes)}")
                working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
            }
        }
        SIZE_LT_REGEX.find(working)?.let { m ->
            parseNormalizedByteSize(m.groupValues[1], m.groupValues[2])?.let { bytes ->
                maxSize = minOf(maxSize ?: bytes, bytes)
                chips.add("< ${formatCompactSize(bytes)}")
                working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
            }
        }

        // 2. Relative multi-word date phrases
        RELATIVE_COUNT_REGEX.find(working)?.let { m ->
            val count = m.groupValues[1].toLongOrNull()?.coerceIn(1L, 3650L)
            val unit = m.groupValues[2].lowercase()
            if (count != null) {
                val start = when {
                    unit.startsWith("day") -> nowDate.minusDays(count)
                    unit.startsWith("week") -> nowDate.minusWeeks(count)
                    unit.startsWith("month") -> nowDate.minusMonths(count)
                    else -> nowDate.minusYears(count)
                }
                setDateRange(start, nowDate, "Last $count ${unit.trimEnd('s')}${if (count > 1) "s" else ""}")
                working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
            }
        }
        FR_RELATIVE_COUNT_REGEX.find(working)?.let { m ->
            val count = m.groupValues[1].toLongOrNull()?.coerceIn(1L, 3650L)
            val unit = m.groupValues[2].lowercase()
            if (count != null) {
                val start = when {
                    unit.startsWith("jour") -> nowDate.minusDays(count)
                    unit.startsWith("semaine") -> nowDate.minusWeeks(count)
                    unit == "mois" -> nowDate.minusMonths(count)
                    else -> nowDate.minusYears(count)
                }
                setDateRange(start, nowDate, "Last $count $unit")
                working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
            }
        }

        val relativePhrases = listOf(
            Regex("""\b(?:from|in|dans)?\s*(?:aujourd'hui|aujourdhui|today)\b""") to {
                setDateRange(nowDate, nowDate, "Today")
            },
            Regex("""\b(?:from|in|dans)?\s*(?:yesterday|hier)\b""") to {
                val y = nowDate.minusDays(1)
                setDateRange(y, y, "Yesterday")
            },
            Regex("""\b(?:from|in|of|this|cette)?\s*(?:this week|cette semaine)\b""") to {
                val start = nowDate.minusDays((nowDate.dayOfWeek.value - 1).toLong())
                setDateRange(start, nowDate, "This week")
            },
            Regex("""\b(?:from|in|of|la)?\s*(?:last week|past week|semaine derniere)\b""") to {
                val end = nowDate.minusDays(nowDate.dayOfWeek.value.toLong())
                val start = end.minusDays(6)
                setDateRange(start, end, "Last week")
            },
            Regex("""\b(?:from|in|of|ce)?\s*(?:this month|ce mois(?:-ci)?)\b""") to {
                setDateRange(nowDate.withDayOfMonth(1), nowDate, "This month")
            },
            Regex("""\b(?:from|in|of|le)?\s*(?:last month|past month|mois dernier)\b""") to {
                val ym = YearMonth.from(nowDate.minusMonths(1))
                setDateRange(ym.atDay(1), ym.atEndOfMonth(), "Last month")
            },
            Regex("""\b(?:from|in|of|cette)?\s*(?:this year|cette annee)\b""") to {
                setDateRange(LocalDate.of(nowDate.year, 1, 1), nowDate, "This year")
            },
            Regex("""\b(?:from|in|of|l')?\s*(?:last year|past year|annee derniere)\b""") to {
                val y = nowDate.year - 1
                setDateRange(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "Last year ($y)")
            },
        )
        for ((regex, applyRange) in relativePhrases) {
            regex.find(working)?.let { m ->
                applyRange()
                working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
            }
        }

        // 3. Explicit Month + Year or Preposition + Month / Year
        MONTH_YEAR_REGEX.find(working)?.let { m ->
            val month = MONTH_MAP[m.groupValues[1].lowercase()]
            val year = m.groupValues[2].toIntOrNull()
            if (month != null && year != null) {
                val ym = YearMonth.of(year, month)
                setDateRange(ym.atDay(1), ym.atEndOfMonth(), "${m.groupValues[1].replaceFirstChar { it.uppercase() }} $year")
                working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
            }
        }
        PREPOSITION_MONTH_REGEX.find(working)?.let { m ->
            val month = MONTH_MAP[m.groupValues[1].lowercase()]
            if (month != null) {
                val year = if (month.value > nowDate.monthValue) nowDate.year - 1 else nowDate.year
                val ym = YearMonth.of(year, month)
                setDateRange(ym.atDay(1), ym.atEndOfMonth(), "${m.groupValues[1].replaceFirstChar { it.uppercase() }} $year")
                working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
            }
        }
        PREPOSITION_YEAR_REGEX.find(working)?.let { m ->
            val prep = m.groupValues[1].lowercase()
            val year = m.groupValues[2].toIntOrNull()
            if (year != null) {
                when (prep) {
                    "before", "avant" -> setDateRange(null, LocalDate.of(year - 1, 12, 31), "Before $year")
                    "since", "after", "depuis", "apres" -> setDateRange(LocalDate.of(year, 1, 1), null, "Since $year")
                    else -> setDateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), "Year $year")
                }
                working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
            }
        }

        // 4. Multi-word scope phrases
        val inZipPhrase = Regex("""\b(?:inside|in|within|dans)\s+(?:a\s+|an\s+|les?\s+|des?\s+)?zips?\b|\bin\s+archives?\b""")
        inZipPhrase.find(working)?.let { m ->
            inferredScope = SearchScopeFilter.IN_ZIPS
            chips.add("In ZIPs")
            working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
        }

        // 5. Known folder phrases ("in Work", "from Camera Backup") ONLY if matching a real folder
        if (knownFolderNames.isNotEmpty()) {
            FOLDER_PHRASE_REGEX.find(working)?.let { m ->
                val candidatePhrase = m.groupValues[1].trim()
                val matched = findMatchingFolder(candidatePhrase, knownFolderNames)
                if (matched != null) {
                    folderFilter = matched
                    chips.add("Folder: $matched")
                    working = working.removeRange(m.range).replace(WHITESPACE_REGEX, " ").trim()
                }
            }
        }

        // 6. Token-level intent extraction
        val rawTokens = working.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val keptTokens = ArrayList<String>(rawTokens.size)
        var qualitativeLarge = false
        var qualitativeSmall = false

        for (tok in rawTokens) {
            when (tok) {
                // Scopes / media types
                "photo", "photos", "picture", "pictures", "pic", "pics", "image", "images", "img", "wallpaper", "wallpapers" -> {
                    if (inferredScope == null) {
                        inferredScope = SearchScopeFilter.IMAGES
                        chips.add("Photos")
                    }
                }
                "screenshot", "screenshots", "capture", "captures" -> {
                    if (inferredScope == null) {
                        inferredScope = SearchScopeFilter.IMAGES
                        chips.add("Photos")
                    }
                    keptTokens.add(tok)
                }
                "video", "videos", "movie", "movies", "clip", "clips", "film", "films" -> {
                    if (inferredScope == null) {
                        inferredScope = SearchScopeFilter.VIDEOS
                        chips.add("Videos")
                    }
                }
                "audio", "music", "musique", "song", "songs", "chanson", "chansons", "track", "tracks", "podcast", "podcasts" -> {
                    if (inferredScope == null) {
                        inferredScope = SearchScopeFilter.AUDIO
                        chips.add("Audio")
                    }
                }
                "pdf", "pdfs" -> {
                    inferredExts.add("pdf")
                    chips.add("PDF")
                }
                "word", "docx" -> {
                    inferredExts.addAll(listOf("doc", "docx"))
                    chips.add("Word")
                }
                "excel", "spreadsheet", "spreadsheets", "csv" -> {
                    inferredExts.addAll(listOf("xls", "xlsx", "csv"))
                    chips.add("Spreadsheets")
                }
                "slides", "powerpoint", "pptx" -> {
                    inferredExts.addAll(listOf("ppt", "pptx"))
                    chips.add("Slides")
                }
                "document", "documents", "doc", "docs" -> {
                    if (inferredScope == null) {
                        inferredScope = SearchScopeFilter.DOCS
                        chips.add("Docs")
                    }
                }
                "zip", "zips", "archive", "archives", "compressed" -> {
                    inferredExts.addAll(listOf("zip", "rar", "7z", "tar", "gz"))
                    chips.add("Archives")
                }

                // Status flags
                "starred", "favorite", "favorites", "favourite", "favourites", "fav", "favs", "favori", "favoris" -> {
                    if (!reqStarred) {
                        reqStarred = true
                        chips.add("Starred")
                    }
                }
                "offline", "pinned" -> {
                    if (!reqOffline) {
                        reqOffline = true
                        chips.add("Offline")
                    }
                }
                "encrypted", "vault", "passvault", "private", "chiffre", "chiffres", "chiffree", "chiffrees", "crypte", "cryptes", "coffre" -> {
                    if (!reqEncrypted) {
                        reqEncrypted = true
                        chips.add("Encrypted")
                    }
                }

                // Qualitative size
                "big", "large", "huge", "heavy", "gros", "grosse", "lourd", "lourde" -> qualitativeLarge = true
                "small", "tiny", "light", "petit", "petite" -> qualitativeSmall = true

                // Sort intent
                "newest", "latest", "recent", "recents", "recente", "recentes" -> {
                    if (inferredSort == null) {
                        inferredSort = SearchSortOrder.NEWEST
                        chips.add("Newest")
                    }
                }
                "largest", "biggest", "heaviest" -> {
                    if (inferredSort == null) {
                        inferredSort = SearchSortOrder.LARGEST
                        chips.add("Largest")
                    }
                }

                else -> keptTokens.add(tok)
            }
        }

        if (qualitativeLarge && minSize == null) {
            val bytes = if (inferredScope == SearchScopeFilter.IMAGES ||
                inferredScope == SearchScopeFilter.DOCS ||
                "pdf" in inferredExts
            ) {
                2L * 1024L * 1024L
            } else {
                10L * 1024L * 1024L
            }
            minSize = bytes
            chips.add("> ${formatCompactSize(bytes)}")
        }
        if (qualitativeSmall && maxSize == null) {
            val bytes = 1024L * 1024L
            maxSize = bytes
            chips.add("< ${formatCompactSize(bytes)}")
        }

        val hasStructured = inferredScope != null ||
            inferredExts.isNotEmpty() ||
            folderFilter != null ||
            afterMs != null ||
            beforeMs != null ||
            minSize != null ||
            maxSize != null ||
            reqStarred ||
            reqOffline ||
            reqEncrypted ||
            inferredSort != null

        val meaningful = keptTokens.filter { it !in CONVERSATIONAL_STOPWORDS }
        val finalTokens = when {
            meaningful.isNotEmpty() -> meaningful
            hasStructured -> emptyList()
            else -> keptTokens
        }

        return NaturalLanguageResult(
            cleanedTextQuery = finalTokens.joinToString(" "),
            inferredScope = inferredScope,
            inferredExtensions = inferredExts,
            inferredFolderFilter = folderFilter,
            inferredAfterMs = afterMs,
            inferredBeforeMs = beforeMs,
            inferredMinSizeBytes = minSize,
            inferredMaxSizeBytes = maxSize,
            requireStarred = reqStarred,
            requireOffline = reqOffline,
            requireEncrypted = reqEncrypted,
            inferredSort = inferredSort,
            interpretedChips = chips.distinct(),
        )
    }

    private fun findMatchingFolder(
        phrase: String,
        knownFolderNames: Collection<String>,
    ): String? {
        val normPhrase = normalizeForSearch(phrase)
        if (normPhrase.length < 2) return null
        val exact = knownFolderNames.firstOrNull { normalizeForSearch(it) == normPhrase }
        if (exact != null) return exact
        // Try full phrase match or first word match (e.g., "in Work last month" after date removal -> "Work")
        val firstWord = normPhrase.substringBefore(' ').trim()
        knownFolderNames.firstOrNull { normalizeForSearch(it) == firstWord }?.let { return it }
        return knownFolderNames.firstOrNull { fn ->
            val normFn = normalizeForSearch(fn)
            normFn.contains(normPhrase) || (firstWord.length >= 3 && normFn.contains(firstWord))
        }
    }

    private fun parseNormalizedByteSize(numStr: String, unitRaw: String): Long? {
        val num = numStr.toDoubleOrNull() ?: return null
        if (num < 0.0) return null
        val mult = when (unitRaw.lowercase()) {
            "", "b" -> 1.0
            "k", "kb", "ko" -> 1024.0
            "m", "mb", "mo" -> 1024.0 * 1024.0
            "g", "gb", "go" -> 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return (num * mult).toLong()
    }

    private fun formatCompactSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }
}

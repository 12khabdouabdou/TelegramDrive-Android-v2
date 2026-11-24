package com.tgdrive.core.zip

/**
 * Shared wildcard/directory exclusion matcher used by both folder-as-ZIP and
 * folder-as-normal uploads (`*.tmp, .thumbnails/, cache/, Sub/temp*`).
 */
object ZipExcludeMatcher {

    fun parse(patternsStr: String): List<String> {
        if (patternsStr.isBlank()) return emptyList()
        return patternsStr.split(",")
            .map { it.trim().replace('\\', '/') }
            .filter { it.isNotBlank() }
    }

    fun matchesDirectory(dirName: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty() || dirName.isBlank()) return false
        val lowerDir = dirName.lowercase()
        return patterns.any { raw ->
            val p = raw.trim().lowercase()
            when {
                p.isEmpty() -> false
                p.endsWith("/") -> {
                    val clean = p.trimEnd('/')
                    clean.isNotEmpty() && '/' !in clean && matchesGlob(lowerDir, clean)
                }
                '/' in p -> false
                else -> matchesGlob(lowerDir, p)
            }
        }
    }

    fun matchesPath(relativePath: String, fileName: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false
        val lowerName = fileName.lowercase()
        val directMatch = patterns.any { raw ->
            val p = raw.trim().lowercase()
            p.isNotEmpty() && !p.endsWith("/") && '/' !in p && matchesGlob(lowerName, p)
        }
        if (directMatch) return true

        val cleanRel = relativePath.trim('/').replace('\\', '/')
        if (cleanRel.isNotEmpty()) {
            val segments = cleanRel.split('/').filter { it.isNotEmpty() }
            if (segments.any { matchesDirectory(it, patterns) }) return true
        }

        val fullRel = if (cleanRel.isEmpty()) fileName else "$cleanRel/$fileName"
        val lowerFull = fullRel.lowercase()
        return patterns.any { raw ->
            val p = raw.trim().lowercase()
            if ('/' !in p) return@any false
            val trimmedDir = p.trimEnd('/')
            if (p.endsWith("/")) {
                trimmedDir.isNotEmpty() &&
                    (lowerFull.startsWith("$trimmedDir/") || lowerFull.contains("/$trimmedDir/"))
            } else {
                matchesGlob(lowerFull, p)
            }
        }
    }

    private fun matchesGlob(candidateLower: String, patternLower: String): Boolean {
        if ('*' !in patternLower) return candidateLower == patternLower
        val regexPattern = buildString {
            append('^')
            val parts = patternLower.split('*')
            parts.forEachIndexed { idx, part ->
                if (part.isNotEmpty()) append(Regex.escape(part))
                if (idx < parts.lastIndex) append(".*")
            }
            append('$')
        }
        return Regex(regexPattern).matches(candidateLower)
    }
}

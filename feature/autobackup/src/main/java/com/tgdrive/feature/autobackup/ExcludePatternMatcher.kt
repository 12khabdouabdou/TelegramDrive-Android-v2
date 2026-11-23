package com.tgdrive.feature.autobackup

/**
 * Matches filenames, folder names, and relative paths against exclusion patterns.
 *
 * Patterns support:
 *   - `*.ext` — matches any file ending in `.ext`
 *   - `name` — exact filename or folder name match
 *   - `*name*` — contains `name`
 *   - `name*` — starts with `name`
 *   - `.thumbnails/` — matches any directory named `.thumbnails` (pruned during scan)
 *   - `Sent/` or `Sub/*` — matches directories or relative paths containing `/`
 *
 * Multiple patterns are separated by commas.
 */
object ExcludePatternMatcher {

    /**
     * Parse a comma-separated pattern string into a list of patterns.
     * Empty/blank patterns are filtered out.
     */
    fun parse(patternsStr: String): List<String> {
        if (patternsStr.isBlank()) return emptyList()
        return patternsStr.split(",")
            .map { it.trim().replace('\\', '/') }
            .filter { it.isNotBlank() }
    }

    /**
     * Check if [fileName] matches any of the [patterns].
     *
     * Returns true if the file should be EXCLUDED (skipped).
     */
    fun matches(fileName: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false
        val lowerName = fileName.lowercase()
        return patterns.any { raw ->
            val p = raw.trim().lowercase()
            if (p.isEmpty() || p.endsWith("/") || '/' in p) {
                false
            } else {
                matchesGlob(lowerName, p)
            }
        }
    }

    /**
     * Check if a directory segment [dirName] should be pruned before recursing.
     */
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

    /**
     * Check a file's [relativePath] (subpath inside the tree root) and [fileName]
     * against all [patterns], including directory patterns and path patterns (`a/b*`).
     */
    fun matchesPath(relativePath: String, fileName: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false
        if (matches(fileName, patterns)) return true

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
                trimmedDir.isNotEmpty() && (lowerFull.startsWith("$trimmedDir/") || lowerFull.contains("/$trimmedDir/"))
            } else {
                matchesGlob(lowerFull, p)
            }
        }
    }

    /** Convenience: parse + match in one call. */
    fun shouldExclude(fileName: String, patternsStr: String): Boolean {
        val patterns = parse(patternsStr)
        return matches(fileName, patterns)
    }

    private fun matchesGlob(candidateLower: String, patternLower: String): Boolean = when {
        patternLower.startsWith("*") && patternLower.endsWith("*") && patternLower.length >= 2 ->
            candidateLower.contains(patternLower.drop(1).dropLast(1))
        patternLower.startsWith("*.") ->
            candidateLower.endsWith(patternLower.drop(1))
        patternLower.startsWith("*") ->
            candidateLower.endsWith(patternLower.drop(1))
        patternLower.endsWith("*") ->
            candidateLower.startsWith(patternLower.dropLast(1))
        else -> candidateLower == patternLower
    }
}

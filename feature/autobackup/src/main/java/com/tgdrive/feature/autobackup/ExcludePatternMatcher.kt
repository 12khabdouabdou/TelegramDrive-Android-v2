package com.tgdrive.feature.autobackup

/**
 * Matches filenames against exclusion patterns.
 *
 * Patterns support:
 *   - `*.ext` — matches any file ending in `.ext`
 *   - `name` — exact filename match
 *   - `*name*` — contains `name`
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
            .map { it.trim() }
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
        return patterns.any { pattern ->
            val p = pattern.lowercase()
            when {
                p.startsWith("*") && p.endsWith("*") -> {
                    // *name* → contains
                    lowerName.contains(p.drop(1).dropLast(1))
                }
                p.startsWith("*.") -> {
                    // *.ext → ends with .ext
                    lowerName.endsWith(p.drop(1)) // drop the *, keep the .
                }
                p.endsWith("*") -> {
                    // name* → starts with
                    lowerName.startsWith(p.dropLast(1))
                }
                else -> {
                    // exact match
                    lowerName == p
                }
            }
        }
    }

    /** Convenience: parse + match in one call. */
    fun shouldExclude(fileName: String, patternsStr: String): Boolean {
        val patterns = parse(patternsStr)
        return matches(fileName, patterns)
    }
}

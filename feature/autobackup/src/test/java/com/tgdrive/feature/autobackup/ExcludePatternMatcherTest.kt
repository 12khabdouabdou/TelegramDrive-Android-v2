package com.tgdrive.feature.autobackup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ExcludePatternMatcherTest {

    @Test
    fun `matches extensions exact names prefix and contains globs`() {
        val patterns = ExcludePatternMatcher.parse("*.tmp, Thumbs.db, .DS_Store, *cache*, draft*")
        assertThat(ExcludePatternMatcher.matches("notes.TMP", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matches("thumbs.db", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matches(".DS_Store", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matches("my_cache_file.bin", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matches("Draft_01.jpg", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matches("IMG_2026.jpg", patterns)).isFalse()
    }

    @Test
    fun `matches directories and relative nested paths`() {
        val patterns = ExcludePatternMatcher.parse(".thumbnails/, cache, WhatsApp/Sent/, Temp/*.bak")

        assertThat(ExcludePatternMatcher.matchesDirectory(".thumbnails", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matchesDirectory("cache", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matchesDirectory("Camera", patterns)).isFalse()

        // Trailing slash directory pattern does not falsely exclude a same-named file directly at root
        // when checked purely via file-only matches(), while matchesPath catches files inside directories.
        assertThat(ExcludePatternMatcher.matchesPath("DCIM/.thumbnails", "thumb_1.jpg", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matchesPath("WhatsApp/Sent", "VID_1.mp4", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matchesPath("Temp", "copy.bak", patterns)).isTrue()
        assertThat(ExcludePatternMatcher.matchesPath("DCIM/Camera", "IMG_1.jpg", patterns)).isFalse()
    }
}

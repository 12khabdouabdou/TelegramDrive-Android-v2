package com.tgdrive.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ExtensionsTest {

    @Test
    fun `toHumanSize formats bytes correctly`() {
        assertThat(0L.toHumanSize()).isEqualTo("0 B")
        assertThat(1L.toHumanSize()).isEqualTo("1 B")
        assertThat(1023L.toHumanSize()).isEqualTo("1023 B")
        assertThat(1024L.toHumanSize()).isEqualTo("1 KB")
        assertThat((1024L * 1024).toHumanSize()).isEqualTo("1 MB")
        assertThat((1024L * 1024 * 1024).toHumanSize()).isEqualTo("1 GB")
    }

    @Test
    fun `toHumanSize handles fractional values`() {
        assertThat(1536L.toHumanSize()).isEqualTo("1.5 KB")
        assertThat((1024L * 1024 * 3 + 1024 * 512).toHumanSize()).isEqualTo("3.5 MB")
    }

    @Test
    fun `isE164 validates phone numbers`() {
        assertThat("+15551234567".isE164()).isTrue()
        assertThat("15551234567".isE164()).isTrue()
        assertThat("+442071838750".isE164()).isTrue()
        assertThat("12345".isE164()).isFalse()    // too short
        assertThat("+0123".isE164()).isFalse()    // leading 0
        assertThat("phone123".isE164()).isFalse()
        assertThat("".isE164()).isFalse()
    }

    @Test
    fun `ellipsize truncates long strings`() {
        assertThat("hello".ellipsize(10)).isEqualTo("hello")
        assertThat("hello world".ellipsize(8)).isEqualTo("hello w…")
        assertThat("hello world".ellipsize(5)).isEqualTo("hell…")
    }

    @Test
    fun `tryNull returns null on exception`() {
        val result: Int? = tryNull { throw RuntimeException("boom") }
        assertThat(result).isNull()
    }

    @Test
    fun `tryNull returns value on success`() {
        val result: Int? = tryNull { 42 }
        assertThat(result).isEqualTo(42)
    }
}

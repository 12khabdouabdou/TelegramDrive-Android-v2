package com.tgdrive.feature.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TextViewerHelpersTest {

    @Test
    fun `decodeUtf8Safe strips incomplete trailing multi-byte sequence when truncated`() {
        val raw = "Hello €🌍".toByteArray(Charsets.UTF_8)
        // Drop the last byte of the 4-byte earth emoji so trailing UTF-8 sequence is incomplete.
        val cut = raw.copyOf(raw.size - 1)
        val decoded = decodeUtf8Safe(cut, truncated = true)
        assertFalse(decoded.contains('\uFFFD'))
        assertEquals("Hello €", decoded)
    }

    @Test
    fun `decodeUtf8Safe preserves complete multi-byte character at truncation boundary`() {
        val raw = "Hi 🌍".toByteArray(Charsets.UTF_8)
        val decoded = decodeUtf8Safe(raw, truncated = true)
        assertEquals("Hi 🌍", decoded)
    }

    @Test
    fun `decodeUtf8Safe leaves non-truncated text untouched`() {
        val raw = "Plain text\nline 2".toByteArray(Charsets.UTF_8)
        assertEquals("Plain text\nline 2", decodeUtf8Safe(raw, truncated = false))
    }

    @Test
    fun `prettyPrintJson strips leading BOM and formats valid json`() {
        val withBom = "\uFEFF{\"name\":\"eni\",\"count\":2}"
        val pretty = prettyPrintJson(withBom)
        assertEquals("{\n  \"name\": \"eni\",\n  \"count\": 2\n}", pretty)
    }

    @Test
    fun `prettyPrintJson returns invalid input unchanged`() {
        val bad = "{broken json"
        assertEquals(bad, prettyPrintJson(bad))
    }
}

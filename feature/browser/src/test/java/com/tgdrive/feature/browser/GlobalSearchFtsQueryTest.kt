package com.tgdrive.feature.browser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlobalSearchFtsQueryTest {

    @Test
    fun `buildFtsPrefixQuery formats multi-word and unicode queries with prefix wildcard`() {
        assertThat(buildFtsPrefixQuery("tax 2026")).isEqualTo("tax* 2026*")
        assertThat(buildFtsPrefixQuery("résumé photo_01")).isEqualTo("résumé* photo* 01*")
    }

    @Test
    fun `buildFtsPrefixQuery strips special FTS4 syntax characters safely`() {
        assertThat(buildFtsPrefixQuery("""hello "world" -foo:bar (baz)"""))
            .isEqualTo("hello* world* foo* bar* baz*")
    }

    @Test
    fun `buildFtsPrefixQuery returns null for blank or symbol-only strings`() {
        assertThat(buildFtsPrefixQuery("")).isNull()
        assertThat(buildFtsPrefixQuery("   ")).isNull()
        assertThat(buildFtsPrefixQuery("*** --- \"\"\"")).isNull()
    }
}

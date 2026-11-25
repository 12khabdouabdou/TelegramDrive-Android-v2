package com.tgdrive.core.telegram

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TdLibFloodWaitTest {

    @Test
    fun `parseFloodWaitSeconds parses FLOOD_WAIT suffix and 429 retry after`() {
        assertThat(TdLibTelegramClient.parseFloodWaitSeconds(420, "FLOOD_WAIT_15")).isEqualTo(15)
        assertThat(TdLibTelegramClient.parseFloodWaitSeconds(429, "Too Many Requests: retry after 9")).isEqualTo(9)
        assertThat(TdLibTelegramClient.parseFloodWaitSeconds(420, "TOO_MANY_REQUESTS: RETRY_AFTER=22")).isEqualTo(22)
    }

    @Test
    fun `parseFloodWaitSeconds returns null for non-positive or non-rate-limit errors`() {
        assertThat(TdLibTelegramClient.parseFloodWaitSeconds(420, "FLOOD_WAIT_0")).isNull()
        assertThat(TdLibTelegramClient.parseFloodWaitSeconds(400, "Bad Request: retry after 5")).isNull()
        assertThat(TdLibTelegramClient.parseFloodWaitSeconds(404, "Not Found")).isNull()
        assertThat(TdLibTelegramClient.parseFloodWaitSeconds(429, "")).isNull()
        assertThat(TdLibTelegramClient.parseFloodWaitSeconds(429, null)).isNull()
    }
}

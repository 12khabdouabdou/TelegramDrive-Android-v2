package com.tgdrive.feature.autobackup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FreeUpSpacePolicyTest {

    @Test
    fun `isVerifiedInCloud requires valid positive messageId and rejects trashed items`() {
        assertFalse(
            FreeUpSpacePolicy.isVerifiedInCloud(
                folderChannelId = 100L,
                telegramMessageId = 0L,
                cachedKeys = setOf(100L to 0L),
                cachedMsgIdsFallback = setOf(0L),
                trashedKeys = emptySet(),
                trashedMsgIdsFallback = emptySet(),
            ),
        )

        assertTrue(
            FreeUpSpacePolicy.isVerifiedInCloud(
                folderChannelId = 100L,
                telegramMessageId = 42L,
                cachedKeys = setOf(100L to 42L),
                cachedMsgIdsFallback = setOf(42L),
                trashedKeys = emptySet(),
                trashedMsgIdsFallback = emptySet(),
            ),
        )

        assertFalse(
            FreeUpSpacePolicy.isVerifiedInCloud(
                folderChannelId = 100L,
                telegramMessageId = 42L,
                cachedKeys = setOf(100L to 42L),
                cachedMsgIdsFallback = setOf(42L),
                trashedKeys = setOf(100L to 42L),
                trashedMsgIdsFallback = emptySet(),
            ),
        )
    }

    @Test
    fun `isVerifiedInCloud enforces composite channelId match and avoids cross-channel collisions`() {
        assertFalse(
            FreeUpSpacePolicy.isVerifiedInCloud(
                folderChannelId = 200L,
                telegramMessageId = 55L,
                cachedKeys = setOf(100L to 55L),
                cachedMsgIdsFallback = setOf(55L),
                trashedKeys = emptySet(),
                trashedMsgIdsFallback = emptySet(),
            ),
        )

        assertFalse(
            FreeUpSpacePolicy.isVerifiedInCloud(
                folderChannelId = null,
                telegramMessageId = 55L,
                cachedKeys = emptySet(),
                cachedMsgIdsFallback = emptySet(),
                trashedKeys = emptySet(),
                trashedMsgIdsFallback = emptySet(),
            ),
        )

        assertTrue(
            FreeUpSpacePolicy.isVerifiedInCloud(
                folderChannelId = null,
                telegramMessageId = 55L,
                cachedKeys = setOf(100L to 55L),
                cachedMsgIdsFallback = setOf(55L),
                trashedKeys = emptySet(),
                trashedMsgIdsFallback = emptySet(),
            ),
        )

        assertFalse(
            FreeUpSpacePolicy.isVerifiedInCloud(
                folderChannelId = null,
                telegramMessageId = 55L,
                cachedKeys = setOf(100L to 55L),
                cachedMsgIdsFallback = setOf(55L),
                trashedKeys = emptySet(),
                trashedMsgIdsFallback = setOf(55L),
            ),
        )
    }

    @Test
    fun `shouldDeleteAfterShaCheck rejects missing or mismatched SHA when verification enabled`() {
        assertTrue(
            FreeUpSpacePolicy.shouldDeleteAfterShaCheck(
                verifyEnabled = true,
                expectedSha = "abc123",
                actualSha = "ABC123",
            ),
        )
        assertFalse(
            FreeUpSpacePolicy.shouldDeleteAfterShaCheck(
                verifyEnabled = true,
                expectedSha = "abc123",
                actualSha = "def456",
            ),
        )
        assertFalse(
            FreeUpSpacePolicy.shouldDeleteAfterShaCheck(
                verifyEnabled = true,
                expectedSha = "abc123",
                actualSha = null,
            ),
        )
        assertFalse(
            FreeUpSpacePolicy.shouldDeleteAfterShaCheck(
                verifyEnabled = true,
                expectedSha = null,
                actualSha = "abc123",
            ),
        )
        assertFalse(
            FreeUpSpacePolicy.shouldDeleteAfterShaCheck(
                verifyEnabled = true,
                expectedSha = " ",
                actualSha = " ",
            ),
        )
        assertTrue(
            FreeUpSpacePolicy.shouldDeleteAfterShaCheck(
                verifyEnabled = false,
                expectedSha = "abc123",
                actualSha = "mismatch",
            ),
        )
    }
}

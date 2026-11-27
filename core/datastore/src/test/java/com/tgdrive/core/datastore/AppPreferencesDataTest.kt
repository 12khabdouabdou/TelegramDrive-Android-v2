package com.tgdrive.core.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AppPreferencesDataTest {

    @Test
    fun `default values match spec defaults`() {
        val d = AppPreferencesData()
        assertThat(d.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(d.biometricLockEnabled).isFalse()
        assertThat(d.fileViewMode).isEqualTo(FileViewMode.GRID)
        assertThat(d.cacheLimitBytes).isEqualTo(500L * 1024 * 1024)
        assertThat(d.thumbnailCacheLimitBytes).isEqualTo(50L * 1024 * 1024)
        assertThat(d.backupEnabled).isFalse()
        assertThat(d.backupFolderUris).isEmpty()
        assertThat(d.backupTargetFolderId).isEqualTo(-1L)
        assertThat(d.backupSchedule).isEqualTo(BackupSchedule.DAILY)
        assertThat(d.backupWifiOnly).isTrue()
        assertThat(d.backupChargingOnly).isFalse()
        assertThat(d.backupStrictMode).isFalse()
        assertThat(d.backupDeleteAfterUpload).isFalse()
        assertThat(d.backupPaused).isFalse()
        assertThat(d.decryptedTempTtlMinutes).isEqualTo(30)
        assertThat(d.manualUploadWifiOnly).isFalse()
        assertThat(d.photosColumns).isEqualTo(3)
        assertThat(d.biometricTimeoutSeconds).isEqualTo(60)
        assertThat(d.blockScreenshotsEverywhere).isFalse()
        assertThat(d.downloadConcurrency).isEqualTo(2)
        assertThat(d.downloadWifiOnly).isFalse()
        assertThat(d.autoSaveDownloadsToPublic).isFalse()
        assertThat(d.pinnedFolderChatIds).isEmpty()
        assertThat(d.showEncryptedPreviewsWhenUnlocked).isTrue()
    }

    @Test
    fun `pinned folder helpers toggle cleanly`() {
        val prefs = AppPreferencesData()
            .withToggledPinnedFolder(101L)
            .withToggledPinnedFolder(202L)
        assertThat(prefs.isFolderPinned(101L)).isTrue()
        assertThat(prefs.isFolderPinned(202L)).isTrue()
        assertThat(prefs.isFolderPinned(303L)).isFalse()

        val unpinned = prefs.withToggledPinnedFolder(101L)
        assertThat(unpinned.isFolderPinned(101L)).isFalse()
        assertThat(unpinned.isFolderPinned(202L)).isTrue()
    }

    @Test
    fun `recent search helpers add deduplicate remove and clear`() {
        val prefs = AppPreferencesData()
            .withRecentSearch("tax 2025")
            .withRecentSearch("receipts")
            .withRecentSearch("TAX 2025")
        assertThat(prefs.recentSearches()).containsExactly("TAX 2025", "receipts").inOrder()

        val afterRemove = prefs.withoutRecentSearch("tax 2025")
        assertThat(afterRemove.recentSearches()).containsExactly("receipts")
        assertThat(afterRemove.clearRecentSearches().recentSearches()).isEmpty()
    }

    @Test
    fun `backup schedule enum has 4 entries`() {
        assertThat(BackupSchedule.entries).hasSize(4)
        assertThat(BackupSchedule.entries).containsExactly(
            BackupSchedule.ON_CHANGE, BackupSchedule.DAILY, BackupSchedule.WEEKLY, BackupSchedule.MANUAL,
        )
    }

    @Test
    fun `conflict strategy enum has 2 entries`() {
        assertThat(ConflictStrategy.entries).hasSize(2)
    }

    @Test
    fun `theme mode enum has 3 entries`() {
        assertThat(ThemeMode.entries).hasSize(3)
    }

    @Test
    fun `file view mode enum has 2 entries`() {
        assertThat(FileViewMode.entries).hasSize(2)
    }

    @Test
    fun `global backupEncryptAll overrides per folder encryption set`() {
        val defaultPrefs = AppPreferencesData(backupEncryptedFolderUris = setOf("content://folder/a"))
        assertThat(defaultPrefs.isFolderEncrypted("content://folder/a")).isTrue()
        assertThat(defaultPrefs.isFolderEncrypted("content://folder/b")).isFalse()

        val globalPrefs = defaultPrefs.copy(backupEncryptAll = true)
        assertThat(globalPrefs.isFolderEncrypted("content://folder/a")).isTrue()
        assertThat(globalPrefs.isFolderEncrypted("content://folder/b")).isTrue()
    }

    @Test
    fun `folder target JSON roundtrips special characters removal and malformed fallback`() {
        val trickyUri = "content://com.android.externalstorage/tree/primary%3AMy \"Docs\"\\2026"
        val secondUri = "content://folder/photos"

        val updated = AppPreferencesData(backupTargetFolderId = -1L)
            .withFolderTarget(trickyUri, -1001234567890L)
            .withFolderTarget(secondUri, -1009999999999L)

        assertThat(updated.parseFolderTargets()).containsExactly(
            trickyUri,
            -1001234567890L,
            secondUri,
            -1009999999999L,
        )
        assertThat(updated.customTargetFor(trickyUri)).isEqualTo(-1001234567890L)
        assertThat(updated.targetFolderFor("content://folder/unmapped")).isEqualTo(-1L)

        val removed = updated.withFolderTarget(trickyUri, null)
        assertThat(removed.customTargetFor(trickyUri)).isNull()
        assertThat(removed.parseFolderTargets()).containsExactly(secondUri, -1009999999999L)

        val malformed = AppPreferencesData(backupFolderTargetsJson = "{not valid json")
        assertThat(malformed.parseFolderTargets()).isEmpty()
    }
}

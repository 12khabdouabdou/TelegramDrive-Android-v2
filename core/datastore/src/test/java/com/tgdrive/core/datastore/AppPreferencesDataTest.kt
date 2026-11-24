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
}

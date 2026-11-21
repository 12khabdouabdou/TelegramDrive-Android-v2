package com.tgdrive.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Type-safe app preferences backed by Preferences DataStore.
 *
 * Note: the spec calls for Proto DataStore. We chose Preferences DataStore to
 * avoid pulling in the protobuf-gradle-plugin (which complicates the build).
 * The API surface is identical to what a Proto-backed impl would expose, so
 * swapping later is a drop-in change.
 *
 * Per spec §F-08 + §F-05 (backup config lives here).
 */

// Single DataStore instance for the process.
private val Context.appStore by preferencesDataStore("tgdrive_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class BackupSchedule { ON_CHANGE, DAILY, WEEKLY, MANUAL }
enum class ConflictStrategy { SKIP, OVERWRITE_RENAME }
enum class FileViewMode { GRID, LIST }
enum class FileFilter { ALL, IMAGES, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES }
enum class FileSort { NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC }

data class AppPreferencesData(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val biometricLockEnabled: Boolean = false,
    val biometricLastBgTime: Long = 0L,
    val fileViewMode: FileViewMode = FileViewMode.GRID,
    val cacheLimitBytes: Long = 500L * 1024 * 1024,
    val thumbnailCacheLimitBytes: Long = 50L * 1024 * 1024,
    val fileFilter: FileFilter = FileFilter.ALL,
    val fileSort: FileSort = FileSort.DATE_DESC,

    // AutoBackup config
    val backupEnabled: Boolean = false,
    val backupFolderUris: Set<String> = emptySet(),
    val backupTargetFolderId: Long = -1L,        // -1 = Saved Messages
    val backupSchedule: BackupSchedule = BackupSchedule.DAILY,
    val backupWifiOnly: Boolean = true,
    val backupChargingOnly: Boolean = false,
    val backupStrictMode: Boolean = false,
    val backupDeleteAfterUpload: Boolean = false,
    val backupZipFolders: Boolean = false,
    val backupZipIncremental: Boolean = false,
    val backupExcludePatterns: String = "",  // comma-separated, e.g. "*.tmp,Thumbs.db,.DS_Store"
    val backupFolderTargetsJson: String = "{}",
    val backupPaused: Boolean = false,
    val onboardingCompleted: Boolean = false,

    // Scheduled upload — upload files from a specific folder at a set time
    val scheduledUploadEnabled: Boolean = false,
    val scheduledUploadFolderUri: String = "",
    val scheduledUploadTargetFolderId: Long = -1L,
    val scheduledUploadHour: Int = 3, // 3 AM by default
    val scheduledUploadMinute: Int = 0,
) {
    fun targetFolderFor(folderUri: String): Long =
        parseFolderTargets()[folderUri] ?: backupTargetFolderId

    fun customTargetFor(folderUri: String): Long? = parseFolderTargets()[folderUri]

    fun parseFolderTargets(): Map<String, Long> = decodeLongMap(backupFolderTargetsJson)

    fun withFolderTarget(folderUri: String, targetChatId: Long?): AppPreferencesData {
        val next = parseFolderTargets().toMutableMap()
        if (targetChatId == null) next.remove(folderUri) else next[folderUri] = targetChatId
        return copy(backupFolderTargetsJson = encodeLongMap(next))
    }

    companion object {
        private fun encodeLongMap(map: Map<String, Long>): String {
            if (map.isEmpty()) return "{}"
            return buildString {
                append('{')
                map.entries.forEachIndexed { idx, (k, v) ->
                    if (idx > 0) append(',')
                    append('"')
                    append(k.replace("\\", "\\\\").replace("\"", "\\\""))
                    append("\":")
                    append(v)
                }
                append('}')
            }
        }

        private fun decodeLongMap(json: String): Map<String, Long> {
            val trimmed = json.trim()
            if (trimmed.length < 2 || !trimmed.startsWith('{') || !trimmed.endsWith('}')) return emptyMap()
            val body = trimmed.substring(1, trimmed.length - 1).trim()
            if (body.isEmpty()) return emptyMap()
            val result = LinkedHashMap<String, Long>()
            var i = 0
            while (i < body.length) {
                while (i < body.length && (body[i] == ',' || body[i].isWhitespace())) i++
                if (i >= body.length || body[i] != '"') break
                i++
                val keySb = StringBuilder()
                while (i < body.length) {
                    val c = body[i++]
                    if (c == '\\' && i < body.length) {
                        keySb.append(body[i++])
                    } else if (c == '"') {
                        break
                    } else {
                        keySb.append(c)
                    }
                }
                while (i < body.length && (body[i] == ':' || body[i].isWhitespace())) i++
                val numStart = i
                while (i < body.length && (body[i] == '-' || body[i].isDigit())) i++
                val parsedVal = body.substring(numStart, i).toLongOrNull()
                if (parsedVal != null) {
                    result[keySb.toString()] = parsedVal
                }
            }
            return result
        }
    }
}

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    private object Keys {
        val THEME_MODE = intPreferencesKey("theme_mode")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val BIOMETRIC_LAST_BG = longPreferencesKey("biometric_last_bg")
        val FILE_VIEW_MODE = intPreferencesKey("file_view_mode")
        val CACHE_LIMIT = longPreferencesKey("cache_limit_bytes")
        val THUMBNAIL_CACHE_LIMIT = longPreferencesKey("thumb_cache_limit_bytes")
        val FILE_FILTER = intPreferencesKey("file_filter")
        val FILE_SORT = intPreferencesKey("file_sort")

        val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        val BACKUP_FOLDER_URIS = stringSetPreferencesKey("backup_folder_uris")
        val BACKUP_TARGET_FOLDER = longPreferencesKey("backup_target_folder")
        val BACKUP_SCHEDULE = intPreferencesKey("backup_schedule")
        val BACKUP_WIFI_ONLY = booleanPreferencesKey("backup_wifi_only")
        val BACKUP_CHARGING_ONLY = booleanPreferencesKey("backup_charging_only")
        val BACKUP_STRICT = booleanPreferencesKey("backup_strict")
        val BACKUP_DELETE_AFTER = booleanPreferencesKey("backup_delete_after")
        val BACKUP_ZIP_FOLDERS = booleanPreferencesKey("backup_zip_folders")
        val BACKUP_ZIP_INCREMENTAL = booleanPreferencesKey("backup_zip_incremental")
        val BACKUP_EXCLUDE_PATTERNS = stringPreferencesKey("backup_exclude_patterns")
        val BACKUP_FOLDER_TARGETS = stringPreferencesKey("backup_folder_targets_json")
        val BACKUP_PAUSED = booleanPreferencesKey("backup_paused")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val SCHEDULED_UPLOAD_ENABLED = booleanPreferencesKey("scheduled_upload_enabled")
        val SCHEDULED_UPLOAD_FOLDER_URI = stringPreferencesKey("scheduled_upload_folder_uri")
        val SCHEDULED_UPLOAD_TARGET = longPreferencesKey("scheduled_upload_target")
        val SCHEDULED_UPLOAD_HOUR = intPreferencesKey("scheduled_upload_hour")
        val SCHEDULED_UPLOAD_MINUTE = intPreferencesKey("scheduled_upload_minute")
    }

    /**
     * App preferences flow, with corruption recovery.
     *
     * If the DataStore file is corrupted (e.g., Samsung killed the process
     * mid-write during an fsync), the flow emits the defaults instead of
     * throwing. This prevents a cascade where every ViewModel that observes
     * `data` crashes on collection.
     */
    val data: Flow<AppPreferencesData> = ctx.appStore.data
        .map { p -> p.toDomain() }
        .catch { e ->
            com.tgdrive.core.common.Log.w("AppPreferences: DataStore corrupted — emitting defaults", e)
            emit(AppPreferencesData())
        }

    suspend fun update(transform: (AppPreferencesData) -> AppPreferencesData) {
        ctx.appStore.edit { prefs ->
            val current = prefs.toDomain()
            val next = transform(current)
            prefs.fromDomain(next)
        }
    }

    // ----------------------------------------------------------------- mappers

    private fun Preferences.toDomain(): AppPreferencesData {
        return AppPreferencesData(
            themeMode = ThemeMode.entries.getOrElse(get(Keys.THEME_MODE) ?: 0) { ThemeMode.SYSTEM },
            biometricLockEnabled = get(Keys.BIOMETRIC_ENABLED) ?: false,
            biometricLastBgTime = get(Keys.BIOMETRIC_LAST_BG) ?: 0L,
            fileViewMode = FileViewMode.entries.getOrElse(get(Keys.FILE_VIEW_MODE) ?: 0) { FileViewMode.GRID },
            cacheLimitBytes = get(Keys.CACHE_LIMIT) ?: 500L * 1024 * 1024,
            thumbnailCacheLimitBytes = get(Keys.THUMBNAIL_CACHE_LIMIT) ?: 50L * 1024 * 1024,
            fileFilter = FileFilter.entries.getOrElse(get(Keys.FILE_FILTER) ?: 0) { FileFilter.ALL },
            fileSort = FileSort.entries.getOrElse(get(Keys.FILE_SORT) ?: 5) { FileSort.DATE_DESC },

            backupEnabled = get(Keys.BACKUP_ENABLED) ?: false,
            backupFolderUris = get(Keys.BACKUP_FOLDER_URIS) ?: emptySet(),
            backupTargetFolderId = get(Keys.BACKUP_TARGET_FOLDER) ?: -1L,
            backupSchedule = BackupSchedule.entries.getOrElse(get(Keys.BACKUP_SCHEDULE) ?: 1) { BackupSchedule.DAILY },
            backupWifiOnly = get(Keys.BACKUP_WIFI_ONLY) ?: true,
            backupChargingOnly = get(Keys.BACKUP_CHARGING_ONLY) ?: false,
            backupStrictMode = get(Keys.BACKUP_STRICT) ?: false,
            backupDeleteAfterUpload = get(Keys.BACKUP_DELETE_AFTER) ?: false,
            backupZipFolders = get(Keys.BACKUP_ZIP_FOLDERS) ?: false,
            backupZipIncremental = get(Keys.BACKUP_ZIP_INCREMENTAL) ?: false,
            backupExcludePatterns = get(Keys.BACKUP_EXCLUDE_PATTERNS) ?: "",
            backupFolderTargetsJson = get(Keys.BACKUP_FOLDER_TARGETS) ?: "{}",
            backupPaused = get(Keys.BACKUP_PAUSED) ?: false,
            onboardingCompleted = get(Keys.ONBOARDING_COMPLETED) ?: false,
            scheduledUploadEnabled = get(Keys.SCHEDULED_UPLOAD_ENABLED) ?: false,
            scheduledUploadFolderUri = get(Keys.SCHEDULED_UPLOAD_FOLDER_URI) ?: "",
            scheduledUploadTargetFolderId = get(Keys.SCHEDULED_UPLOAD_TARGET) ?: -1L,
            scheduledUploadHour = get(Keys.SCHEDULED_UPLOAD_HOUR) ?: 3,
            scheduledUploadMinute = get(Keys.SCHEDULED_UPLOAD_MINUTE) ?: 0,
        )
    }

    private fun MutablePreferences.fromDomain(d: AppPreferencesData) {
        this[Keys.THEME_MODE] = d.themeMode.ordinal
        this[Keys.BIOMETRIC_ENABLED] = d.biometricLockEnabled
        this[Keys.BIOMETRIC_LAST_BG] = d.biometricLastBgTime
        this[Keys.FILE_VIEW_MODE] = d.fileViewMode.ordinal
        this[Keys.CACHE_LIMIT] = d.cacheLimitBytes
        this[Keys.THUMBNAIL_CACHE_LIMIT] = d.thumbnailCacheLimitBytes
        this[Keys.FILE_FILTER] = d.fileFilter.ordinal
        this[Keys.FILE_SORT] = d.fileSort.ordinal

        this[Keys.BACKUP_ENABLED] = d.backupEnabled
        this[Keys.BACKUP_FOLDER_URIS] = d.backupFolderUris
        this[Keys.BACKUP_TARGET_FOLDER] = d.backupTargetFolderId
        this[Keys.BACKUP_SCHEDULE] = d.backupSchedule.ordinal
        this[Keys.BACKUP_WIFI_ONLY] = d.backupWifiOnly
        this[Keys.BACKUP_CHARGING_ONLY] = d.backupChargingOnly
        this[Keys.BACKUP_STRICT] = d.backupStrictMode
        this[Keys.BACKUP_DELETE_AFTER] = d.backupDeleteAfterUpload
        this[Keys.BACKUP_ZIP_FOLDERS] = d.backupZipFolders
        this[Keys.BACKUP_ZIP_INCREMENTAL] = d.backupZipIncremental
        this[Keys.BACKUP_EXCLUDE_PATTERNS] = d.backupExcludePatterns
        this[Keys.BACKUP_FOLDER_TARGETS] = d.backupFolderTargetsJson
        this[Keys.BACKUP_PAUSED] = d.backupPaused
        this[Keys.ONBOARDING_COMPLETED] = d.onboardingCompleted
        this[Keys.SCHEDULED_UPLOAD_ENABLED] = d.scheduledUploadEnabled
        this[Keys.SCHEDULED_UPLOAD_FOLDER_URI] = d.scheduledUploadFolderUri
        this[Keys.SCHEDULED_UPLOAD_TARGET] = d.scheduledUploadTargetFolderId
        this[Keys.SCHEDULED_UPLOAD_HOUR] = d.scheduledUploadHour
        this[Keys.SCHEDULED_UPLOAD_MINUTE] = d.scheduledUploadMinute
    }
}

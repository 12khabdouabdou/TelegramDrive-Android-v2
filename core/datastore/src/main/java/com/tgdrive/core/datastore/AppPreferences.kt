package com.tgdrive.core.datastore

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
private val Context.appStore by preferencesDataStore(
    name = "tgdrive_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class BackupSchedule { ON_CHANGE, DAILY, WEEKLY, MANUAL }
enum class BackupUploadOrder { NEWEST_FIRST, OLDEST_FIRST, SMALLEST_FIRST }
enum class BackupMediaFilter { ALL_FILES, PHOTOS_AND_VIDEOS, PHOTOS_ONLY }
enum class BackupQuality { ORIGINAL, STORAGE_SAVER }
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
    val recentSearchQueries: String = "",
    val lastCloudSyncMs: Long = 0L,

    // AutoBackup v2 enhancements
    val backupUploadOrder: BackupUploadOrder = BackupUploadOrder.NEWEST_FIRST,
    val backupMediaFilter: BackupMediaFilter = BackupMediaFilter.ALL_FILES,
    val backupQuality: BackupQuality = BackupQuality.ORIGINAL,
    val backupOnlyNewAfterMs: Long = 0L,
    val backupCellularDailyLimitMb: Int = 0,
    val backupCellularUsedBytes: Long = 0L,
    val backupCellularUsageDay: String = "",
    val backupSkipVideosOnCellular: Boolean = true,
    val backupMaxFileSizeMb: Int = 0,
    val backupMinBatteryPercent: Int = 15,
    val backupAutoOrganizeByDate: Boolean = false,
    val backupEncryptedFolderUris: Set<String> = emptySet(),
    val backupEncryptAll: Boolean = false,
    val backupUseCustomPassphrase: Boolean = false,
    val backupScheduleHour: Int = 2,
    val backupScheduleMinute: Int = 0,
    val backupSyncIndexRestored: Boolean = false,
    val decryptedTempTtlMinutes: Int = 30,
    val manualUploadWifiOnly: Boolean = false,
    val photosColumns: Int = 3,
    val biometricTimeoutSeconds: Int = 60,
    val blockScreenshotsEverywhere: Boolean = false,
    val downloadConcurrency: Int = 2,
    val downloadWifiOnly: Boolean = false,
    val autoSaveDownloadsToPublic: Boolean = false,
    val pinnedFolderChatIds: Set<String> = emptySet(),
    val showEncryptedPreviewsWhenUnlocked: Boolean = false,
) {
    fun isFolderPinned(chatId: Long): Boolean = chatId.toString() in pinnedFolderChatIds

    fun withToggledPinnedFolder(chatId: Long): AppPreferencesData {
        val k = chatId.toString()
        val next = if (k in pinnedFolderChatIds) pinnedFolderChatIds - k else pinnedFolderChatIds + k
        return copy(pinnedFolderChatIds = next)
    }
    fun isFolderEncrypted(folderUri: String): Boolean =
        backupEncryptAll || (folderUri in backupEncryptedFolderUris)

    fun recentSearches(): List<String> =
        recentSearchQueries.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(10)

    fun withRecentSearch(query: String): AppPreferencesData {
        val cleaned = query.trim().replace('\n', ' ')
        if (cleaned.isEmpty()) return this
        val updated = listOf(cleaned) + recentSearches().filterNot { it.equals(cleaned, ignoreCase = true) }
        return copy(recentSearchQueries = updated.take(10).joinToString("\n"))
    }

    fun clearRecentSearches(): AppPreferencesData = copy(recentSearchQueries = "")

    fun withoutRecentSearch(query: String): AppPreferencesData {
        val cleaned = query.trim().replace('\n', ' ')
        val updated = recentSearches().filterNot { it.equals(cleaned, ignoreCase = true) }
        return copy(recentSearchQueries = updated.joinToString("\n"))
    }
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
            val obj = org.json.JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            return obj.toString()
        }

        private fun decodeLongMap(json: String): Map<String, Long> {
            val trimmed = json.trim()
            if (trimmed.isEmpty() || trimmed == "{}") return emptyMap()
            return try {
                val obj = org.json.JSONObject(trimmed)
                val result = LinkedHashMap<String, Long>(obj.length())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val parsedVal = when (val raw = obj.opt(k)) {
                        is Number -> raw.toLong()
                        is String -> raw.toLongOrNull()
                        else -> null
                    }
                    if (parsedVal != null) {
                        result[k] = parsedVal
                    }
                }
                result
            } catch (_: Throwable) {
                emptyMap()
            }
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
        val RECENT_SEARCH_QUERIES = stringPreferencesKey("recent_search_queries")
        val LAST_CLOUD_SYNC_MS = longPreferencesKey("last_cloud_sync_ms")

        val BACKUP_UPLOAD_ORDER = intPreferencesKey("backup_upload_order")
        val BACKUP_MEDIA_FILTER = intPreferencesKey("backup_media_filter")
        val BACKUP_QUALITY = intPreferencesKey("backup_quality")
        val BACKUP_ONLY_NEW_AFTER_MS = longPreferencesKey("backup_only_new_after_ms")
        val BACKUP_CELLULAR_DAILY_LIMIT_MB = intPreferencesKey("backup_cellular_daily_limit_mb")
        val BACKUP_CELLULAR_USED_BYTES = longPreferencesKey("backup_cellular_used_bytes")
        val BACKUP_CELLULAR_USAGE_DAY = stringPreferencesKey("backup_cellular_usage_day")
        val BACKUP_SKIP_VIDEOS_ON_CELLULAR = booleanPreferencesKey("backup_skip_videos_on_cellular")
        val BACKUP_MAX_FILE_SIZE_MB = intPreferencesKey("backup_max_file_size_mb")
        val BACKUP_MIN_BATTERY_PERCENT = intPreferencesKey("backup_min_battery_percent")
        val BACKUP_AUTO_ORGANIZE_BY_DATE = booleanPreferencesKey("backup_auto_organize_by_date")
        val BACKUP_ENCRYPTED_FOLDER_URIS = stringSetPreferencesKey("backup_encrypted_folder_uris")
        val BACKUP_ENCRYPT_ALL = booleanPreferencesKey("backup_encrypt_all")
        val BACKUP_USE_CUSTOM_PASSPHRASE = booleanPreferencesKey("backup_use_custom_passphrase")
        val BACKUP_SCHEDULE_HOUR = intPreferencesKey("backup_schedule_hour")
        val BACKUP_SCHEDULE_MINUTE = intPreferencesKey("backup_schedule_minute")
        val BACKUP_SYNC_INDEX_RESTORED = booleanPreferencesKey("backup_sync_index_restored")
        val DECRYPTED_TEMP_TTL_MINUTES = intPreferencesKey("decrypted_temp_ttl_minutes")
        val MANUAL_UPLOAD_WIFI_ONLY = booleanPreferencesKey("manual_upload_wifi_only")
        val PHOTOS_COLUMNS = intPreferencesKey("photos_columns")
        val BIOMETRIC_TIMEOUT_SECONDS = intPreferencesKey("biometric_timeout_seconds")
        val BLOCK_SCREENSHOTS_EVERYWHERE = booleanPreferencesKey("block_screenshots_everywhere")
        val DOWNLOAD_CONCURRENCY = intPreferencesKey("download_concurrency")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val AUTO_SAVE_DOWNLOADS_TO_PUBLIC = booleanPreferencesKey("auto_save_downloads_to_public")
        val PINNED_FOLDER_CHAT_IDS = stringSetPreferencesKey("pinned_folder_chat_ids")
        val SHOW_ENCRYPTED_PREVIEWS_UNLOCKED = booleanPreferencesKey("show_encrypted_previews_unlocked")
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

    suspend fun resetUserSession() {
        update { current ->
            AppPreferencesData(
                themeMode = current.themeMode,
                onboardingCompleted = current.onboardingCompleted,
            )
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
            recentSearchQueries = get(Keys.RECENT_SEARCH_QUERIES) ?: "",
            lastCloudSyncMs = get(Keys.LAST_CLOUD_SYNC_MS) ?: 0L,
            backupUploadOrder = BackupUploadOrder.entries.getOrElse(get(Keys.BACKUP_UPLOAD_ORDER) ?: 0) {
                BackupUploadOrder.NEWEST_FIRST
            },
            backupMediaFilter = BackupMediaFilter.entries.getOrElse(get(Keys.BACKUP_MEDIA_FILTER) ?: 0) {
                BackupMediaFilter.ALL_FILES
            },
            backupQuality = BackupQuality.entries.getOrElse(get(Keys.BACKUP_QUALITY) ?: 0) {
                BackupQuality.ORIGINAL
            },
            backupOnlyNewAfterMs = get(Keys.BACKUP_ONLY_NEW_AFTER_MS) ?: 0L,
            backupCellularDailyLimitMb = get(Keys.BACKUP_CELLULAR_DAILY_LIMIT_MB) ?: 0,
            backupCellularUsedBytes = get(Keys.BACKUP_CELLULAR_USED_BYTES) ?: 0L,
            backupCellularUsageDay = get(Keys.BACKUP_CELLULAR_USAGE_DAY) ?: "",
            backupSkipVideosOnCellular = get(Keys.BACKUP_SKIP_VIDEOS_ON_CELLULAR) ?: true,
            backupMaxFileSizeMb = get(Keys.BACKUP_MAX_FILE_SIZE_MB) ?: 0,
            backupMinBatteryPercent = (get(Keys.BACKUP_MIN_BATTERY_PERCENT) ?: 15).coerceIn(5, 80),
            backupAutoOrganizeByDate = get(Keys.BACKUP_AUTO_ORGANIZE_BY_DATE) ?: false,
            backupEncryptedFolderUris = get(Keys.BACKUP_ENCRYPTED_FOLDER_URIS) ?: emptySet(),
            backupEncryptAll = get(Keys.BACKUP_ENCRYPT_ALL) ?: false,
            backupUseCustomPassphrase = get(Keys.BACKUP_USE_CUSTOM_PASSPHRASE) ?: false,
            backupScheduleHour = (get(Keys.BACKUP_SCHEDULE_HOUR) ?: 2).coerceIn(0, 23),
            backupScheduleMinute = (get(Keys.BACKUP_SCHEDULE_MINUTE) ?: 0).coerceIn(0, 59),
            backupSyncIndexRestored = get(Keys.BACKUP_SYNC_INDEX_RESTORED) ?: false,
            decryptedTempTtlMinutes = get(Keys.DECRYPTED_TEMP_TTL_MINUTES) ?: 30,
            manualUploadWifiOnly = get(Keys.MANUAL_UPLOAD_WIFI_ONLY) ?: false,
            photosColumns = (get(Keys.PHOTOS_COLUMNS) ?: 3).coerceIn(2, 5),
            biometricTimeoutSeconds = (get(Keys.BIOMETRIC_TIMEOUT_SECONDS) ?: 60).coerceIn(0, 3600),
            blockScreenshotsEverywhere = get(Keys.BLOCK_SCREENSHOTS_EVERYWHERE) ?: false,
            downloadConcurrency = (get(Keys.DOWNLOAD_CONCURRENCY) ?: 2).coerceIn(1, 4),
            downloadWifiOnly = get(Keys.DOWNLOAD_WIFI_ONLY) ?: false,
            autoSaveDownloadsToPublic = get(Keys.AUTO_SAVE_DOWNLOADS_TO_PUBLIC) ?: false,
            pinnedFolderChatIds = get(Keys.PINNED_FOLDER_CHAT_IDS) ?: emptySet(),
            showEncryptedPreviewsWhenUnlocked = get(Keys.SHOW_ENCRYPTED_PREVIEWS_UNLOCKED) ?: false,
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
        this[Keys.RECENT_SEARCH_QUERIES] = d.recentSearchQueries
        this[Keys.LAST_CLOUD_SYNC_MS] = d.lastCloudSyncMs
        this[Keys.BACKUP_UPLOAD_ORDER] = d.backupUploadOrder.ordinal
        this[Keys.BACKUP_MEDIA_FILTER] = d.backupMediaFilter.ordinal
        this[Keys.BACKUP_QUALITY] = d.backupQuality.ordinal
        this[Keys.BACKUP_ONLY_NEW_AFTER_MS] = d.backupOnlyNewAfterMs
        this[Keys.BACKUP_CELLULAR_DAILY_LIMIT_MB] = d.backupCellularDailyLimitMb
        this[Keys.BACKUP_CELLULAR_USED_BYTES] = d.backupCellularUsedBytes
        this[Keys.BACKUP_CELLULAR_USAGE_DAY] = d.backupCellularUsageDay
        this[Keys.BACKUP_SKIP_VIDEOS_ON_CELLULAR] = d.backupSkipVideosOnCellular
        this[Keys.BACKUP_MAX_FILE_SIZE_MB] = d.backupMaxFileSizeMb
        this[Keys.BACKUP_MIN_BATTERY_PERCENT] = d.backupMinBatteryPercent
        this[Keys.BACKUP_AUTO_ORGANIZE_BY_DATE] = d.backupAutoOrganizeByDate
        this[Keys.BACKUP_ENCRYPTED_FOLDER_URIS] = d.backupEncryptedFolderUris
        this[Keys.BACKUP_ENCRYPT_ALL] = d.backupEncryptAll
        this[Keys.BACKUP_USE_CUSTOM_PASSPHRASE] = d.backupUseCustomPassphrase
        this[Keys.BACKUP_SCHEDULE_HOUR] = d.backupScheduleHour
        this[Keys.BACKUP_SCHEDULE_MINUTE] = d.backupScheduleMinute
        this[Keys.BACKUP_SYNC_INDEX_RESTORED] = d.backupSyncIndexRestored
        this[Keys.DECRYPTED_TEMP_TTL_MINUTES] = d.decryptedTempTtlMinutes
        this[Keys.MANUAL_UPLOAD_WIFI_ONLY] = d.manualUploadWifiOnly
        this[Keys.PHOTOS_COLUMNS] = d.photosColumns.coerceIn(2, 5)
        this[Keys.BIOMETRIC_TIMEOUT_SECONDS] = d.biometricTimeoutSeconds.coerceIn(0, 3600)
        this[Keys.BLOCK_SCREENSHOTS_EVERYWHERE] = d.blockScreenshotsEverywhere
        this[Keys.DOWNLOAD_CONCURRENCY] = d.downloadConcurrency.coerceIn(1, 4)
        this[Keys.DOWNLOAD_WIFI_ONLY] = d.downloadWifiOnly
        this[Keys.AUTO_SAVE_DOWNLOADS_TO_PUBLIC] = d.autoSaveDownloadsToPublic
        this[Keys.PINNED_FOLDER_CHAT_IDS] = d.pinnedFolderChatIds
        this[Keys.SHOW_ENCRYPTED_PREVIEWS_UNLOCKED] = d.showEncryptedPreviewsWhenUnlocked
    }
}

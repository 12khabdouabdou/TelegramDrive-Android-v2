package com.tgdrive.feature.browser.analytics

import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.FolderEntity

/**
 * Pure, side-effect-free models + functions for the Storage Analytics
 * dashboard. Mirrors the `DiffEngine` pattern: every domain transform lives
 * here as a pure function so it can be unit-tested without Room, TDLib, or
 * Compose.
 *
 * The ViewModel is responsible for fetching rows from the DAOs and calling
 * [compute]; the Screen is responsible for rendering the result. This is
 * the single source of truth for "what the numbers on the dashboard mean".
 */
object StorageAnalytics {
    /**
     * Top-level dashboard model. Built by [compute] from raw DB rows.
     *
     * All byte counts are pre-formatted as raw [Long]s — the Compose layer
     * calls `toHumanSize()` for display. This keeps the model testable.
     */
    data class Report(
        val totalFiles: Int,
        val totalBytes: Long,
        val totalStarred: Int,
        val perFolder: List<FolderStat>,
        val perType: List<TypeStat>,
        val dedup: DedupStat,
        val backup: BackupStat,
    )

    /** Per-folder roll-up. The [label] is the folder title or the
     *  hardcoded "Saved Messages" sentinel for chatId == myUserId. */
    data class FolderStat(
        val chatId: Long,
        val label: String,
        val fileCount: Int,
        val totalBytes: Long,
        val lastActivityMs: Long,
        val isSavedMessages: Boolean,
    )

    /** Per-file-type roll-up. The [bucket] is a stable enum so the UI can
     *  map each bucket to a tint + icon without parsing the label. */
    data class TypeStat(
        val bucket: FileBucket,
        val label: String,
        val fileCount: Int,
        val totalBytes: Long,
    )

    /** Deduplication summary across the whole cache. */
    data class DedupStat(
        val duplicateGroups: Int,
        val duplicateCopies: Int,
        val wastedBytes: Long,
        val topOffenders: List<DuplicateOffender>,
    )

    data class DuplicateOffender(
        val fileName: String,
        val fileSize: Long,
        val copies: Int,
        val wastedBytes: Long,
    )

    /** AutoBackup coverage summary — bytes that have a synced-file record. */
    data class BackupStat(
        val syncedFiles: Int,
        val syncedBytes: Long,
        val lastSyncMs: Long,
    )

    /**
     * Compute a full [Report] from raw DAO rows. Pure function — no I/O,
     * no exceptions thrown (defensive defaults instead, matching the
     * codebase convention that analytics never crashes the UI).
     *
     * @param cacheAggregates per-chat aggregates from [FileCacheDao.perChatAggregates]
     * @param grandTotal total counts from [FileCacheDao.grandTotal]
     * @param duplicateAggregates duplicate groups from [FileCacheDao.duplicateAggregates]
     * @param folders local folder rows for label resolution
     * @param savedMessagesChatId the user's own Telegram id, used to flag
     *        the Saved Messages row
     * @param allCachedFiles the full list of cached files; used for the
     *        per-type breakdown (cheap because the cache is bounded) and
     *        for the starred-file count
     * @param backupGrandTotal aggregate from [SyncedFileDao.grandTotal]
     * @param topOffenderCount how many duplicate offenders to surface
     *        (defaults to 5; the rest are still counted in the totals)
     */
    fun compute(
        cacheAggregates: List<FileCacheDao.ChatAggregateRow>,
        grandTotal: FileCacheDao.GrandTotalRow,
        duplicateAggregates: List<FileCacheDao.DuplicateAggregateRow>,
        folders: List<FolderEntity>,
        savedMessagesChatId: Long,
        allCachedFiles: List<FileCacheEntity>,
        backupGrandTotal: SyncedFileDao.SyncedGrandTotalRow,
        topOffenderCount: Int = 5,
    ): Report = assembleReport(
        cacheAggregates = cacheAggregates,
        grandTotal = grandTotal,
        duplicateAggregates = duplicateAggregates,
        perType = bucketByType(allCachedFiles),
        totalStarred = allCachedFiles.count { it.isStarred },
        folders = folders,
        savedMessagesChatId = savedMessagesChatId,
        backupGrandTotal = backupGrandTotal,
        topOffenderCount = topOffenderCount,
    )

    fun computeFromAggregates(
        cacheAggregates: List<FileCacheDao.ChatAggregateRow>,
        grandTotal: FileCacheDao.GrandTotalRow,
        duplicateAggregates: List<FileCacheDao.DuplicateAggregateRow>,
        bucketAggregates: List<FileCacheDao.BucketAggregateRow>,
        totalStarred: Int,
        folders: List<FolderEntity>,
        savedMessagesChatId: Long,
        backupGrandTotal: SyncedFileDao.SyncedGrandTotalRow,
        topOffenderCount: Int = 5,
    ): Report {
        val counts = mutableMapOf<FileBucket, TypeAccumulator>()
        for (row in bucketAggregates) {
            val bucket = runCatching { FileBucket.valueOf(row.bucket) }.getOrDefault(FileBucket.OTHER)
            val acc = counts.getOrPut(bucket) { TypeAccumulator() }
            acc.count += row.fileCount
            acc.bytes += row.totalBytes
        }
        val perType = FileBucket.entries
            .map { bucket ->
                val acc = counts[bucket] ?: TypeAccumulator()
                TypeStat(
                    bucket = bucket,
                    label = bucket.label,
                    fileCount = acc.count,
                    totalBytes = acc.bytes,
                )
            }.filter { it.fileCount > 0 || it.totalBytes > 0 }
            .sortedByDescending { it.totalBytes }
        return assembleReport(
            cacheAggregates = cacheAggregates,
            grandTotal = grandTotal,
            duplicateAggregates = duplicateAggregates,
            perType = perType,
            totalStarred = totalStarred,
            folders = folders,
            savedMessagesChatId = savedMessagesChatId,
            backupGrandTotal = backupGrandTotal,
            topOffenderCount = topOffenderCount,
        )
    }

    private fun assembleReport(
        cacheAggregates: List<FileCacheDao.ChatAggregateRow>,
        grandTotal: FileCacheDao.GrandTotalRow,
        duplicateAggregates: List<FileCacheDao.DuplicateAggregateRow>,
        perType: List<TypeStat>,
        totalStarred: Int,
        folders: List<FolderEntity>,
        savedMessagesChatId: Long,
        backupGrandTotal: SyncedFileDao.SyncedGrandTotalRow,
        topOffenderCount: Int,
    ): Report {
        val folderByChat = folders.associateBy { it.channelId }

        val perFolder: List<FolderStat> =
            cacheAggregates.map { row ->
                val folder = folderByChat[row.chatId]
                val isSavedMessages = row.chatId == savedMessagesChatId || folder == null
                FolderStat(
                    chatId = row.chatId,
                    label = folder?.title ?: "Saved Messages",
                    fileCount = row.fileCount,
                    totalBytes = row.totalBytes,
                    lastActivityMs = row.lastActivityMs,
                    isSavedMessages = isSavedMessages,
                )
            }

        val dedup =
            DedupStat(
                duplicateGroups = duplicateAggregates.size,
                duplicateCopies = duplicateAggregates.sumOf { it.copies },
                wastedBytes = duplicateAggregates.sumOf { it.wastedBytes },
                topOffenders =
                    duplicateAggregates
                        .take(topOffenderCount)
                        .map {
                            DuplicateOffender(
                                fileName = it.fileName,
                                fileSize = it.fileSize,
                                copies = it.copies,
                                wastedBytes = it.wastedBytes,
                            )
                        },
            )

        val backup =
            BackupStat(
                syncedFiles = backupGrandTotal.fileCount,
                syncedBytes = backupGrandTotal.totalBytes,
                lastSyncMs = backupGrandTotal.lastSyncMs,
            )

        return Report(
            totalFiles = grandTotal.fileCount,
            totalBytes = grandTotal.totalBytes,
            totalStarred = totalStarred,
            perFolder = perFolder,
            perType = perType,
            dedup = dedup,
            backup = backup,
        )
    }

    /**
     * Bucket a flat list of cached files into per-type aggregates.
     *
     * Pure function — extracted so the per-type breakdown can be tested
     * independently of Room. The bucketing rules match
     * `StorageUsageChart.computeSegments` so the existing chart and the
     * new analytics screen stay in sync; the canonical source of truth
     * now lives here.
     */
    fun bucketByType(files: List<FileCacheEntity>): List<TypeStat> {
        val counts = mutableMapOf<FileBucket, TypeAccumulator>()
        for (f in files) {
            val bucket = classify(f)
            val acc = counts.getOrPut(bucket) { TypeAccumulator() }
            acc.count += 1
            acc.bytes += f.fileSize
        }
        return FileBucket.entries
            .map { bucket ->
                val acc = counts[bucket] ?: TypeAccumulator()
                TypeStat(
                    bucket = bucket,
                    label = bucket.label,
                    fileCount = acc.count,
                    totalBytes = acc.bytes,
                )
            }.filter { it.fileCount > 0 || it.totalBytes > 0 }
            .sortedByDescending { it.totalBytes }
    }

    /**
     * Classify a single file into its [FileBucket]. Delegates to the
     * canonical [com.tgdrive.core.common.classifyFile] in `:core:common`
     * and maps the result to this module's own [FileBucket] enum.
     */
    fun classify(file: FileCacheEntity): FileBucket {
        val core =
            com.tgdrive.core.common
                .classifyFile(file.mimeType, file.fileName)
        return when (core) {
            com.tgdrive.core.common.FileBucket.IMAGES -> FileBucket.IMAGES
            com.tgdrive.core.common.FileBucket.VIDEOS -> FileBucket.VIDEOS
            com.tgdrive.core.common.FileBucket.AUDIO -> FileBucket.AUDIO
            com.tgdrive.core.common.FileBucket.PDF -> FileBucket.PDF
            com.tgdrive.core.common.FileBucket.DOCUMENTS -> FileBucket.DOCUMENTS
            com.tgdrive.core.common.FileBucket.ARCHIVES -> FileBucket.ARCHIVES
            com.tgdrive.core.common.FileBucket.OTHER -> FileBucket.OTHER
        }
    }

    /** Stable file-type buckets for the analytics dashboard. */
    enum class FileBucket(
        val label: String,
    ) {
        IMAGES("Images"),
        VIDEOS("Videos"),
        AUDIO("Audio"),
        PDF("PDF"),
        DOCUMENTS("Documents"),
        ARCHIVES("Archives"),
        OTHER("Other"),
    }

    private class TypeAccumulator {
        var count: Int = 0
        var bytes: Long = 0L
    }
}

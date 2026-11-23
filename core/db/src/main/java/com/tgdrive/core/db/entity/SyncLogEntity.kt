package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AutoBackup job history. We keep at most the last 50 entries
 * (see [com.tgdrive.core.db.dao.SyncLogDao.deleteOlderThan]).
 *
 * Status values: SUCCESS | FAILURE | PARTIAL | RUNNING.
 *
 * Per spec §7 and §F-05.
 */
@Entity(
    tableName = "sync_log",
    indices = [Index("started_at")],
)
data class SyncLogEntity(
    @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "files_scanned") val filesScanned: Int = 0,
    @ColumnInfo(name = "files_uploaded") val filesUploaded: Int = 0,
    @ColumnInfo(name = "files_skipped") val filesSkipped: Int = 0,
    @ColumnInfo(name = "status") val status: String, // SUCCESS | FAILURE | PARTIAL | RUNNING
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "failed_count") val failedCount: Int = 0,
    @ColumnInfo(name = "failed_details_json") val failedDetailsJson: String? = null,
    @ColumnInfo(name = "bytes_uploaded") val bytesUploaded: Long = 0L,
)

package com.tgdrive.feature.autobackup

/**
 * State machine for a single AutoBackup run.
 *
 * Replaces the previous free-form `phase: String` field. The UI matches on
 * the sealed type, which means the compiler enforces that every screen
 * branch handles every phase. No more typos like "Partial" vs "PARTIAL".
 *
 * Lifecycle:
 *
 *   Idle ──► Scanning ──► Diffing ──► Uploading ──► Finalizing ──► Done
 *                  │           │            │             │
 *                  └───────────┴────────────┴─────────────┴──► Cancelled / Failed
 *
 * The [identifier] field is the jobId (e.g. "manual_1700000000000") so the
 * UI can correlate the active phase with a sync_log row.
 */
sealed class BackupPhase {
    /** No run in progress. */
    data object Idle : BackupPhase()

    /** Walking the selected SAF folders. [foldersDone]/[foldersTotal] tracks per-folder progress. */
    data class Scanning(
        val foldersDone: Int,
        val foldersTotal: Int,
        val filesFound: Int,
    ) : BackupPhase()

    /** Computing the diff against the synced-files registry. */
    data class Diffing(
        val filesFound: Int,
    ) : BackupPhase()

    /** Uploading files one by one. [current]/[total] are 1-indexed file counts. */
    data class Uploading(
        val current: Int,
        val total: Int,
        val fileName: String?,
        val bytesSent: Long,
        val bytesTotal: Long,
        val totalBytesUploaded: Long = 0L,
        val totalBytesAll: Long = 0L,
        val speedBytesPerSec: Long = 0L,
        val etaSeconds: Long = -1L,
        val currentLocalUri: String? = null,
    ) : BackupPhase()

    /** Writing the final sync_log row and cleaning up staging. */
    data class Finalizing(
        val uploaded: Int,
        val total: Int,
        val skipped: Int,
    ) : BackupPhase()

    /** Run completed successfully (or partially — check [uploaded] vs [total]). */
    data class Done(
        val uploaded: Int,
        val total: Int,
        val skipped: Int,
        val failed: Int,
    ) : BackupPhase()

    /** Run was cancelled by the user or system. Staging files have been cleaned up. */
    data class Cancelled(
        val uploaded: Int,
        val total: Int,
    ) : BackupPhase()

    /** Run failed catastrophically (e.g. TDLib not authorized, OOM). */
    data class Failed(
        val message: String,
        val uploaded: Int,
        val total: Int,
    ) : BackupPhase()

    /** True if this phase represents a terminal state (no more progress will be emitted). */
    val isTerminal: Boolean
        get() = this is Done || this is Cancelled || this is Failed

    /** True if this phase represents an active state (work in progress). */
    val isActive: Boolean
        get() = this is Scanning || this is Diffing || this is Uploading || this is Finalizing
}

/**
 * Snapshot of a single backup job's progress, suitable for display in the UI.
 *
 * The [phase] is the only field the UI needs to inspect — it carries all the
 * relevant counts inline.
 */
data class BackupJobState(
    val jobId: String,
    val phase: BackupPhase,
    val startedAt: Long,
)

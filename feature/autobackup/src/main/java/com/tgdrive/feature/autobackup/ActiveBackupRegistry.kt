package com.tgdrive.feature.autobackup

import com.tgdrive.core.common.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide registry of active AutoBackup jobs.
 *
 * Solves three problems:
 *
 * 1. **Mutual exclusion.** Both the periodic WorkManager job and the manual
 *    "Run now" button used to be able to run simultaneously, causing duplicate
 *    uploads and TDLib contention. Now [tryBegin] atomically checks whether a
 *    job is already running and refuses if so.
 *
 * 2. **Cross-component progress.** The ViewModel needs to display progress
 *    emitted by the Worker. Previously the ViewModel had its own
 *    `_progress: MutableStateFlow` that was lost on navigation. Now the
 *    Worker publishes [BackupJobState] to this registry, and the ViewModel
 *    observes [active] (a StateFlow that survives ViewModel recreation).
 *
 * 3. **Cancellation.** [BackupActionReceiver] calls [requestCancellation] to
 *    signal the worker; the worker checks [isCancellationRequested] in its
 *    upload loop and at every chunk boundary.
 *
 * The registry is a Singleton so it survives across Activity/ViewModel
 * recreations. It is *not* persistent across process death — that's fine
 * because WorkManager will restart the worker, and the worker will re-enter
 * the pipeline (and re-create its entry here).
 */
@Singleton
class ActiveBackupRegistry @Inject constructor() {

    private val _active = MutableStateFlow<BackupJobState?>(null)
    val active: StateFlow<BackupJobState?> = _active.asStateFlow()

    private val _cancelRequested = MutableStateFlow<String?>(null)
    val cancelRequested: StateFlow<String?> = _cancelRequested.asStateFlow()

    /**
     * Try to register a new job ATOMICALLY. Returns true if registration
     * succeeded, false if another job is already active.
     *
     * Uses `compareAndSet` to guarantee that two concurrent calls cannot
     * both succeed — previously the check-then-set pattern allowed a race
     * where two orchestrators started simultaneously, causing duplicate
     * uploads, TDLib contention, and shared-buffer corruption in
     * [com.tgdrive.feature.autobackup.diff.Sha256Calculator].
     */
    fun tryBegin(jobId: String, startedAt: Long): Boolean {
        while (true) {
            val current = _active.value
            if (current != null && !current.phase.isTerminal) {
                Log.i("ActiveBackupRegistry: refused to begin $jobId — another job (${current.jobId}) is active")
                return false
            }
            val next = BackupJobState(jobId = jobId, phase = BackupPhase.Idle, startedAt = startedAt)
            if (_active.compareAndSet(current, next)) {
                _cancelRequested.value = null
                Log.i("ActiveBackupRegistry: began job $jobId")
                return true
            }
        }
    }

    /**
     * Update the phase of the active job. No-op if no job is active.
     *
     * Safe to call from any thread. Uses `update` (CAS-based) so concurrent
     * updates don't clobber each other.
     */
    fun updatePhase(phase: BackupPhase) {
        _active.update { current ->
            current?.copy(phase = phase)
        }
    }

    /**
     * Mark the active job as finished. Clears [active] and [cancelRequested].
     *
     * Called by the ViewModel when the user dismisses the terminal phase
     * card, OR by [tryBegin] when a new job claims the slot.
     */
    fun finish() {
        _active.value = null
        _cancelRequested.value = null
        Log.i("ActiveBackupRegistry: finished")
    }

    /**
     * Request cancellation of the current (or next) job with the given [jobId].
     *
     * The orchestrator polls [isCancellationRequested] at every chunk
     * boundary and exits cleanly. This is a "soft" cancel — the orchestrator
     * finishes the current operation (file upload, SHA computation) before
     * checking.
     */
    fun requestCancellation(jobId: String) {
        Log.i("ActiveBackupRegistry: cancellation requested for $jobId")
        _cancelRequested.value = jobId
    }

    /** True if cancellation was requested for the given jobId. */
    fun isCancellationRequested(jobId: String): Boolean {
        return _cancelRequested.value == jobId
    }

    /** True if any backup job is currently in an active phase. */
    fun isAnyActive(): Boolean = _active.value?.phase?.isActive == true
}

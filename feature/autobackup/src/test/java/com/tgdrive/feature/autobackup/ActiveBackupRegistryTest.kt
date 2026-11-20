package com.tgdrive.feature.autobackup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ActiveBackupRegistryTest {

    @Test
    fun `tryBegin succeeds when no job is active`() = runTest {
        val registry = ActiveBackupRegistry()
        val ok = registry.tryBegin("job1", 0L)
        assertThat(ok).isTrue()
        assertThat(registry.active.value?.jobId).isEqualTo("job1")
        assertThat(registry.active.value?.phase).isEqualTo(BackupPhase.Idle)
    }

    @Test
    fun `tryBegin refuses when another job is active`() = runTest {
        val registry = ActiveBackupRegistry()
        registry.tryBegin("job1", 0L)
        registry.updatePhase(BackupPhase.Scanning(0, 1, 0))
        val ok = registry.tryBegin("job2", 1000L)
        assertThat(ok).isFalse()
        assertThat(registry.active.value?.jobId).isEqualTo("job1")
    }

    @Test
    fun `tryBegin succeeds after previous job reached terminal phase`() = runTest {
        val registry = ActiveBackupRegistry()
        registry.tryBegin("job1", 0L)
        registry.updatePhase(BackupPhase.Done(5, 5, 0, 0))
        // Job1 is done (terminal) — job2 should be able to start.
        val ok = registry.tryBegin("job2", 1000L)
        assertThat(ok).isTrue()
        assertThat(registry.active.value?.jobId).isEqualTo("job2")
    }

    @Test
    fun `requestCancellation sets cancelRequested`() = runTest {
        val registry = ActiveBackupRegistry()
        registry.tryBegin("job1", 0L)
        registry.requestCancellation("job1")
        assertThat(registry.isCancellationRequested("job1")).isTrue()
        assertThat(registry.isCancellationRequested("job2")).isFalse()
    }

    @Test
    fun `finish clears active and cancelRequested`() = runTest {
        val registry = ActiveBackupRegistry()
        registry.tryBegin("job1", 0L)
        registry.requestCancellation("job1")
        registry.finish()
        assertThat(registry.active.value).isNull()
        assertThat(registry.cancelRequested.value).isNull()
    }

    @Test
    fun `isAnyActive returns true only for active phases`() = runTest {
        val registry = ActiveBackupRegistry()
        assertThat(registry.isAnyActive()).isFalse()

        registry.tryBegin("job1", 0L)
        // Idle is NOT active
        assertThat(registry.isAnyActive()).isFalse()

        registry.updatePhase(BackupPhase.Scanning(0, 1, 0))
        assertThat(registry.isAnyActive()).isTrue()

        registry.updatePhase(BackupPhase.Uploading(1, 5, "f.txt", 0, 100))
        assertThat(registry.isAnyActive()).isTrue()

        registry.updatePhase(BackupPhase.Done(5, 5, 0, 0))
        assertThat(registry.isAnyActive()).isFalse()
    }

    @Test
    fun `updatePhase is no-op when no job is active`() = runTest {
        val registry = ActiveBackupRegistry()
        registry.updatePhase(BackupPhase.Scanning(0, 1, 0))
        assertThat(registry.active.value).isNull()
    }

    @Test
    fun `tryBegin refuses immediately after another tryBegin before Scanning`() = runTest {
        val registry = ActiveBackupRegistry()
        assertThat(registry.tryBegin("job1", 0L)).isTrue()
        assertThat(registry.tryBegin("job2", 1000L)).isFalse()
        assertThat(registry.active.value?.jobId).isEqualTo("job1")
    }

    @Test
    fun `tryBegin clears previous cancelRequested`() = runTest {
        val registry = ActiveBackupRegistry()
        registry.tryBegin("job1", 0L)
        registry.requestCancellation("job1")
        registry.updatePhase(BackupPhase.Cancelled(0, 0))
        // Now start job2 — cancelRequested should be cleared.
        registry.tryBegin("job2", 1000L)
        assertThat(registry.isCancellationRequested("job1")).isFalse()
        assertThat(registry.isCancellationRequested("job2")).isFalse()
    }
}

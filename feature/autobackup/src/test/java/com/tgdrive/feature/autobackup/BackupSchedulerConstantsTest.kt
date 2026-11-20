package com.tgdrive.feature.autobackup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BackupSchedulerConstantsTest {

    @Test
    fun `periodic work name is stable across releases`() {
        // Per spec §8: unique name "autobackup_periodic" with KEEP policy.
        // Changing this string would orphan any existing scheduled work
        // after an app update — keep it stable.
        assertThat(BackupScheduler.PERIODIC_WORK_NAME).isEqualTo("autobackup_periodic")
        assertThat(BackupScheduler.ONE_TIME_WORK_NAME).isEqualTo("autobackup_onetime")
    }
}

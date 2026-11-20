package com.tgdrive.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tgdrive.core.db.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {

    @Query("SELECT * FROM sync_log ORDER BY started_at DESC LIMIT 50")
    fun observeRecent(): Flow<List<SyncLogEntity>>

    @Query("SELECT * FROM sync_log WHERE job_id = :jobId LIMIT 1")
    suspend fun get(jobId: String): SyncLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SyncLogEntity)

    @Update
    suspend fun update(entry: SyncLogEntity)

    @Query("DELETE FROM sync_log WHERE started_at < :threshold")
    suspend fun deleteOlderThan(threshold: Long): Int

    /** Mark every row with status='RUNNING' as 'CANCELLED'. Called on app
     *  start to recover from a previous-process crash that left rows stuck
     *  in RUNNING. Optionally records the timestamp at which we made the
     *  recovery (passes null as completed_at so the UI shows the original
     *  started_at — which is more useful than the recovery time). */
    @Query("""
        UPDATE sync_log
        SET status = 'CANCELLED',
            completed_at = :nowMs,
            error_message = COALESCE(error_message, 'Process killed mid-run')
        WHERE status = 'RUNNING'
    """)
    suspend fun markRunningAsCancelled(nowMs: Long): Int

    @Query("DELETE FROM sync_log")
    suspend fun clear()
}

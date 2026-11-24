package com.tgdrive.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgdrive.core.db.entity.PendingUploadEntity

@Dao
interface PendingUploadDao {
    @Query("SELECT * FROM pending_uploads ORDER BY created_at_ms ASC")
    suspend fun getAll(): List<PendingUploadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingUploadEntity)

    @Query("UPDATE pending_uploads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM pending_uploads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_uploads")
    suspend fun clear()
}

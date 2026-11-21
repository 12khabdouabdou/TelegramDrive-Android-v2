package com.tgdrive.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgdrive.core.db.entity.TrashEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashEntryDao {

    @Query("SELECT * FROM trash_entries ORDER BY deleted_at DESC")
    fun observeAll(): Flow<List<TrashEntryEntity>>

    @Query("SELECT COUNT(*) FROM trash_entries")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM trash_entries ORDER BY deleted_at DESC")
    suspend fun getAll(): List<TrashEntryEntity>

    @Query("SELECT * FROM trash_entries WHERE chat_id = :chatId AND message_id = :messageId LIMIT 1")
    suspend fun getById(chatId: Long, messageId: Long): TrashEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TrashEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<TrashEntryEntity>)

    @Query("DELETE FROM trash_entries WHERE chat_id = :chatId AND message_id = :messageId")
    suspend fun deleteById(chatId: Long, messageId: Long)

    @Query("SELECT * FROM trash_entries WHERE deleted_at < :cutoffMs")
    suspend fun getOlderThan(cutoffMs: Long): List<TrashEntryEntity>

    @Query("DELETE FROM trash_entries")
    suspend fun clear()
}

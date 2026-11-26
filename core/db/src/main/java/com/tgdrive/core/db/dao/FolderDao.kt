package com.tgdrive.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tgdrive.core.db.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY created_at DESC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY created_at DESC")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE channel_id = :channelId LIMIT 1")
    suspend fun getByChannelId(channelId: Long): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM folders")
    suspend fun clear()

    @Query("UPDATE folders SET color = :color WHERE id = :id")
    suspend fun updateColor(id: Long, color: Int)

    @Query("UPDATE folders SET is_pinned_offline = :pinned WHERE channel_id = :channelId")
    suspend fun setPinnedOffline(channelId: Long, pinned: Boolean)

    @Query("UPDATE folders SET is_vault = :isVault WHERE channel_id = :channelId")
    suspend fun setVault(channelId: Long, isVault: Boolean)

    @Query("SELECT * FROM folders WHERE is_pinned_offline = 1")
    suspend fun getPinnedOfflineFolders(): List<FolderEntity>
}

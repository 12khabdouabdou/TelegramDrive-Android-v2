package com.tgdrive.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgdrive.core.db.entity.AlbumEntity
import com.tgdrive.core.db.entity.AlbumItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

    @Query("SELECT * FROM albums ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums ORDER BY updated_at DESC")
    suspend fun getAll(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM album_items WHERE album_id = :albumId ORDER BY added_at DESC")
    fun observeItems(albumId: String): Flow<List<AlbumItemEntity>>

    @Query("SELECT * FROM album_items ORDER BY added_at DESC")
    fun observeAllItems(): Flow<List<AlbumItemEntity>>

    @Query("SELECT * FROM album_items ORDER BY added_at DESC")
    suspend fun getAllItems(): List<AlbumItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItem(item: AlbumItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItems(items: List<AlbumItemEntity>)

    @Query("DELETE FROM album_items WHERE album_id = :albumId AND chat_id = :chatId AND message_id = :messageId")
    suspend fun removeItem(albumId: String, chatId: Long, messageId: Long)

    @Query("SELECT album_id FROM album_items WHERE chat_id = :chatId AND message_id = :messageId")
    fun observeAlbumIdsForFile(chatId: Long, messageId: Long): Flow<List<String>>

    @Query("DELETE FROM albums")
    suspend fun clear()
}

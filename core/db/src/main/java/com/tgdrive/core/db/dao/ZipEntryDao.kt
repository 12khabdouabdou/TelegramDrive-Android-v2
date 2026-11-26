package com.tgdrive.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgdrive.core.db.entity.ZipEntryEntity

@Dao
interface ZipEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ZipEntryEntity>)

    @Query("SELECT * FROM zip_entries WHERE chat_id = :chatId AND zip_message_id = :zipMessageId ORDER BY entry_path ASC")
    suspend fun forZip(chatId: Long, zipMessageId: Long): List<ZipEntryEntity>

    @Query("DELETE FROM zip_entries WHERE chat_id = :chatId AND zip_message_id = :zipMessageId")
    suspend fun deleteForZip(chatId: Long, zipMessageId: Long)

    @Query("DELETE FROM zip_entries WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: Long)

    @Query("SELECT * FROM zip_entries ORDER BY indexed_at DESC")
    suspend fun getAll(): List<ZipEntryEntity>

    @Query(
        """
        SELECT zip_entries.* FROM zip_entries
        JOIN zip_entries_fts ON zip_entries.rowid = zip_entries_fts.docid
        WHERE zip_entries_fts MATCH :ftsQuery
        ORDER BY zip_entries.indexed_at DESC
        LIMIT :limit
        """,
    )
    suspend fun searchFts(ftsQuery: String, limit: Int = 500): List<ZipEntryEntity>

    @Query("SELECT DISTINCT zip_message_id FROM zip_entries")
    suspend fun indexedZipMessageIds(): List<Long>

    @Query("SELECT COUNT(*) FROM zip_entries WHERE chat_id = :chatId AND zip_message_id = :zipMessageId")
    suspend fun countForZip(chatId: Long, zipMessageId: Long): Int

    @Query("DELETE FROM zip_entries")
    suspend fun clear()
}

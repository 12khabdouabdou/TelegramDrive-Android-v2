package com.tgdrive.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tgdrive.core.db.entity.FileCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileCacheDao {

    @Query("SELECT * FROM file_cache WHERE chat_id = :chatId ORDER BY date DESC LIMIT :limit OFFSET :offset")
    fun observeForChat(chatId: Long, limit: Int, offset: Int): Flow<List<FileCacheEntity>>

    @Query("SELECT * FROM file_cache WHERE chat_id = :chatId ORDER BY date DESC")
    suspend fun forChat(chatId: Long): List<FileCacheEntity>

    @Query("SELECT * FROM file_cache WHERE chat_id = :chatId AND message_id = :messageId LIMIT 1")
    suspend fun getById(chatId: Long, messageId: Long): FileCacheEntity?

    @Query("SELECT COUNT(*) FROM file_cache WHERE chat_id = :chatId")
    suspend fun countForChat(chatId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: FileCacheEntity): Long

    @Query("""
        UPDATE file_cache
        SET file_size = :fileSize,
            mime_type = COALESCE(:mimeType, mime_type),
            thumbnail_path = COALESCE(:thumbnailPath, thumbnail_path),
            remote_id = CASE WHEN :remoteId != '' THEN :remoteId ELSE remote_id END,
            date = :date
        WHERE message_id = :messageId AND chat_id = :chatId
    """)
    suspend fun updateRemoteMetadata(
        chatId: Long,
        messageId: Long,
        fileSize: Long,
        mimeType: String?,
        thumbnailPath: String?,
        remoteId: String,
        date: Long,
    )

    /**
     * Upsert remote file entries WITHOUT wiping local-only fields
     * (`is_starred`, `last_accessed`, local `file_name` renames, or an already
     * populated `thumbnail_path`). Compatible with SQLite 3.19 (API 26+).
     */
    @Transaction
    suspend fun upsertAll(entries: List<FileCacheEntity>) {
        for (entry in entries) {
            val rowId = insertIgnore(entry)
            if (rowId == -1L) {
                updateRemoteMetadata(
                    chatId = entry.chatId,
                    messageId = entry.messageId,
                    fileSize = entry.fileSize,
                    mimeType = entry.mimeType,
                    thumbnailPath = entry.thumbnailPath,
                    remoteId = entry.remoteId,
                    date = entry.date,
                )
            }
        }
    }

    @Query("DELETE FROM file_cache WHERE message_id = :messageId AND chat_id = :chatId")
    suspend fun deleteById(chatId: Long, messageId: Long)

    @Query("DELETE FROM file_cache WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: Long)

    @Query("DELETE FROM file_cache")
    suspend fun clear()

    @Query("SELECT * FROM file_cache WHERE is_starred = 1 ORDER BY date DESC LIMIT 100")
    suspend fun getStarred(): List<FileCacheEntity>

    @Query("SELECT * FROM file_cache ORDER BY last_accessed DESC LIMIT 50")
    suspend fun getRecent(): List<FileCacheEntity>

    @Query("UPDATE file_cache SET is_starred = :starred WHERE message_id = :messageId AND chat_id = :chatId")
    suspend fun setStarred(chatId: Long, messageId: Long, starred: Boolean)

    @Query("UPDATE file_cache SET last_accessed = :timestamp WHERE message_id = :messageId AND chat_id = :chatId")
    suspend fun updateLastAccessed(chatId: Long, messageId: Long, timestamp: Long)

    @Query("SELECT * FROM file_cache WHERE chat_id = :chatId AND file_name = :fileName AND file_size = :fileSize LIMIT 1")
    suspend fun findDuplicate(chatId: Long, fileName: String, fileSize: Long): FileCacheEntity?

    @Query("SELECT * FROM file_cache WHERE chat_id != :currentChatId AND file_name = :fileName AND file_size = :fileSize LIMIT 1")
    suspend fun findDuplicateInOtherChats(currentChatId: Long, fileName: String, fileSize: Long): FileCacheEntity?

    @Query("SELECT * FROM file_cache WHERE file_name = :fileName AND file_size = :fileSize")
    suspend fun findAllDuplicates(fileName: String, fileSize: Long): List<FileCacheEntity>

    @Query("SELECT DISTINCT chat_id FROM file_cache")
    suspend fun getAllChatIds(): List<Long>

    /**
     * Per-chat aggregate used by the Storage Analytics dashboard.
     * Returns one row per chat that has at least one cached file.
     *
     * NOTE: This is a read-only aggregate query — no schema change is
     * needed and the existing v3 schema covers it. Room validates the
     * SQL at compile time.
     */
    @Query("""
        SELECT chat_id AS chatId,
               COUNT(*) AS fileCount,
               COALESCE(SUM(file_size), 0) AS totalBytes,
               COALESCE(MAX(date), 0) AS lastActivityMs
        FROM file_cache
        GROUP BY chat_id
        ORDER BY totalBytes DESC
    """)
    suspend fun perChatAggregates(): List<ChatAggregateRow>

    /** Aggregate row produced by [perChatAggregates]. */
    data class ChatAggregateRow(
        val chatId: Long,
        val fileCount: Int,
        val totalBytes: Long,
        val lastActivityMs: Long,
    )

    /** Grand total across every cached file in every chat. */
    @Query("""
        SELECT COUNT(*) AS fileCount,
               COALESCE(SUM(file_size), 0) AS totalBytes
        FROM file_cache
    """)
    suspend fun grandTotal(): GrandTotalRow

    /** Result of [grandTotal]. Always non-null because COUNT/SUM over an
     *  empty table still returns one row. */
    data class GrandTotalRow(val fileCount: Int, val totalBytes: Long)

    /**
     * Groups files by (file_name, file_size) and returns the groups with
     * more than one entry. Used by Storage Analytics to compute the
     * "potential dedup savings" — the bytes that would be freed if every
     * duplicate beyond the first were deleted.
     *
     * Returns one row per duplicate group, ordered by wasted bytes
     * (descending) so the biggest offenders surface first.
     */
    @Query("""
        SELECT file_name AS fileName,
               file_size AS fileSize,
               COUNT(*) AS copies,
               COUNT(*) - 1 AS wastedCopies,
               (COUNT(*) - 1) * file_size AS wastedBytes
        FROM file_cache
        GROUP BY file_name, file_size
        HAVING COUNT(*) > 1
        ORDER BY wastedBytes DESC
    """)
    suspend fun duplicateAggregates(): List<DuplicateAggregateRow>

    /** Row produced by [duplicateAggregates]. */
    data class DuplicateAggregateRow(
        val fileName: String,
        val fileSize: Long,
        val copies: Int,
        val wastedCopies: Int,
        val wastedBytes: Long,
    )

    @Query("UPDATE file_cache SET message_id = :newId WHERE message_id = :oldId AND chat_id = :chatId")
    suspend fun updateMessageId(chatId: Long, oldId: Long, newId: Long)

    @Query("UPDATE file_cache SET file_name = :newName WHERE message_id = :messageId AND chat_id = :chatId")
    suspend fun updateFileName(chatId: Long, messageId: Long, newName: String)

    @Query("DELETE FROM file_cache WHERE message_id = :newId AND chat_id = :chatId")
    suspend fun deleteByMessageId(chatId: Long, newId: Long): Int

    @Transaction
    suspend fun updateMessageIdTx(chatId: Long, oldId: Long, newId: Long) {
        deleteByMessageId(chatId, newId)
        updateMessageId(chatId, oldId, newId)
    }
}

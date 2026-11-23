package com.tgdrive.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgdrive.core.db.entity.SyncedFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncedFileDao {

    /**
     * Find synced files for a target folder.
     * Pass -1L for Saved Messages (folder_id IS NULL in the DB).
     */
    @Query("""
        SELECT * FROM synced_files
        WHERE CASE WHEN :folderId = -1 THEN folder_id IS NULL ELSE folder_id = :folderId END
    """)
    suspend fun forFolder(folderId: Long): List<SyncedFileEntity>

    @Query("""
        SELECT * FROM synced_files
        WHERE local_path = :path AND size = :size
        LIMIT 1
    """)
    suspend fun findByPathAndSize(path: String, size: Long): SyncedFileEntity?

    @Query("""
        SELECT * FROM synced_files
        WHERE sha256 = :sha256
        LIMIT 1
    """)
    suspend fun findBySha(sha256: String): SyncedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: SyncedFileEntity): Long

    @Query("DELETE FROM synced_files WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM synced_files WHERE folder_id = :folderId")
    suspend fun deleteForFolder(folderId: Long)

    /** Delete the row tracking the given local path. Used by the DiffEngine
     *  to clean up synced records whose local file has been deleted. */
    @Query("DELETE FROM synced_files WHERE local_path = :path")
    suspend fun deleteByPath(path: String): Int

    @Query("""
        DELETE FROM synced_files
        WHERE local_path = :path
          AND CASE WHEN :folderId = -1 THEN folder_id IS NULL ELSE folder_id = :folderId END
    """)
    suspend fun deleteByPathAndFolder(path: String, folderId: Long): Int

    @Query("SELECT * FROM synced_files ORDER BY synced_at DESC")
    fun observeAll(): Flow<List<SyncedFileEntity>>

    @Query("DELETE FROM synced_files")
    suspend fun clear()

    @Query("SELECT * FROM synced_files ORDER BY synced_at DESC")
    suspend fun getAll(): List<SyncedFileEntity>

    /**
     * Aggregate stats across every file AutoBackup has ever synced.
     * Used by the Storage Analytics dashboard to show how much local
     * data is protected by backup.
     *
     * Read-only aggregate; no schema change required.
     */
    @Query("""
        SELECT COUNT(*) AS fileCount,
               COALESCE(SUM(size), 0) AS totalBytes,
               COALESCE(MAX(synced_at), 0) AS lastSyncMs
        FROM synced_files
    """)
    suspend fun grandTotal(): SyncedGrandTotalRow

    /** Result of [grandTotal]. Never null — COUNT/SUM over an empty table
     *  returns one row of zeros. */
    data class SyncedGrandTotalRow(
        val fileCount: Int,
        val totalBytes: Long,
        val lastSyncMs: Long,
    )
}

package com.tgdrive.feature.browser.analytics

import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.FolderEntity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StorageAnalytics]. Mirrors the DiffEngine test pattern:
 * pure JUnit5 + Truth, no Android dependencies, no Room, no coroutine
 * dispatchers. Every public function is covered.
 *
 * What we DON'T test here:
 * - The DAO @Query SQL (validated by Room at compile time)
 * - The ViewModel (needs Turbine + mocked DAOs; covered separately)
 * - The Compose screen (manual smoke test, per spec §14)
 */
class StorageAnalyticsTest {
    // ---------- helpers ----------

    private fun cacheRow(
        chatId: Long,
        fileCount: Int,
        totalBytes: Long,
        lastActivityMs: Long = 0L,
    ) = FileCacheDao.ChatAggregateRow(chatId, fileCount, totalBytes, lastActivityMs)

    private fun dupRow(
        fileName: String,
        fileSize: Long,
        copies: Int,
    ) = FileCacheDao.DuplicateAggregateRow(
        fileName = fileName,
        fileSize = fileSize,
        copies = copies,
        wastedCopies = copies - 1,
        wastedBytes = (copies - 1) * fileSize,
    )

    private fun fileEntity(
        name: String,
        size: Long,
        mime: String? = null,
        chatId: Long = 1L,
        starred: Boolean = false,
    ) = FileCacheEntity(
        messageId = name.hashCode().toLong() and 0xFFFFFFFFL,
        chatId = chatId,
        fileName = name,
        fileSize = size,
        mimeType = mime,
        thumbnailPath = null,
        date = 0L,
        isStarred = starred,
    )

    private fun folder(
        id: Long,
        title: String,
        channelId: Long,
    ) = FolderEntity(id = id, channelId = channelId, title = title, createdAt = 0L, color = 0xFF2D8CF0.toInt())

    // ---------- compute() ----------

    @Nested
    @DisplayName("compute()")
    inner class Compute {
        @Test
        @DisplayName("returns empty report for empty inputs")
        fun emptyInputs() {
            val report =
                StorageAnalytics.compute(
                    cacheAggregates = emptyList(),
                    grandTotal = FileCacheDao.GrandTotalRow(0, 0L),
                    duplicateAggregates = emptyList(),
                    folders = emptyList(),
                    savedMessagesChatId = 0L,
                    allCachedFiles = emptyList(),
                    backupGrandTotal = SyncedFileDao.SyncedGrandTotalRow(0, 0L, 0L),
                )
            assertThat(report.totalFiles).isEqualTo(0)
            assertThat(report.totalBytes).isEqualTo(0L)
            assertThat(report.totalStarred).isEqualTo(0)
            assertThat(report.perFolder).isEmpty()
            assertThat(report.perType).isEmpty()
            assertThat(report.dedup.duplicateGroups).isEqualTo(0)
            assertThat(report.dedup.wastedBytes).isEqualTo(0L)
            assertThat(report.backup.syncedBytes).isEqualTo(0L)
        }

        @Test
        @DisplayName("maps per-chat aggregate to folder label when folder exists")
        fun perFolderLabelFromFolder() {
            val folders = listOf(folder(id = 1, title = "Photos", channelId = 100L))
            val aggregates = listOf(cacheRow(chatId = 100L, fileCount = 4, totalBytes = 1024L))

            val report =
                StorageAnalytics.compute(
                    cacheAggregates = aggregates,
                    grandTotal = FileCacheDao.GrandTotalRow(4, 1024L),
                    duplicateAggregates = emptyList(),
                    folders = folders,
                    savedMessagesChatId = 999L,
                    allCachedFiles = emptyList(),
                    backupGrandTotal = SyncedFileDao.SyncedGrandTotalRow(0, 0L, 0L),
                )

            assertThat(report.perFolder).hasSize(1)
            val stat = report.perFolder.first()
            assertThat(stat.label).isEqualTo("Photos")
            assertThat(stat.isSavedMessages).isFalse()
            assertThat(stat.fileCount).isEqualTo(4)
            assertThat(stat.totalBytes).isEqualTo(1024L)
        }

        @Test
        @DisplayName("falls back to 'Saved Messages' when chatId matches myUserId")
        fun perFolderSavedMessagesByChatId() {
            val aggregates = listOf(cacheRow(chatId = 999L, fileCount = 1, totalBytes = 1L))
            val report =
                StorageAnalytics.compute(
                    cacheAggregates = aggregates,
                    grandTotal = FileCacheDao.GrandTotalRow(1, 1L),
                    duplicateAggregates = emptyList(),
                    folders = emptyList(),
                    savedMessagesChatId = 999L,
                    allCachedFiles = emptyList(),
                    backupGrandTotal = SyncedFileDao.SyncedGrandTotalRow(0, 0L, 0L),
                )
            val stat = report.perFolder.first()
            assertThat(stat.label).isEqualTo("Saved Messages")
            assertThat(stat.isSavedMessages).isTrue()
        }

        @Test
        @DisplayName("also flags 'Saved Messages' when folder row is missing (untracked chat)")
        fun perFolderSavedMessagesFallback() {
            val aggregates = listOf(cacheRow(chatId = 555L, fileCount = 1, totalBytes = 1L))
            val report =
                StorageAnalytics.compute(
                    cacheAggregates = aggregates,
                    grandTotal = FileCacheDao.GrandTotalRow(1, 1L),
                    duplicateAggregates = emptyList(),
                    folders = emptyList(),
                    savedMessagesChatId = 999L,
                    allCachedFiles = emptyList(),
                    backupGrandTotal = SyncedFileDao.SyncedGrandTotalRow(0, 0L, 0L),
                )
            val stat = report.perFolder.first()
            assertThat(stat.label).isEqualTo("Saved Messages")
            assertThat(stat.isSavedMessages).isTrue()
        }

        @Test
        @DisplayName("sums wasted bytes across all duplicate groups")
        fun dedupWastedBytes() {
            val dup =
                listOf(
                    dupRow("a.txt", 100L, copies = 3), // 2 wasted × 100 = 200
                    dupRow("b.txt", 50L, copies = 2), // 1 wasted × 50  = 50
                )
            val report =
                StorageAnalytics.compute(
                    cacheAggregates = emptyList(),
                    grandTotal = FileCacheDao.GrandTotalRow(0, 0L),
                    duplicateAggregates = dup,
                    folders = emptyList(),
                    savedMessagesChatId = 0L,
                    allCachedFiles = emptyList(),
                    backupGrandTotal = SyncedFileDao.SyncedGrandTotalRow(0, 0L, 0L),
                )
            assertThat(report.dedup.duplicateGroups).isEqualTo(2)
            assertThat(report.dedup.duplicateCopies).isEqualTo(5) // 3 + 2
            assertThat(report.dedup.wastedBytes).isEqualTo(250L)
        }

        @Test
        @DisplayName("caps topOffenders at topOffenderCount, totals unchanged")
        fun topOffenderCap() {
            val dup = (1..10).map { dupRow("f$it.bin", it.toLong() * 10L, copies = 2) }
            val report =
                StorageAnalytics.compute(
                    cacheAggregates = emptyList(),
                    grandTotal = FileCacheDao.GrandTotalRow(0, 0L),
                    duplicateAggregates = dup,
                    folders = emptyList(),
                    savedMessagesChatId = 0L,
                    allCachedFiles = emptyList(),
                    backupGrandTotal = SyncedFileDao.SyncedGrandTotalRow(0, 0L, 0L),
                    topOffenderCount = 3,
                )
            assertThat(report.dedup.topOffenders).hasSize(3)
            assertThat(report.dedup.duplicateGroups).isEqualTo(10)
        }

        @Test
        @DisplayName("counts starred files from allCachedFiles, not from a separate query")
        fun starredCount() {
            val files =
                listOf(
                    fileEntity("a.jpg", 1L, starred = true),
                    fileEntity("b.jpg", 2L, starred = true),
                    fileEntity("c.jpg", 3L, starred = false),
                )
            val report =
                StorageAnalytics.compute(
                    cacheAggregates = emptyList(),
                    grandTotal = FileCacheDao.GrandTotalRow(3, 6L),
                    duplicateAggregates = emptyList(),
                    folders = emptyList(),
                    savedMessagesChatId = 0L,
                    allCachedFiles = files,
                    backupGrandTotal = SyncedFileDao.SyncedGrandTotalRow(0, 0L, 0L),
                )
            assertThat(report.totalStarred).isEqualTo(2)
        }
    }

    // ---------- classify() / bucketByType() ----------

    @Nested
    @DisplayName("classify() and bucketByType()")
    inner class Classify {
        @Test
        @DisplayName("image MIME prefix → IMAGES")
        fun imageMime() {
            val f = fileEntity("x", 1L, mime = "image/jpeg")
            assertThat(StorageAnalytics.classify(f)).isEqualTo(StorageAnalytics.FileBucket.IMAGES)
        }

        @Test
        @DisplayName("image extension wins when MIME is null")
        fun imageExtFallback() {
            val f = fileEntity("photo.heic", 1L, mime = null)
            assertThat(StorageAnalytics.classify(f)).isEqualTo(StorageAnalytics.FileBucket.IMAGES)
        }

        @Test
        @DisplayName("video MIME prefix → VIDEOS")
        fun videoMime() {
            val f = fileEntity("clip", 1L, mime = "video/mp4")
            assertThat(StorageAnalytics.classify(f)).isEqualTo(StorageAnalytics.FileBucket.VIDEOS)
        }

        @Test
        @DisplayName("audio MIME prefix → AUDIO")
        fun audioMime() {
            val f = fileEntity("track", 1L, mime = "audio/mpeg")
            assertThat(StorageAnalytics.classify(f)).isEqualTo(StorageAnalytics.FileBucket.AUDIO)
        }

        @Test
        @DisplayName("PDF wins over generic document ext (resolved by MIME first)")
        fun pdfMime() {
            val f = fileEntity("doc.pdf", 1L, mime = "application/pdf")
            assertThat(StorageAnalytics.classify(f)).isEqualTo(StorageAnalytics.FileBucket.PDF)
        }

        @Test
        @DisplayName("DOC_EXTS (.docx) → DOCUMENTS")
        fun docExt() {
            val f = fileEntity("notes.docx", 1L, mime = null)
            assertThat(StorageAnalytics.classify(f)).isEqualTo(StorageAnalytics.FileBucket.DOCUMENTS)
        }

        @Test
        @DisplayName("archive ext (.zip) → ARCHIVES")
        fun archiveExt() {
            val f = fileEntity("backup.zip", 1L, mime = null)
            assertThat(StorageAnalytics.classify(f)).isEqualTo(StorageAnalytics.FileBucket.ARCHIVES)
        }

        @Test
        @DisplayName("unknown mime + unknown ext → OTHER")
        fun other() {
            val f = fileEntity("mystery.dat", 1L, mime = "application/octet-stream")
            assertThat(StorageAnalytics.classify(f)).isEqualTo(StorageAnalytics.FileBucket.OTHER)
        }

        @Test
        @DisplayName("bucketByType aggregates counts and bytes per bucket")
        fun bucketAggregates() {
            val files =
                listOf(
                    fileEntity("a.jpg", 100L, mime = "image/jpeg"),
                    fileEntity("b.png", 200L, mime = "image/png"),
                    fileEntity("c.mp4", 1_000L, mime = "video/mp4"),
                    fileEntity("d.zip", 500L, mime = null),
                )
            val stats = StorageAnalytics.bucketByType(files)
            val byBucket = stats.associateBy { it.bucket }
            assertThat(byBucket[StorageAnalytics.FileBucket.IMAGES]!!.fileCount).isEqualTo(2)
            assertThat(byBucket[StorageAnalytics.FileBucket.IMAGES]!!.totalBytes).isEqualTo(300L)
            assertThat(byBucket[StorageAnalytics.FileBucket.VIDEOS]!!.totalBytes).isEqualTo(1_000L)
            assertThat(byBucket[StorageAnalytics.FileBucket.ARCHIVES]!!.totalBytes).isEqualTo(500L)
        }

        @Test
        @DisplayName("bucketByType filters out empty buckets")
        fun bucketFiltersEmpty() {
            val files = listOf(fileEntity("a.jpg", 100L, mime = "image/jpeg"))
            val stats = StorageAnalytics.bucketByType(files)
            assertThat(stats).hasSize(1)
            assertThat(stats.first().bucket).isEqualTo(StorageAnalytics.FileBucket.IMAGES)
        }

        @Test
        @DisplayName("bucketByType sorts by totalBytes descending")
        fun bucketSort() {
            val files =
                listOf(
                    fileEntity("a.jpg", 100L, mime = "image/jpeg"),
                    fileEntity("b.mp4", 1_000L, mime = "video/mp4"),
                    fileEntity("c.zip", 500L, mime = null),
                )
            val stats = StorageAnalytics.bucketByType(files)
            assertThat(stats.map { it.bucket })
                .containsExactly(
                    StorageAnalytics.FileBucket.VIDEOS,
                    StorageAnalytics.FileBucket.ARCHIVES,
                    StorageAnalytics.FileBucket.IMAGES,
                ).inOrder()
        }

        @Test
        @DisplayName("case-insensitive extension matching")
        fun caseInsensitiveExt() {
            val f = fileEntity("PHOTO.JPG", 1L, mime = null)
            assertThat(StorageAnalytics.classify(f)).isEqualTo(StorageAnalytics.FileBucket.IMAGES)
        }
    }

    @Test
    @DisplayName("computeFromAggregates matches compute() on identical fixtures")
    fun computeFromAggregatesParity() {
        val files = listOf(
            fileEntity("a.jpg", 100L, mime = "image/jpeg", chatId = 10L, starred = true),
            fileEntity("b.mp4", 1_000L, mime = "video/mp4", chatId = 10L, starred = false),
            fileEntity("c.zip", 500L, mime = null, chatId = 20L, starred = true),
        )
        val folders = listOf(folder(1L, "Camera", 10L))
        val cacheAgg = listOf(
            cacheRow(10L, 2, 1_100L),
            cacheRow(20L, 1, 500L),
        )
        val grand = FileCacheDao.GrandTotalRow(3, 1_600L)
        val dups = listOf(dupRow("x", 10L, 2))
        val backupGrand = SyncedFileDao.SyncedGrandTotalRow(2, 1_100L, 999L)

        val full = StorageAnalytics.compute(
            cacheAggregates = cacheAgg,
            grandTotal = grand,
            duplicateAggregates = dups,
            folders = folders,
            savedMessagesChatId = 20L,
            allCachedFiles = files,
            backupGrandTotal = backupGrand,
        )
        val agg = StorageAnalytics.computeFromAggregates(
            cacheAggregates = cacheAgg,
            grandTotal = grand,
            duplicateAggregates = dups,
            bucketAggregates = listOf(
                FileCacheDao.BucketAggregateRow("IMAGES", 1, 100L),
                FileCacheDao.BucketAggregateRow("VIDEOS", 1, 1_000L),
                FileCacheDao.BucketAggregateRow("ARCHIVES", 1, 500L),
            ),
            totalStarred = 2,
            folders = folders,
            savedMessagesChatId = 20L,
            backupGrandTotal = backupGrand,
        )
        assertThat(agg).isEqualTo(full)
    }
}

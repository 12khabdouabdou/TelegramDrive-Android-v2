package com.tgdrive.feature.browser

import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.entity.FileCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DuplicateDetector
    @Inject
    constructor(
        private val fileCacheDao: FileCacheDao,
    ) {
        data class DuplicateGroup(
            val fileName: String,
            val fileSize: Long,
            val entries: List<FileCacheEntity>,
        )

        suspend fun findDuplicates(): List<DuplicateGroup> =
            try {
                val chatIds = fileCacheDao.getAllChatIds()
                val allFiles = mutableListOf<FileCacheEntity>()
                for (chatId in chatIds) {
                    allFiles.addAll(fileCacheDao.forChat(chatId))
                }
                // Group by (fileName, fileSize) and keep groups with >1 entry
                allFiles
                    .groupBy { it.fileName to it.fileSize }
                    .filter { it.value.size > 1 }
                    .map { (key, entries) -> DuplicateGroup(key.first, key.second, entries) }
                    .sortedByDescending { it.entries.size }
            } catch (t: Throwable) {
                Log.w("DuplicateDetector: findDuplicates failed", t)
                emptyList()
            }

        suspend fun findDuplicateInOtherChats(
            currentChatId: Long,
            fileName: String,
            fileSize: Long,
        ): FileCacheEntity? =
            try {
                fileCacheDao.findDuplicateInOtherChats(currentChatId, fileName, fileSize)
            } catch (t: Throwable) {
                Log.w("DuplicateDetector: findDuplicateInOtherChats failed", t)
                null
            }
    }

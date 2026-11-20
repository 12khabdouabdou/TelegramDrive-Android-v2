package com.tgdrive.feature.browser.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Storage Analytics dashboard.
 *
 * State model: a single [UiState] sealed class. This keeps the screen
 * composable trivial — it pattern-matches on the state and renders. All
 * mutation flows through [refresh], matching the project's "single source
 * of mutations" rule from `ARCHITECTURE.md`.
 *
 * Why a pull-to-refresh model instead of a Room Flow subscription? The
 * dashboard joins data from THREE DAOs (file_cache, folders, synced_files)
 * plus the Telegram user id. A reactive combine() over four sources would
 * re-emit on every cache write (e.g. during a backup), recomputing the
 * full report each time. A manual refresh is cheaper and the user can
 * always pull to refresh.
 */
@HiltViewModel
class StorageAnalyticsViewModel
    @Inject
    constructor(
        private val fileCacheDao: FileCacheDao,
        private val folderDao: FolderDao,
        private val syncedFileDao: SyncedFileDao,
        private val telegramClient: TelegramClient,
        private val dispatchers: DispatchersProvider,
    ) : ViewModel() {
        private val _state = MutableStateFlow<UiState>(UiState.Loading)
        val state: StateFlow<UiState> = _state.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch(dispatchers.io) {
                _state.value = UiState.Loading
                try {
                    val report = buildReport()
                    _state.value = UiState.Ready(report)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("StorageAnalyticsVM: refresh failed", t)
                    _state.value = UiState.Error(t.message ?: "Unknown error")
                }
            }
        }

        private suspend fun buildReport(): StorageAnalytics.Report {
            // Resolve myUserId defensively — never let analytics crash because
            // TDLib isn't ready yet. Treat 0L as "Saved Messages chatId unknown";
            // the per-folder fallback still labels such chats as Saved Messages.
            val myUserId =
                try {
                    telegramClient.myUserId()
                } catch (t: Throwable) {
                    Log.w("StorageAnalyticsVM: myUserId lookup failed — assuming 0", t)
                    0L
                }

            val cacheAggregates = fileCacheDao.perChatAggregates()
            val grandTotal = fileCacheDao.grandTotal()
            val duplicateAggregates = fileCacheDao.duplicateAggregates()
            val folders = folderDao.getAll()
            val backupGrandTotal = syncedFileDao.grandTotal()

            // For the per-type + starred breakdown we need the flat file list.
            // Cheap because the cache is bounded (HomeViewModel already loads
            // the same list for "Recent" + "Starred" rows).
            val allCachedFiles = mutableListOf<com.tgdrive.core.db.entity.FileCacheEntity>()
            for (chatId in fileCacheDao.getAllChatIds()) {
                allCachedFiles += fileCacheDao.forChat(chatId)
            }

            return StorageAnalytics.compute(
                cacheAggregates = cacheAggregates,
                grandTotal = grandTotal,
                duplicateAggregates = duplicateAggregates,
                folders = folders,
                savedMessagesChatId = myUserId,
                allCachedFiles = allCachedFiles,
                backupGrandTotal = backupGrandTotal,
            )
        }

        /** UI state. `Loading` is the initial state so the screen shows a
         *  spinner before the first DB round-trip completes. */
        sealed class UiState {
            data object Loading : UiState()

            data class Ready(
                val report: StorageAnalytics.Report,
            ) : UiState()

            data class Error(
                val message: String,
            ) : UiState()
        }
    }

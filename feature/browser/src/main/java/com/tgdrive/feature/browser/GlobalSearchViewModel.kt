package com.tgdrive.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class GlobalSearchUiState {
    data object Idle : GlobalSearchUiState()

    data object Searching : GlobalSearchUiState()

    data class Results(
        val results: List<TelegramMessage>,
    ) : GlobalSearchUiState()

    data class Error(
        val message: String,
    ) : GlobalSearchUiState()
}

@HiltViewModel
class GlobalSearchViewModel
    @Inject
    constructor(
        private val telegramClient: TelegramClient,
        private val folderDao: FolderDao,
        private val dispatchers: DispatchersProvider,
    ) : ViewModel() {
        private val _state = MutableStateFlow<GlobalSearchUiState>(GlobalSearchUiState.Idle)
        val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _folderNames = MutableStateFlow<Map<Long, String>>(emptyMap())
        val folderNames: StateFlow<Map<Long, String>> = _folderNames.asStateFlow()

        private var searchJob: Job? = null

        init {
            viewModelScope.launch(dispatchers.io) {
                try {
                    val map = mutableMapOf<Long, String>()
                    folderDao.getAll().forEach { map[it.channelId] = it.title }
                    val myId = runCatching { telegramClient.myUserId() }.getOrDefault(0L)
                    if (myId != 0L) map[myId] = "Saved Messages"
                    _folderNames.value = map
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {}
            }
        }

        fun search(query: String, immediate: Boolean = false) {
            _query.value = query
            searchJob?.cancel()
            if (query.isBlank()) {
                _state.value = GlobalSearchUiState.Idle
                return
            }
            searchJob =
                viewModelScope.launch(dispatchers.io) {
                    if (!immediate) delay(350)
                    _state.value = GlobalSearchUiState.Searching
                    try {
                        val results = telegramClient.searchMessagesGlobal(query.trim(), limit = 50)
                        _state.value = GlobalSearchUiState.Results(results)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("GlobalSearchVM: search failed", t)
                        _state.value = GlobalSearchUiState.Error(t.message ?: "Search failed")
                    }
                }
        }

        fun retry() {
            search(_query.value, immediate = true)
        }
    }

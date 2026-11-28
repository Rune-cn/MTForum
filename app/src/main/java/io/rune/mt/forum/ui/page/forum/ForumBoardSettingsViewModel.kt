// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumBoardPreferences
import io.rune.mt.forum.domain.forum.model.arrangeForums
import io.rune.mt.forum.domain.forum.repository.ForumBoardPreferencesRepository
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/** UI state of the board order/hide settings screen. */
data class ForumBoardSettingsUiState(
    val boards: List<Forum> = emptyList(),
    val hiddenFids: Set<Int> = emptySet(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Backs the 板块管理 screen: lists the site's own board index (never a hard-coded list), lets the
 * user move rows up/down and hide them, and persists `orderedFids` + `hiddenFids` through
 * [ForumBoardPreferencesRepository].
 *
 * The displayed order is [arrangeForums] over the server list with `includeHidden = true`, so the
 * hidden rows stay visible here (with their switch off) instead of vanishing from the settings page.
 */
class ForumBoardSettingsViewModel(
    private val repository: ForumRepository,
    private val boardPreferences: ForumBoardPreferencesRepository,
) : ViewModel() {

    private val serverBoards = MutableStateFlow<List<Forum>>(emptyList())
    private val prefs = MutableStateFlow(ForumBoardPreferences())
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<String?>(null)
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            boardPreferences.preferences.collect { prefs.value = it }
        }
    }

    val state: StateFlow<ForumBoardSettingsUiState> =
        combine(serverBoards, prefs, loading, error) { boards, preferences, isLoading, message ->
            ForumBoardSettingsUiState(
                boards = arrangeForums(boards, preferences, includeHidden = true),
                hiddenFids = preferences.hiddenFidSet,
                isLoading = isLoading,
                error = message,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ForumBoardSettingsUiState())

    /** Loads the board index once; [force] re-fetches after a failure. */
    fun load(force: Boolean = false) {
        if (!force && loadJob?.isActive != true && serverBoards.value.isNotEmpty()) return
        loadJob?.cancel()
        loading.value = true
        error.value = null
        loadJob = viewModelScope.launch {
            runCatching { repository.getHome() }
                .onSuccess { home ->
                    Timber.tag(TAG).i("board settings boards=%d", home.forums.size)
                    serverBoards.value = home.forums
                    loading.value = false
                }
                .onFailure { failure ->
                    Timber.tag(TAG).w(failure, "board settings board list failed")
                    loading.value = false
                    error.value = failure.message ?: "加载失败"
                }
        }
    }

    fun moveUp(fid: Int) {
        reorder(fid) { index -> if (index > 0) index - 1 else index }
    }

    fun moveDown(fid: Int) {
        reorder(fid) { index -> if (index < currentOrder().lastIndex) index + 1 else index }
    }

    fun setHidden(fid: Int, hidden: Boolean) {
        viewModelScope.launch {
            boardPreferences.update { current ->
                val next = current.hiddenFids.toMutableList()
                if (hidden) {
                    if (fid !in next) next += fid
                } else {
                    next.remove(fid)
                }
                current.copy(hiddenFids = next)
            }
        }
    }

    private fun currentOrder(): List<Int> =
        arrangeForums(serverBoards.value, prefs.value, includeHidden = true).map { it.fid }

    private fun reorder(fid: Int, target: (Int) -> Int) {
        val order = currentOrder()
        val index = order.indexOf(fid)
        if (index < 0) return
        val targetIndex = target(index)
        if (targetIndex == index) return
        val next = order.toMutableList()
        next.removeAt(index)
        next.add(targetIndex, fid)
        viewModelScope.launch {
            // Store the full visible order so the user's arrangement is authoritative; boards the
            // server adds later are absent and keep their server slot via arrangeForums.
            boardPreferences.update { it.copy(orderedFids = next) }
        }
    }

    private companion object {
        const val TAG = "BoardSettingsVM"
    }
}

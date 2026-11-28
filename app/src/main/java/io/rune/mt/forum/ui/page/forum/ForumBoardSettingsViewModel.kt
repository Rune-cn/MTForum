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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * user reorder the grid (drag or the accessible up/down actions) and hide boards, and persists
 * `orderedFids` + `hiddenFids` through [ForumBoardPreferencesRepository].
 *
 * The displayed order is [arrangeForums] over the server list with `includeHidden = true`, so the
 * hidden tiles stay in the grid (grayed out) instead of vanishing from the settings page — that is
 * the only way to bring a hidden board back.
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

    // Reorders are launched from every drag crossing; DataStore serialises its own writes, but not
    // necessarily in launch order. The mutex keeps the queued moves applied in the order the user
    // made them, so a fast drag cannot land a later step first.
    private val reorderMutex = Mutex()

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

    /**
     * Drag reorder: moves the board currently at [from] to [to] in the displayed order. The grid
     * re-slots optimistically on every crossing, so both indices always describe the order the user
     * is looking at.
     */
    fun move(from: Int, to: Int) {
        if (from == to) return
        viewModelScope.launch {
            // Compute the move inside the update lambda so it is applied to the freshest stored
            // order, and hold the mutex so queued crossings land in the order they happened.
            reorderMutex.withLock {
                val boards = serverBoards.value
                boardPreferences.update { current ->
                    val order = arrangeForums(boards, current, includeHidden = true).map { it.fid }
                    if (from !in order.indices || to !in order.indices) {
                        current
                    } else {
                        val next = order.toMutableList()
                        next.add(to, next.removeAt(from))
                        // Store the full visible order so the user's arrangement is authoritative;
                        // boards the server adds later are absent and keep their server slot via
                        // arrangeForums.
                        current.copy(orderedFids = next)
                    }
                }
            }
        }
    }

    fun moveUp(fid: Int) = step(fid, -1)

    fun moveDown(fid: Int) = step(fid, +1)

    /** The accessible per-tile move actions: one slot up/down for the board [fid]. */
    private fun step(fid: Int, delta: Int) {
        viewModelScope.launch {
            reorderMutex.withLock {
                val boards = serverBoards.value
                boardPreferences.update { current ->
                    val order = arrangeForums(boards, current, includeHidden = true).map { it.fid }
                    val index = order.indexOf(fid)
                    val target = index + delta
                    if (index < 0 || target !in order.indices) {
                        current
                    } else {
                        val next = order.toMutableList()
                        next.add(target, next.removeAt(index))
                        current.copy(orderedFids = next)
                    }
                }
            }
        }
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

    private companion object {
        const val TAG = "BoardSettingsVM"
    }
}

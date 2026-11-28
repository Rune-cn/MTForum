// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumThreadHistoryEntry
import io.rune.mt.forum.domain.forum.repository.ForumThreadHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** State of the 浏览历史 screen: the stored visits plus the store-read flags. */
data class ForumThreadHistoryState(
    val isLoading: Boolean = true,
    val entries: List<ForumThreadHistoryEntry> = emptyList(),
    /** Failure of the device store itself; the visits are local, so nothing here touches the net. */
    val error: String? = null,
) {
    /**
     * Data first, then loading, then failure: an empty list only means "no visits" once a read has
     * actually landed, so a broken store can never render as the empty card.
     */
    val phase: ForumThreadHistoryPhase
        get() = when {
            entries.isNotEmpty() -> ForumThreadHistoryPhase.Rows
            isLoading -> ForumThreadHistoryPhase.Loading
            error != null -> ForumThreadHistoryPhase.Error
            else -> ForumThreadHistoryPhase.Empty
        }
}

/** The one body the 浏览历史 screen shows; loading / empty / failure stay three distinct cards. */
enum class ForumThreadHistoryPhase { Loading, Empty, Error, Rows }

/**
 * Backs the 浏览历史 page (设置 → 论坛 → 浏览历史).
 *
 * The list is the device store ([ForumThreadHistoryRepository], one DataStore document written by
 * the thread page), so the screen only mirrors it: a removal is applied to the store and the row
 * disappears when the flow emits again. There is no login gate — a guest's own visits are just as
 * real as a member's, and nothing is sent to the site.
 */
class ForumThreadHistoryViewModel(
    private val repository: ForumThreadHistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ForumThreadHistoryState())
    val state: StateFlow<ForumThreadHistoryState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        observe()
    }

    /** Re-subscribes after a read failure (the error card's retry). */
    fun retry() = observe()

    /** Drops one entry; the row leaves the list through the store's own emission. */
    fun remove(tid: Int) = write { repository.remove(tid) }

    /** Drops every entry; the page asks for confirmation before calling this. */
    fun clear() = write { repository.clear() }

    /**
     * Runs one store write off the composition, logging instead of crashing: the history is a
     * convenience list, so a failed write keeps the previous state rather than killing the screen.
     */
    private fun write(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "thread history write failed")
            }
        }
    }

    private fun observe() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.entries.isEmpty(), error = null) }
            try {
                repository.entries.collect { entries ->
                    _state.value = ForumThreadHistoryState(entries = entries, isLoading = false)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "thread history unreadable")
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "ForumThreadHistoryVM"
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.repository.ForumSearchHistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Exposes the recent-search keywords to the search surface and records new ones.
 *
 * The overlay ([ForumSearchOverlay]) and the result page ([ForumSearchPage]) both submit through
 * this view model, so the list always reflects the final keyword of a round trip, not just what was
 * typed into the overlay. Stored state lives in [ForumSearchHistoryRepository] (DataStore), the
 * view model only mirrors it for composition.
 */
class ForumSearchHistoryViewModel(
    private val repository: ForumSearchHistoryRepository,
) : ViewModel() {

    /** Most recent first; empty until the first stored list arrives. */
    val recent: StateFlow<List<String>> = repository.recent.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Records a submitted keyword; [submit] already ignores blank input, this repeats the guard. */
    fun record(keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch { repository.record(keyword) }
    }

    /** Clears the whole history (the 清除 action next to the recent list). */
    fun clear() {
        viewModelScope.launch { repository.clear() }
    }
}
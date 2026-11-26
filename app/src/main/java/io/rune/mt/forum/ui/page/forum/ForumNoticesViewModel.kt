// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumNotice
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** One tab's independently paged slice of the 提醒 inbox. */
data class NoticeListState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val notices: List<ForumNotice> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val error: String? = null,
    /** True after a load succeeded; keeps re-selecting a cached tab from re-fetching. */
    val loaded: Boolean = false,
) {
    val canLoadMore: Boolean get() = page < totalPages
}

/**
 * State of the 提醒 screen: one [NoticeListState] per tab plus the selected tab.
 *
 * Modelled on [ForumGuideState]: a tab switch shows that tab's own rows (never the previous
 * tab's), and re-selecting a cached tab is instant.
 */
data class ForumNoticesState(
    val selected: ForumNoticeTab = ForumNoticeTab.System,
    val tabs: Map<ForumNoticeTab, NoticeListState> = emptyMap(),
) {
    val current: NoticeListState
        get() = tabs[selected] ?: NoticeListState()
}

/**
 * Backs the 提醒 (notice inbox) screen.
 *
 * Each tab is a separate server page (`view=system|interactive|mypost`), so every tab owns its
 * own loading job and page counter; switching tabs must not cancel the other tab's request.
 */
class ForumNoticesViewModel(
    private val repository: ForumRepository,
) : ViewModel() {

    /** Session drives the login gate: the site answers the tab pages with the login form when anonymous. */
    val session = repository.session

    private val _state = MutableStateFlow(ForumNoticesState())
    val state: StateFlow<ForumNoticesState> = _state.asStateFlow()

    /** One in-flight job per tab. */
    private val jobs = mutableMapOf<ForumNoticeTab, Job>()

    fun loadIfNeeded() {
        if (!session.value.isLoggedIn) return
        val tab = _state.value.selected
        if (_state.value.tabs[tab]?.loaded == true) return
        loadPage(tab, page = 1, refresh = false)
    }

    /** Switches the visible tab; a tab whose page 1 is already cached renders instantly. */
    fun selectTab(tab: ForumNoticeTab) {
        if (_state.value.selected == tab) return
        _state.update { it.copy(selected = tab) }
        if (_state.value.tabs[tab]?.loaded == true) return
        loadPage(tab, page = 1, refresh = false)
    }

    /** Pull-to-refresh of the visible tab. */
    fun refresh() {
        loadPage(_state.value.selected, page = 1, refresh = true)
    }

    /** Appends the next page of the visible tab. */
    fun loadMore() {
        val current = _state.value.current
        if (!current.canLoadMore || current.isLoading || current.isRefreshing || current.isAppending) return
        loadPage(_state.value.selected, page = current.page + 1, refresh = false, append = true)
    }

    private fun loadPage(
        tab: ForumNoticeTab,
        page: Int,
        refresh: Boolean,
        append: Boolean = false,
    ) {
        // Anonymous users get the login wall from every tab page; the screen already shows its
        // login card, so a request here would only produce a pointless failure.
        if (!session.value.isLoggedIn) return
        jobs[tab]?.cancel()
        jobs[tab] = viewModelScope.launch {
            updateTab(tab) {
                it.copy(
                    isLoading = !refresh && !append,
                    isRefreshing = refresh,
                    isAppending = append,
                    error = null,
                )
            }
            runCatching { repository.getNotices(tab, page) }
                .onSuccess { result ->
                    Timber.tag(TAG).i(
                        "notices tab=%s page=%d/%d rows=%d",
                        tab.value,
                        result.page,
                        result.totalPages,
                        result.notices.size,
                    )
                    updateTab(tab) { previous ->
                        val merged = if (append) {
                            previous.notices + result.notices.filterNot { notice ->
                                previous.notices.any { it.id != null && it.id == notice.id }
                            }
                        } else {
                            result.notices
                        }
                        previous.copy(
                            notices = merged,
                            page = result.page,
                            totalPages = result.totalPages,
                            loaded = true,
                            isLoading = false,
                            isRefreshing = false,
                            isAppending = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "notices tab=%s page=%d failed", tab.value, page)
                    updateTab(tab) {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isAppending = false,
                            error = error.message ?: "加载失败",
                        )
                    }
                }
        }
    }

    /** Patches one tab's slice without disturbing the others. */
    private fun updateTab(tab: ForumNoticeTab, transform: (NoticeListState) -> NoticeListState) {
        _state.update { state ->
            val previous = state.tabs[tab] ?: NoticeListState()
            state.copy(tabs = state.tabs + (tab to transform(previous)))
        }
    }

    private companion object {
        const val TAG = "ForumNoticesVM"
    }
}
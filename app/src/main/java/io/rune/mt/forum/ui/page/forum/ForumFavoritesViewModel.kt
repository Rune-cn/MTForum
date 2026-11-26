// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** State of the 我的收藏 screen (one page at a time, with the page's formhash for deletion). */
data class ForumFavoritesState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val favorites: List<ForumFavorite> = emptyList(),
    /** Session formhash printed by the favourites page; null until a page has been parsed. */
    val formhash: String? = null,
    val page: Int = 1,
    val totalPages: Int = 1,
    val isAppending: Boolean = false,
    val error: String? = null,
    /** True when the site served the login form (signed out or the cookie expired). */
    val loginRequired: Boolean = false,
    /** Set when a removal was rejected so the screen can raise a toast. */
    val deleteFailed: Boolean = false,
    val loaded: Boolean = false,
) {
    val canLoadMore: Boolean get() = page < totalPages
}

/**
 * Backs the favourites page: paging, pull-to-refresh and optimistic removal.
 *
 * A removal hides the row immediately (the site confirms deletion slowly), then restores it at
 * its original position if the POST fails, so the list never silently loses an entry. The
 * `formhash` comes from the same page fetch — the favourites page prints it in its logout link —
 * which keeps deletion to a single round trip.
 */
class ForumFavoritesViewModel(
    private val repository: ForumRepository,
) : ViewModel() {

    val session = repository.session

    private val _state = MutableStateFlow(ForumFavoritesState())
    val state: StateFlow<ForumFavoritesState> = _state.asStateFlow()

    private var job: Job? = null
    private var deleteJob: Job? = null

    fun loadIfNeeded() {
        if (_state.value.loaded) return
        loadPage(page = 1, refresh = false)
    }

    fun refresh() {
        if (_state.value.isRefreshing) return
        loadPage(page = 1, refresh = true)
    }

    /** Appends the next page; used both by the footer button and the scroll listener. */
    fun loadMore() {
        val current = _state.value
        if (!current.canLoadMore || current.isLoading || current.isRefreshing || current.isAppending) return
        viewModelScope.launch {
            _state.update { it.copy(isAppending = true) }
            try {
                val next = current.page + 1
                val page = repository.getFavoritesPage(next)
                _state.update { state ->
                    val known = state.favorites.map { it.favid }.toSet()
                    state.copy(
                        isAppending = false,
                        favorites = state.favorites + page.favorites.filterNot { it.favid in known },
                        formhash = page.formhash ?: state.formhash,
                        page = page.page,
                        totalPages = page.totalPages,
                    )
                }
                Timber.tag(TAG).i("favorites page=%d appended", next)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                _state.update { it.copy(isAppending = false, loginRequired = true) }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "favorites append failed")
                _state.update { it.copy(isAppending = false, error = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    /**
     * Hides [favorite] right away and submits the two-step delete; on failure the row reappears
     * at its old index and [ForumFavoritesState.deleteFailed] triggers the screen's toast.
     */
    fun removeFavorite(favorite: ForumFavorite) {
        if (deleteJob?.isActive == true) return
        val current = _state.value
        val formhash = current.formhash ?: run {
            _state.update { it.copy(deleteFailed = true) }
            return
        }
        val index = current.favorites.indexOfFirst { it.favid == favorite.favid }
        if (index < 0) return
        _state.update {
            it.copy(
                favorites = it.favorites.filterNot { row -> row.favid == favorite.favid },
                deleteFailed = false,
            )
        }
        deleteJob = viewModelScope.launch {
            val removed = runCatching { repository.removeFavorite(favorite.favid, formhash) }
            val failed = removed.getOrNull() != true
            if (failed) {
                Timber.tag(TAG).w("favorite delete failed favid=%d", favorite.favid)
                _state.update { state ->
                    val rows = state.favorites.toMutableList()
                    if (rows.none { it.favid == favorite.favid }) {
                        rows.add(index.coerceAtMost(rows.size), favorite)
                    }
                    state.copy(favorites = rows, deleteFailed = true)
                }
            } else {
                Timber.tag(TAG).i("favorite deleted favid=%d", favorite.favid)
            }
        }
    }

    fun clearDeleteFailed() {
        _state.update { it.copy(deleteFailed = false) }
    }

    private fun loadPage(page: Int, refresh: Boolean) {
        if (!refresh && job?.isActive == true) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = !refresh && !it.loaded,
                    isRefreshing = refresh,
                    error = null,
                    loginRequired = false,
                )
            }
            try {
                val pageData = repository.getFavoritesPage(page)
                _state.value = ForumFavoritesState(
                    isLoading = false,
                    isRefreshing = false,
                    favorites = pageData.favorites,
                    formhash = pageData.formhash,
                    page = pageData.page,
                    totalPages = pageData.totalPages,
                    loaded = true,
                )
                Timber.tag(TAG).i(
                    "favorites ok count=%d pages=%d formhash=%s",
                    pageData.favorites.size,
                    pageData.totalPages,
                    pageData.formhash != null,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, loginRequired = true, loaded = true)
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "favorites failed")
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "ForumFavoritesVM"
    }
}
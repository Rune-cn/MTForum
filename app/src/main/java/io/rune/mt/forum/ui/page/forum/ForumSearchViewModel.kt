// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumThread
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

/**
 * State of the in-forum search screen.
 *
 * [searched] separates "nothing typed yet" from "the server returned no match", so the empty card
 * only shows after a real round trip. [loginRequired] is the site's own gate — search.php redirects
 * anonymous visitors to the login form — and drives the sign-in card instead of the error card.
 */
data class ForumSearchState(
    val keyword: String = "",
    val isSearching: Boolean = false,
    val isAppending: Boolean = false,
    val threads: List<ForumThread> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val error: String? = null,
    val loginRequired: Boolean = false,
    val searched: Boolean = false,
    /** Set by [ForumSearchViewModel.prepare] so a recomposition does not replay the route keyword. */
    val prepared: Boolean = false,
) {
    val canLoadMore: Boolean get() = page < totalPages && !isSearching && !isAppending
}

/**
 * Backs the in-forum search page.
 *
 * Discuz requires a signed-in account for search, so [search] short-circuits to the login card
 * instead of sending a request the site would only bounce to the login form. Pages 2+ reuse the
 * repository's server-side search id; the rows append, matching the board listing's behaviour.
 */
class ForumSearchViewModel(
    private val repository: ForumRepository,
) : ViewModel() {

    val session = repository.session

    private val _state = MutableStateFlow(ForumSearchState())
    val state: StateFlow<ForumSearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Seeds the field from `Route.ForumSearch(keyword)`; later recompositions keep what is typed.
     *
     * A non-blank keyword is the result of a confirmed search (the home overlay or a recent-search
     * tap), so the search runs immediately instead of leaving the user on a page that only *looks*
     * like a result page until they press 搜索 again. `prepared` keeps a recomposition from
     * replaying the route keyword, which would otherwise restart the request.
     */
    fun prepare(initialKeyword: String) {
        if (_state.value.prepared) return
        val seeded = if (_state.value.keyword.isBlank()) initialKeyword.trim() else _state.value.keyword
        _state.update { it.copy(keyword = seeded, prepared = true) }
        if (seeded.isNotEmpty()) search()
    }

    fun updateKeyword(value: String) {
        _state.update { it.copy(keyword = value, error = null, loginRequired = false) }
    }

    /** Runs page 1 for the current keyword; also the retry action of the error card. */
    fun search() {
        val keyword = _state.value.keyword.trim()
        if (keyword.isEmpty() || _state.value.isSearching) return
        if (!session.value.isLoggedIn) {
            _state.update {
                it.copy(keyword = keyword, searched = true, loginRequired = true, error = null)
            }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    keyword = keyword,
                    isSearching = true,
                    isAppending = false,
                    loginRequired = false,
                    error = null,
                )
            }
            try {
                Timber.tag(TAG).i("search keyword=%s", keyword)
                val result = repository.searchThreads(keyword, page = 1)
                _state.update {
                    it.copy(
                        isSearching = false,
                        threads = result.threads,
                        page = result.page,
                        totalPages = result.totalPages,
                        searched = true,
                    )
                }
                Timber.tag(TAG).i("search ok threads=%d pages=%d", result.threads.size, result.totalPages)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                Timber.tag(TAG).i("search keyword=%s needs login", keyword)
                _state.update { it.copy(isSearching = false, searched = true, loginRequired = true) }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "search keyword=%s failed", keyword)
                _state.update {
                    it.copy(
                        isSearching = false,
                        searched = true,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    /** Appends the next result page of the current keyword. */
    fun loadMore() {
        val current = _state.value
        if (!current.canLoadMore) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isAppending = true, error = null) }
            try {
                val next = current.page + 1
                Timber.tag(TAG).i("search keyword=%s page=%d append", current.keyword, next)
                val result = repository.searchThreads(current.keyword, page = next)
                val known = _state.value.threads.map { it.tid }.toSet()
                _state.update {
                    it.copy(
                        threads = it.threads + result.threads.filterNot { thread -> thread.tid in known },
                        page = result.page,
                        totalPages = result.totalPages,
                        isAppending = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                _state.update { it.copy(isAppending = false, loginRequired = true) }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "search append page=%d failed", current.page + 1)
                _state.update {
                    it.copy(
                        isAppending = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "ForumSearch"
    }
}
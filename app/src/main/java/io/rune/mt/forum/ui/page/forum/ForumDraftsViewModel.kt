// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumDraft
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
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

/** State of the 草稿 page: the site's draft list plus the paging/failure flags. */
data class ForumDraftsState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val drafts: List<ForumDraft> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val error: String? = null,
    /** True when the site served the login form (signed out or the cookie expired). */
    val loginRequired: Boolean = false,
    /** Set when a delete was rejected so the screen can raise its snackbar. */
    val deleteError: String? = null,
    /** tid of the draft currently being deleted (drives the row's spinner / row hiding). */
    val deletingTid: Int? = null,
    val loaded: Boolean = false,
) {
    val canLoadMore: Boolean get() = page < totalPages
}

/**
 * Backs the 草稿 page (`forum.php?mod=guide&view=my&type=thread&filter=save`).
 *
 * The list is the site's own draft store — the same drafts the 保存草稿 button creates — so this
 * screen is what makes drafts visible outside the composer. Rows open in the composer, which loads
 * the draft's edit form; deletion is confirmed by the screen and only removes the row once the
 * server accepted it (a failed delete keeps the row and reports the site's wording).
 */
class ForumDraftsViewModel(
    private val repository: ForumRepository,
    private val draftService: ForumDraftService,
) : ViewModel() {

    val session = repository.session

    private val _state = MutableStateFlow(ForumDraftsState())
    val state: StateFlow<ForumDraftsState> = _state.asStateFlow()

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

    fun loadMore() {
        val current = _state.value
        if (!current.canLoadMore || current.isLoading || current.isRefreshing || current.isAppending) return
        viewModelScope.launch {
            _state.update { it.copy(isAppending = true) }
            try {
                val next = current.page + 1
                val page = draftService.getDrafts(next)
                _state.update { state ->
                    val known = state.drafts.map { it.tid }.toSet()
                    state.copy(
                        isAppending = false,
                        drafts = state.drafts + page.drafts.filterNot { it.tid in known },
                        page = page.page,
                        totalPages = page.totalPages,
                    )
                }
                Timber.tag(TAG).i("drafts page=%d appended", next)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                _state.update { it.copy(isAppending = false, loginRequired = true) }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "drafts append failed")
                _state.update {
                    it.copy(isAppending = false, error = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * Deletes [draft] on the site.
     *
     * The edit form has to be read first because deletion replays its hidden field set
     * (`delete=1`); the row only disappears once the server accepted the write.
     */
    fun deleteDraft(draft: ForumDraft) {
        if (deleteJob?.isActive == true) return
        _state.update { it.copy(deletingTid = draft.tid, deleteError = null) }
        deleteJob = viewModelScope.launch {
            val result = runCatching {
                val edit = draftService.loadDraft(draft.tid, draft.fid, draft.pid)
                draftService.deleteDraft(edit.form)
            }.getOrElse { DraftActionResult.Failure(it.message ?: "删除草稿失败") }
            when (result) {
                is DraftActionResult.Success -> {
                    Timber.tag(TAG).i("draft deleted tid=%d", draft.tid)
                    _state.update { state ->
                        state.copy(
                            drafts = state.drafts.filterNot { it.tid == draft.tid },
                            deletingTid = null,
                        )
                    }
                }

                is DraftActionResult.Failure -> {
                    Timber.tag(TAG).w("draft delete tid=%d failed: %s", draft.tid, result.reason)
                    _state.update { it.copy(deletingTid = null, deleteError = result.reason) }
                }
            }
        }
    }

    fun clearDeleteError() = _state.update { it.copy(deleteError = null) }

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
                val pageData = draftService.getDrafts(page)
                _state.value = ForumDraftsState(
                    isLoading = false,
                    isRefreshing = false,
                    drafts = pageData.drafts,
                    page = pageData.page,
                    totalPages = pageData.totalPages,
                    loaded = true,
                )
                Timber.tag(TAG).i(
                    "drafts ok count=%d pages=%d",
                    pageData.drafts.size,
                    pageData.totalPages,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, loginRequired = true, loaded = true)
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "drafts failed")
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
        const val TAG = "ForumDraftsVM"
    }
}
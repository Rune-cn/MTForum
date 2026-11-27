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
 * The one card the 草稿 screen may show.
 *
 * Derived from the **load result**, never from the session flag alone: the cookie restore finishes
 * asynchronously after the first composition of a cold start, so the first fetch can be anonymous
 * while the account is in fact signed in. Rows the site returned are therefore always [Rows]; the
 * session only decides [LoginRequired] when nothing has been asked yet (or when the site itself
 * answered with the login form).
 */
enum class ForumDraftsPhase { Loading, LoginRequired, Error, Empty, Rows }

/** Maps a load state + the current session to the phase the screen renders. */
fun ForumDraftsState.phase(signedIn: Boolean): ForumDraftsPhase = when {
    // Data first: a page the site served is never hidden behind a flag that may still be settling.
    drafts.isNotEmpty() -> ForumDraftsPhase.Rows
    isLoading -> ForumDraftsPhase.Loading
    loginRequired -> ForumDraftsPhase.LoginRequired
    error != null -> ForumDraftsPhase.Error
    // Signed out and nothing has been asked yet: a login card, not "the box is empty".
    !signedIn && !loaded -> ForumDraftsPhase.LoginRequired
    loaded -> ForumDraftsPhase.Empty
    else -> ForumDraftsPhase.Loading
}

/**
 * Backs the 草稿 page (`forum.php?mod=guide&view=my&type=thread&filter=save`).
 *
 * The list is the site's own draft store — the same drafts the 保存草稿 button creates — so this
 * screen is what makes drafts visible outside the composer. Rows open in the composer, which loads
 * the draft's edit form; deletion is confirmed by the screen and only removes the row once the
 * server accepted it (a failed delete keeps the row and reports the site's wording).
 *
 * The screen renders [ForumDraftsPhase], never a boolean mixture of "signed in" and "loaded": a
 * page the site served is shown even when the session flag is still false, and a failure stays a
 * failure instead of degrading into "草稿箱是空的" (rule 26).
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

    /**
     * Loads page one unless a page already arrived.
     *
     * A "signed out" verdict is only valid for the session that produced it. The cookie restore is
     * asynchronous, so the first fetch of a cold start can be anonymous and leave `loginRequired`
     * set while the account is in fact signed in — without this re-check the screen would keep the
     * login card (and hide the drafts that were fetched all along) until the user pulled to refresh.
     */
    fun loadIfNeeded() {
        val current = _state.value
        if (current.loaded && !(current.loginRequired && session.value.isLoggedIn)) return
        loadPage(page = 1, refresh = false, showLoading = true)
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
     * (`delete=1`); the row only disappears once the server accepted the write. Reading that
     * form is also where the draft's `pid` comes from — the list row does not print it, and
     * Discuz's edit page answers 「帖子不存在」 without it (see
     * `ForumDraftServiceImpl.loadDraft`).
     */
    fun deleteDraft(draft: ForumDraft) {
        if (deleteJob?.isActive == true) return
        _state.update { it.copy(deletingTid = draft.tid, deleteError = null) }
        deleteJob = viewModelScope.launch {
            val result = try {
                val edit = draftService.loadDraft(draft.tid, draft.fid, draft.pid)
                draftService.deleteDraft(edit.form)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                // The cookie expired between listing and deleting: show the login card again
                // instead of the generic failure text.
                _state.update { it.copy(deletingTid = null, loginRequired = true, drafts = emptyList()) }
                return@launch
            } catch (error: Exception) {
                DraftActionResult.Failure(error.message ?: "删除草稿失败")
            }
            when (result) {
                is DraftActionResult.Success -> {
                    Timber.tag(TAG).i("draft deleted tid=%d", draft.tid)
                    _state.update { state ->
                        state.copy(
                            drafts = state.drafts.filterNot { it.tid == draft.tid },
                            deletingTid = null,
                        )
                    }
                    // The local row removal is only a claim: ask the site for page one again so
                    // the screen shows what the account really has. Without this the row could be
                    // gone from the app while the draft still exists (or come back after a
                    // pull-to-refresh with no explanation).
                    refreshAfterDelete(deletedTid = draft.tid)
                }

                is DraftActionResult.Failure -> {
                    Timber.tag(TAG).w("draft delete tid=%d failed: %s", draft.tid, result.reason)
                    _state.update { it.copy(deletingTid = null, deleteError = result.reason) }
                }
            }
        }
    }

    /**
     * Re-reads the list after an accepted delete, keeping the current screen if that read fails.
     *
     * A failed refresh must not resurrect the deleted row (the server already accepted the
     * write); the pull-to-refresh path stays available.
     */
    private suspend fun refreshAfterDelete(deletedTid: Int) {
        val page = runCatching { draftService.getDrafts(1) }.getOrElse { error ->
            if (error is CancellationException) throw error
            Timber.tag(TAG).w(error, "drafts refresh after delete failed")
            return
        }
        if (page.drafts.any { it.tid == deletedTid }) {
            Timber.tag(TAG).w("draft tid=%d still listed after a successful delete", deletedTid)
        }
        _state.update { state ->
            state.copy(
                drafts = page.drafts,
                page = page.page,
                totalPages = page.totalPages,
                loaded = true,
            )
        }
    }

    fun clearDeleteError() = _state.update { it.copy(deleteError = null) }

    private fun loadPage(page: Int, refresh: Boolean, showLoading: Boolean = !refresh) {
        // A newer request supersedes the older one: the session can be restored while the first
        // (anonymous) fetch is still in flight, and that fetch's "login required" verdict must not
        // win over the signed-in request that replaced it.
        job?.cancel()
        job = viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = showLoading,
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
                    "drafts ok page=%d rows=%d pages=%d signedIn=%s",
                    page,
                    pageData.drafts.size,
                    pageData.totalPages,
                    session.value.isLoggedIn,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                Timber.tag(TAG).i(
                    "drafts login required page=%d signedIn=%s",
                    page,
                    session.value.isLoggedIn,
                )
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, loginRequired = true, loaded = true)
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "drafts failed page=%d", page)
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
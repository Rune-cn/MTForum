// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** State of the board index (版块宫格) plus the hot-thread preview. */
data class ForumHomeState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val forums: List<Forum> = emptyList(),
    val hotThreads: List<ForumThread> = emptyList(),
    val error: String? = null,
    /** True after a successful load; keeps re-entry from re-fetching the index. */
    val loaded: Boolean = false,
)
/** State of one board's thread listing. */
data class ForumListState(
    val fid: Int = 0,
    val forumName: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val threads: List<ForumThread> = emptyList(),
    val notices: List<ForumThread> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val error: String? = null,
    /** Set once a load succeeded, used to avoid re-fetching on tab re-entry. */
    val loaded: Boolean = false,
) {
    val canLoadMore: Boolean get() = page < totalPages
}

/** State of a thread: the floors loaded so far. */
data class ForumThreadState(
    val tid: Int = 0,
    val fid: Int? = null,
    val title: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val posts: List<ForumPost> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val formhash: String? = null,
    val noticeAuthor: String? = null,
    val error: String? = null,
) {
    val canLoadMore: Boolean get() = page < totalPages
}

/**
 * Backs the three forum screens.
 *
 * The three state groups live side by side because the screens form one navigation chain
 * (home -> board -> thread) and share a repository; keeping them together means walking back
 * does not re-fetch the board index. Page 2+ of a listing or thread is appended instead of
 * replacing the list, so "load more" behaves like the site's own paging.
 */
class ForumViewModel(
    private val repository: ForumRepository,
) : ViewModel() {

    /** Session is read for the reply composer (login gate + author identity). */
    val session = repository.session

    private val _homeState = MutableStateFlow(ForumHomeState())
    val homeState: StateFlow<ForumHomeState> = _homeState.asStateFlow()

    private val _listState = MutableStateFlow(ForumListState())
    val listState: StateFlow<ForumListState> = _listState.asStateFlow()

    private val _threadState = MutableStateFlow(ForumThreadState())
    val threadState: StateFlow<ForumThreadState> = _threadState.asStateFlow()

    private var homeJob: Job? = null
    private var listJob: Job? = null
    private var threadJob: Job? = null
    /** Loads the board index once per VM; a manual refresh always re-fetches. */
    fun loadHomeIfNeeded() {
        if (_homeState.value.loaded) return
        loadHome(refresh = false)
    }

    fun refreshHome() = loadHome(refresh = true)

    fun loadHome(refresh: Boolean) {
        if (!refresh && homeJob?.isActive == true) return
        if (refresh && _homeState.value.isRefreshing) return
        homeJob = viewModelScope.launch {
            _homeState.update {
                it.copy(
                    isLoading = !refresh && !it.loaded,
                    isRefreshing = refresh,
                    error = null,
                )
            }
            try {
                Timber.tag(TAG).i("loadHome refresh=%s", refresh)
                val forums = repository.getForums()
                // The hot list is decoration: failing it must not blank the board grid.
                val hot = runCatching { repository.getHotThreads() }
                    .onFailure { e -> Timber.tag(TAG).w(e, "loadHotThreads failed, ignoring") }
                    .getOrDefault(emptyList())
                _homeState.value = ForumHomeState(
                    isLoading = false,
                    forums = forums,
                    hotThreads = hot,
                    loaded = true,
                )
                Timber.tag(TAG).i("loadHome ok forums=%d hot=%d", forums.size, hot.size)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "loadHome failed")
                _homeState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }
    /** Opens a board; the listing resets only when the board actually changes. */
    fun openForum(fid: Int, forumName: String) {
        val current = _listState.value
        if (current.fid != fid) {
            _listState.value = ForumListState(fid = fid, forumName = forumName)
        } else {
            _listState.update { it.copy(forumName = forumName) }
        }
        if (current.fid == fid && current.threads.isNotEmpty()) {
            // Same board already loaded: keep the rows on screen instead of flashing a spinner.
            return
        }
        listJob?.cancel()
        loadForumPage(fid = fid, forumName = forumName, page = 1, refresh = false)
    }

    fun refreshForum() {
        val state = _listState.value
        if (state.fid == 0) return
        listJob?.cancel()
        loadForumPage(state.fid, state.forumName, page = 1, refresh = true)
    }

    /** Appends the next page of the current board listing. */
    fun loadMoreThreads() {
        val state = _listState.value
        if (!state.canLoadMore || state.isLoading || state.isRefreshing || state.isAppending) return
        listJob = viewModelScope.launch {
            _listState.update { it.copy(isAppending = true) }
            try {
                val next = state.page + 1
                Timber.tag(TAG).i("loadForumPage fid=%d page=%d append", state.fid, next)
                val pageData = repository.getThreads(state.fid, next)
                val known = _listState.value.threads.map { it.tid }.toSet()
                _listState.update {
                    it.copy(
                        threads = it.threads + pageData.threads.filterNot { thread -> thread.tid in known },
                        page = pageData.page,
                        totalPages = pageData.totalPages,
                        isAppending = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "loadMoreThreads fid=%d failed", state.fid)
                _listState.update {
                    it.copy(isAppending = false, error = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private fun loadForumPage(fid: Int, forumName: String, page: Int, refresh: Boolean) {
        listJob = viewModelScope.launch {
            _listState.update {
                it.copy(
                    fid = fid,
                    forumName = forumName,
                    isLoading = !refresh && it.threads.isEmpty(),
                    isRefreshing = refresh,
                    error = null,
                )
            }
            try {
                Timber.tag(TAG).i("loadForumPage fid=%d page=%d refresh=%s", fid, page, refresh)
                val pageData = repository.getThreads(fid, page)
                _listState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isAppending = false,
                        threads = pageData.threads,
                        notices = pageData.notice,
                        page = pageData.page,
                        totalPages = pageData.totalPages,
                    )
                }
                Timber.tag(TAG).i(
                    "loadForumPage ok fid=%d threads=%d notices=%d pages=%d",
                    fid,
                    pageData.threads.size,
                    pageData.notice.size,
                    pageData.totalPages,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "loadForumPage fid=%d failed", fid)
                _listState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isAppending = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }
    /** Opens a thread; the floors reset only when the thread actually changes. */
    fun openThread(tid: Int, title: String) {
        val current = _threadState.value
        if (current.tid != tid) {
            _threadState.value = ForumThreadState(tid = tid, title = title)
        } else {
            _threadState.update { it.copy(title = title.ifBlank { it.title }) }
        }
        if (current.tid == tid && current.posts.isNotEmpty()) {
            // Same thread already loaded: keep the floors on screen.
            return
        }
        threadJob?.cancel()
        loadThreadPage(tid = tid, page = 1, refresh = false)
    }

    fun refreshThread() {
        val state = _threadState.value
        if (state.tid == 0) return
        threadJob?.cancel()
        loadThreadPage(state.tid, page = 1, refresh = true)
    }

    /** Appends the next page of floors to the thread. */
    fun loadMorePosts() {
        val state = _threadState.value
        if (!state.canLoadMore || state.isLoading || state.isRefreshing || state.isAppending) return
        threadJob = viewModelScope.launch {
            _threadState.update { it.copy(isAppending = true) }
            try {
                val next = state.page + 1
                Timber.tag(TAG).i("loadThreadPage tid=%d page=%d append", state.tid, next)
                val detail = repository.getThreadDetail(state.tid, next)
                val known = _threadState.value.posts.map { it.pid }.toSet()
                _threadState.update {
                    it.copy(
                        posts = it.posts + detail.posts.filterNot { post -> post.pid in known },
                        page = detail.page,
                        totalPages = detail.totalPages,
                        formhash = detail.formhash ?: it.formhash,
                        noticeAuthor = detail.noticeAuthor ?: it.noticeAuthor,
                        isAppending = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "loadMorePosts tid=%d failed", state.tid)
                _threadState.update {
                    it.copy(isAppending = false, error = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }


    // ---- Reply composer -----------------------------------------------------
    private val _replyState = MutableStateFlow(ReplyState())
    val replyState: StateFlow<ReplyState> = _replyState.asStateFlow()

    /** Opens/closes the composer; the form credentials come from the loaded thread. */
    /** Opens the composer, optionally quoting the floor the user tapped 回复 on. */
    fun requestReply(quote: ForumPost? = null) {
        val thread = _threadState.value
        if (session.value.user == null) {
            _replyState.value = ReplyState(error = "请先登录后再回复")
            return
        }
        if (thread.formhash == null) {
            _replyState.value = ReplyState(error = "帖子尚未加载完成，请稍候")
            return
        }
        _replyState.value = ReplyState(
            visible = true,
            quoteAuthor = quote?.authorName,
            quoteText = quote?.contentHtml?.let { ForumBodyParser.plainText(it).take(120) },
        )
    }

    fun dismissReply() {
        _replyState.value = _replyState.value.copy(visible = false, error = null)
    }

    fun updateReplyText(text: String) {
        _replyState.value = _replyState.value.copy(text = text, error = null)
    }

    fun submitReply() {
        val composer = _replyState.value
        val thread = _threadState.value
        if (composer.text.isBlank() || composer.isSubmitting) return
        val formhash = thread.formhash ?: return
        viewModelScope.launch {
            _replyState.value = composer.copy(isSubmitting = true, error = null)
            val result = runCatching {
                repository.reply(
                    tid = thread.tid,
                    fid = thread.fid ?: 0,
                    message = buildString {
                        if (composer.quoteAuthor != null) {
                            append("[quote][b]").append(composer.quoteAuthor).append("[/b]\n")
                            append(composer.quoteText.orEmpty()).append("[/quote]\n")
                        }
                        append(composer.text.trim())
                    },
                    formhash = formhash,
                    noticeAuthor = thread.noticeAuthor,
                )
            }
            result.onSuccess {
                Timber.tag(TAG).i("reply sent tid=%d", thread.tid)
                _replyState.value = ReplyState()
                refreshThread()
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "reply failed tid=%d", thread.tid)
                _replyState.value = composer.copy(
                    isSubmitting = false,
                    error = error.message ?: "回复失败",
                )
            }
        }
    }


    // ---- Thread quick actions (收藏 / 顶赞 / 关注楼主) ------------------------
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    private fun authorUidOfThread(): Int? =
        _threadState.value.posts.firstOrNull { it.isOp }?.authorUid
            ?: _threadState.value.posts.firstOrNull()?.authorUid

    /** Adds the open thread to favourites (the site has no "un-favourite" here). */
    fun favoriteCurrentThread() {
        val thread = _threadState.value
        if (session.value.user == null) {
            _actionMessage.value = "请先登录"
            return
        }
        val formhash = thread.formhash ?: run {
            _actionMessage.value = "帖子尚未加载完成"
            return
        }
        viewModelScope.launch {
            runCatching { repository.addFavorite(thread.tid, formhash) }
                .onSuccess { ok -> _actionMessage.value = if (ok) "已收藏本帖" else "收藏失败" }
                .onFailure { e -> _actionMessage.value = e.message ?: "收藏失败" }
        }
    }

    /** 顶/推荐 the open thread; this is irreversible on the site. */
    fun recommendCurrentThread() {
        val thread = _threadState.value
        if (session.value.user == null) {
            _actionMessage.value = "请先登录"
            return
        }
        val formhash = thread.formhash ?: run {
            _actionMessage.value = "帖子尚未加载完成"
            return
        }
        viewModelScope.launch {
            runCatching { repository.recommendThread(thread.tid, formhash) }
                .onSuccess { ok -> _actionMessage.value = if (ok) "已推荐本帖" else "推荐失败" }
                .onFailure { e -> _actionMessage.value = e.message ?: "推荐失败" }
        }
    }

    /** Follows the OP of the open thread. */
    fun followCurrentThreadAuthor() {
        if (session.value.user == null) {
            _actionMessage.value = "请先登录"
            return
        }
        val uid = authorUidOfThread() ?: run {
            _actionMessage.value = "楼主信息缺失"
            return
        }
        val formhash = _threadState.value.formhash ?: run {
            _actionMessage.value = "帖子尚未加载完成"
            return
        }
        viewModelScope.launch {
            runCatching { repository.followUser(uid, formhash) }
                .onSuccess { ok -> _actionMessage.value = if (ok) "已关注楼主" else "关注失败" }
                .onFailure { e -> _actionMessage.value = e.message ?: "关注失败" }
        }
    }

    // ---- Guide (导读) ---------------------------------------------------------
    private val _guideState = MutableStateFlow(ForumListState())
    val guideState: StateFlow<ForumListState> = _guideState.asStateFlow()

    fun loadGuideIfNeeded() {
        if (_guideState.value.loaded) return
        loadGuidePage(page = 1, refresh = false)
    }

    fun refreshGuide() = loadGuidePage(page = 1, refresh = true)

    fun loadMoreGuide() {
        val current = _guideState.value
        if (current.isAppending || current.page >= current.totalPages) return
        loadGuidePage(page = current.page + 1, refresh = false, append = true)
    }

    private fun loadGuidePage(page: Int, refresh: Boolean, append: Boolean = false) {
        viewModelScope.launch {
            _guideState.update {
                it.copy(
                    isLoading = !refresh && !append,
                    isRefreshing = refresh,
                    isAppending = append,
                    error = null,
                )
            }
            runCatching { repository.getHotThreads(page) }
                .onSuccess { threads ->
                    _guideState.update { prev ->
                        val merged = if (append) prev.threads + threads.filterNot { t -> prev.threads.any { it.tid == t.tid } } else threads
                        prev.copy(
                            threads = merged,
                            page = page,
                            // The guide endpoint reports no reliable page count, so keep paging
                            // while the server still returns a full page.
                            totalPages = if (threads.size >= 40) page + 1 else page,
                            loaded = true,
                            isLoading = false,
                            isRefreshing = false,
                            isAppending = false,
                        )
                    }
                }
                .onFailure { error ->
                    Timber.tag(TAG).e(error, "loadGuidePage(%d) failed", page)
                    _guideState.update {
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

    private fun loadThreadPage(tid: Int, page: Int, refresh: Boolean) {
        threadJob = viewModelScope.launch {
            _threadState.update {
                it.copy(
                    tid = tid,
                    isLoading = !refresh && it.posts.isEmpty(),
                    isRefreshing = refresh,
                    error = null,
                )
            }
            try {
                Timber.tag(TAG).i("loadThreadPage tid=%d page=%d refresh=%s", tid, page, refresh)
                val detail = repository.getThreadDetail(tid, page)
                _threadState.update {
                    it.copy(
                        tid = detail.tid,
                        title = detail.title.ifBlank { it.title },
                        isLoading = false,
                        isRefreshing = false,
                        isAppending = false,
                        posts = detail.posts,
                        page = detail.page,
                        totalPages = detail.totalPages,
                        formhash = detail.formhash,
                        noticeAuthor = detail.noticeAuthor,
                    )
                }
                Timber.tag(TAG).i(
                    "loadThreadPage ok tid=%d posts=%d pages=%d",
                    tid,
                    detail.posts.size,
                    detail.totalPages,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "loadThreadPage tid=%d failed", tid)
                _threadState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isAppending = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "ForumViewModel"
    }
}

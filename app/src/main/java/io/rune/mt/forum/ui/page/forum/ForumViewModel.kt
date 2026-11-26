// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumCategory
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHotEntry
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** State of the board index (版块宫格) plus the site's 热帖排行 block. */
data class ForumHomeState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val forums: List<Forum> = emptyList(),
    /** Already ranked by the site; never re-sorted, never truncated. */
    val hotRanking: List<ForumHotEntry> = emptyList(),
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
    /** 主题分类 tabs of the board; empty when the board has none. */
    val categories: List<ForumCategory> = emptyList(),
    /** Selected 主题分类 (`null` = 全部); every fetch of this board carries it. */
    val typeId: Int? = null,
    val page: Int = 1,
    val totalPages: Int = 1,
    val error: String? = null,
    /** Set once a load succeeded, used to avoid re-fetching on tab re-entry. */
    val loaded: Boolean = false,
) {
    val canLoadMore: Boolean get() = page < totalPages
}

/**
 * State of the 导读 screen: one independently paged [ForumListState] per view plus the selected
 * tab. Keeping a map (instead of one shared list) is what stops a tab switch from flashing the
 * previous tab's rows, and what makes re-selecting a cached view instant.
 */
data class ForumGuideState(
    val selected: ForumGuideView = ForumGuideView.Latest,
    val views: Map<ForumGuideView, ForumListState> = emptyMap(),
) {
    val current: ForumListState
        get() = views[selected] ?: ForumListState()
}

/** State of a thread: the floors loaded so far. */
data class ForumThreadState(
    val tid: Int = 0,
    val fid: Int? = null,
    val title: String = "",
    /** Board the thread belongs to, read from the thread page breadcrumb (null while loading). */
    val forumName: String? = null,
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
                val home = repository.getHome()
                _homeState.value = ForumHomeState(
                    isLoading = false,
                    forums = home.forums,
                    hotRanking = home.hotRanking,
                    loaded = true,
                )
                Timber.tag(TAG).i("loadHome ok forums=%d ranking=%d", home.forums.size, home.hotRanking.size)
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
            // Same board already loaded: keep the rows on screen — and the 主题分类 the user
            // picked — instead of flashing a spinner.
            return
        }
        listJob?.cancel()
        loadForumPage(
            fid = fid,
            forumName = forumName,
            // Re-entering the same board keeps its category; a different board starts at 全部.
            typeId = current.typeId.takeIf { current.fid == fid },
            page = 1,
            refresh = false,
        )
    }

    /**
     * Switches the 主题分类 of the open board.
     *
     * The site filters server-side, so the rows cannot be narrowed client-side: the page-1
     * request is repeated with `typeid`, and the previous board's rows are dropped first so a
     * filtered result never shows unfiltered rows underneath while it loads. `null` is 全部.
     */
    fun selectCategory(typeId: Int?) {
        val state = _listState.value
        if (state.fid == 0 || state.typeId == typeId) return
        listJob?.cancel()
        _listState.update {
            it.copy(
                typeId = typeId,
                threads = emptyList(),
                notices = emptyList(),
                page = 1,
                totalPages = 1,
                isLoading = true,
                isAppending = false,
                error = null,
            )
        }
        loadForumPage(state.fid, state.forumName, typeId = typeId, page = 1, refresh = false)
    }

    fun refreshForum() {
        val state = _listState.value
        if (state.fid == 0) return
        listJob?.cancel()
        loadForumPage(state.fid, state.forumName, typeId = state.typeId, page = 1, refresh = true)
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
                val pageData = repository.getThreads(state.fid, next, state.typeId)
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

    private fun loadForumPage(
        fid: Int,
        forumName: String,
        typeId: Int?,
        page: Int,
        refresh: Boolean,
    ) {
        listJob = viewModelScope.launch {
            _listState.update {
                it.copy(
                    fid = fid,
                    forumName = forumName,
                    typeId = typeId,
                    isLoading = !refresh && it.threads.isEmpty(),
                    isRefreshing = refresh,
                    error = null,
                )
            }
            try {
                Timber.tag(TAG).i("loadForumPage fid=%d typeId=%s page=%d refresh=%s", fid, typeId, page, refresh)
                val pageData = repository.getThreads(fid, page, typeId)
                _listState.update { current ->
                    current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isAppending = false,
                        threads = pageData.threads,
                        notices = pageData.notice,
                        // Page 2+ repeats the same strip; an empty answer (e.g. a view without
                        // categories) must not wipe the tabs the first page already delivered.
                        categories = pageData.categories.ifEmpty { current.categories },
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
                enrichNoticeAuthors()
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
    /** OP floor of a 置顶 announcement, resolved once per tid from its thread page. */
    private val noticeAuthors = mutableMapOf<Int, ForumPost>()

    private var noticeAuthorJob: Job? = null

    /**
     * Fills the byline of 置顶 rows.
     *
     * The announcement strip prints only `<a><em>置顶</em>title</a>` — no author, avatar or time —
     * on every board (verified across 30+ boards on 2026-11-26), so the listing alone can never
     * show who posted it. Each missing row is resolved once from its thread page (the OP floor
     * carries the byline) and patched into the current list. Failures leave the row title-only and
     * the card falls back to a pin glyph instead of a blank "?" avatar.
     */
    private fun enrichNoticeAuthors() {
        val state = _listState.value
        val pending = state.notices.filter {
            it.tid > 0 && it.authorName.isBlank() && noticeAuthors[it.tid] == null
        }
        if (pending.isEmpty()) return
        noticeAuthorJob?.cancel()
        noticeAuthorJob = viewModelScope.launch {
            val resolved = coroutineScope {
                pending.map { thread ->
                    async {
                        val post = runCatching { repository.getThreadDetail(thread.tid, 1).posts.firstOrNull() }
                            .getOrNull()
                        if (post != null && post.authorName.isNotBlank()) {
                            noticeAuthors[thread.tid] = post
                            true
                        } else {
                            false
                        }
                    }
                }.awaitAll()
            }
            Timber.tag(TAG).i("notice authors resolved %d/%d", resolved.count { it }, pending.size)
            _listState.update { current ->
                current.copy(
                    notices = current.notices.map { thread ->
                        val post = noticeAuthors[thread.tid]
                        if (thread.authorName.isBlank() && post != null) {
                            thread.copy(
                                authorName = post.authorName,
                                authorUid = post.authorUid,
                                authorAvatarUrl = post.authorAvatarUrl,
                                authorLevel = post.authorLevel,
                            )
                        } else {
                            thread
                        }
                    },
                )
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
                        forumName = detail.forumName ?: it.forumName,
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
            _replyState.value = ReplyState(errorRes = R.string.login_required_message)
            return
        }
        if (thread.formhash == null) {
            _replyState.value = ReplyState(errorRes = R.string.reply_error_thread_not_ready)
            return
        }
        _replyState.value = ReplyState(
            visible = true,
            quoteAuthor = quote?.authorName,
            quoteText = quote?.contentHtml?.let { ForumBodyParser.plainText(it).take(120) },
        )
    }

    fun dismissReply() {
        _replyState.value = _replyState.value.copy(visible = false, error = null, errorRes = null)
    }

    fun updateReplyText(text: String) {
        _replyState.value = _replyState.value.copy(text = text, error = null, errorRes = null)
    }

    fun submitReply() {
        val composer = _replyState.value
        val thread = _threadState.value
        if (composer.text.isBlank() || composer.isSubmitting) return
        val formhash = thread.formhash ?: return
        viewModelScope.launch {
            _replyState.value = composer.copy(isSubmitting = true, error = null, errorRes = null)
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
                    error = error.message,
                    errorRes = if (error.message == null) R.string.reply_error_failed else null,
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
    private val _guideState = MutableStateFlow(ForumGuideState())
    val guideState: StateFlow<ForumGuideState> = _guideState.asStateFlow()

    /** One in-flight job per view: switching tabs must not cancel the other view's request. */
    private val guideJobs = mutableMapOf<ForumGuideView, Job>()

    fun loadGuideIfNeeded() {
        val view = _guideState.value.selected
        if (_guideState.value.views[view]?.loaded == true) return
        loadGuidePage(view, page = 1, refresh = false)
    }

    /** Switches the visible tab; a view whose page 1 is already cached renders instantly. */
    fun selectGuideView(view: ForumGuideView) {
        if (_guideState.value.selected == view) return
        _guideState.update { it.copy(selected = view) }
        if (_guideState.value.views[view]?.loaded == true) return
        loadGuidePage(view, page = 1, refresh = false)
    }

    fun refreshGuide() {
        val view = _guideState.value.selected
        loadGuidePage(view, page = 1, refresh = true)
    }

    fun loadMoreGuide() {
        val state = _guideState.value
        val current = state.current
        if (current.isAppending || current.page >= current.totalPages) return
        loadGuidePage(state.selected, page = current.page + 1, refresh = false, append = true)
    }

    private fun loadGuidePage(
        view: ForumGuideView,
        page: Int,
        refresh: Boolean,
        append: Boolean = false,
    ) {
        guideJobs[view]?.cancel()
        guideJobs[view] = viewModelScope.launch {
            updateGuideView(view) {
                it.copy(
                    isLoading = !refresh && !append,
                    isRefreshing = refresh,
                    isAppending = append,
                    error = null,
                )
            }
            runCatching { repository.getGuidePage(view, page) }
                .onSuccess { result ->
                    val threads = result.threads
                    Timber.tag(TAG).i(
                        "loadGuidePage view=%s page=%d/%d threads=%d",
                        view.value,
                        page,
                        result.totalPages,
                        threads.size,
                    )
                    updateGuideView(view) { prev ->
                        val merged = if (append) {
                            prev.threads + threads.filterNot { thread -> prev.threads.any { it.tid == thread.tid } }
                        } else {
                            threads
                        }
                        prev.copy(
                            threads = merged,
                            page = page,
                            // The pager is the site's own count (`/ 12 页`); 抢沙发 prints none, so
                            // it stays a single page. A truncated pager (no anchor carrying the
                            // last number) still falls back to the "full page implies another"
                            // heuristic, which cannot over-page past the real list.
                            totalPages = if (result.totalPages > 1) {
                                result.totalPages
                            } else if (threads.size >= GUIDE_FULL_PAGE) {
                                page + 1
                            } else {
                                page
                            },
                            loaded = true,
                            isLoading = false,
                            isRefreshing = false,
                            isAppending = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "loadGuidePage view=%s page=%d failed", view.value, page)
                    updateGuideView(view) {
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

    /** Patches one view's slice without disturbing the other tabs. */
    private fun updateGuideView(view: ForumGuideView, transform: (ForumListState) -> ForumListState) {
        _guideState.update { state ->
            val previous = state.views[view] ?: ForumListState()
            state.copy(views = state.views + (view to transform(previous)))
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
                        forumName = detail.forumName ?: it.forumName,
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

        /** A full 导读 page holds 50 rows; anything shorter is the last page (sofa: 12). */
        const val GUIDE_FULL_PAGE = 40
    }
}

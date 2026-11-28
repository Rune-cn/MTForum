// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.filter.CommentFilterMatcher
import io.rune.mt.forum.domain.forum.model.CommentFilterSettings
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumBoardPreferences
import io.rune.mt.forum.domain.forum.model.ForumCategory
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHotEntry
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumReplyDraft
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.arrangeForums
import io.rune.mt.forum.domain.forum.repository.CommentFilterRepository
import io.rune.mt.forum.domain.forum.repository.ForumBoardPreferencesRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.forum.repository.NoopCommentFilterRepository
import io.rune.mt.forum.domain.forum.repository.NoopForumBoardPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    /**
     * The home page's 最新主题 feed, page by page.
     *
     * The 热帖排行 above it is a **server top-10**, so it can never be a complete list; the site's
     * own home page continues below it with a thread stream that loads page after page. These rows
     * are that stream (the 导读 `view=newthread` listing, in the server's order), appended page by
     * page like every other paged list in the app.
     */
    val latestThreads: List<ForumThread> = emptyList(),
    /** Page of [latestThreads] already in hand; `0` until the first page arrives. */
    val latestPage: Int = 0,
    val latestTotalPages: Int = 0,
    val isAppendingLatest: Boolean = false,
    val latestError: String? = null,
    /** True once the feed's first page arrived; drives the footer, not the index spinner. */
    val latestLoaded: Boolean = false,
    val error: String? = null,
    /** True after a successful load; keeps re-entry from re-fetching the index. */
    val loaded: Boolean = false,
) {
    val canLoadMoreLatest: Boolean get() = latestLoaded && latestPage < latestTotalPages
}
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
    /** People who 推荐ed (顶) the thread; read from the OP's recommend strip, page 1 only. */
    val recommendCount: Int = 0,
    val error: String? = null,
) {
    val canLoadMore: Boolean get() = page < totalPages
}

/**
 * What the thread page actually renders after the comment filter is applied.
 *
 * [posts] is the *visible* floor list (filtered) and [hiddenCount] is how many floors the rules
 * matched — that count is what the "N 条被屏蔽" footer reports. Matched floors are hidden
 * permanently (the rules are a local setting), so there is no temporary "show blocked" state and
 * no way to bring them back from the thread page; [hiddenCount] only explains the gap in floor
 * numbering. The filtering itself lives in the ViewModel, not in the LazyColumn item loop: each
 * rule's regex is compiled once per settings revision and each floor's plain text is cached by
 * pid, so the UI list path only walks already-computed strings.
 */
data class ThreadDisplayState(
    val posts: List<ForumPost> = emptyList(),
    val hiddenCount: Int = 0,
)

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
    private val drafts: ForumDraftRepository,
    private val draftService: ForumDraftService,
    /** Local comment (楼层) filter rules. Defaults to a no-op so existing callers/tests keep working. */
    private val commentFilter: CommentFilterRepository = NoopCommentFilterRepository,
    /** Local board order/hide customisation; defaults to "server order, nothing hidden". */
    private val boardPreferences: ForumBoardPreferencesRepository = NoopForumBoardPreferencesRepository,
) : ViewModel() {

    /** Session is read for the reply composer (login gate + author identity). */
    val session = repository.session

    private val _homeState = MutableStateFlow(ForumHomeState())
    val homeState: StateFlow<ForumHomeState> = _homeState.asStateFlow()

    private val _listState = MutableStateFlow(ForumListState())
    val listState: StateFlow<ForumListState> = _listState.asStateFlow()

    private val _threadState = MutableStateFlow(ForumThreadState())
    val threadState: StateFlow<ForumThreadState> = _threadState.asStateFlow()

    // ---- Comment filter runtime ------------------------------------------
    /** Settings + a matcher compiled once per revision (never per row). */
    private data class FilterRuntime(
        val settings: CommentFilterSettings,
        val matcher: CommentFilterMatcher,
    )

    private val _filterRuntime = MutableStateFlow(FilterRuntime(CommentFilterSettings(), CommentFilterMatcher.EMPTY))
    /** Plain text of each floor, keyed by pid, so filtering never re-parses HTML. */
    private val plainTextCache = HashMap<Long, String>()

    init {
        viewModelScope.launch {
            commentFilter.settings.collect { settings ->
                _filterRuntime.value = FilterRuntime(
                    settings = settings,
                    matcher = if (settings.masterEnabled) {
                        CommentFilterMatcher.compile(settings.rules)
                    } else {
                        CommentFilterMatcher.EMPTY
                    },
                )
            }
        }
    }

    /** The filtered floor list + the "N 条被屏蔽" count the thread page renders. */
    val threadDisplay: StateFlow<ThreadDisplayState> =
        combine(_threadState, _filterRuntime) { state, runtime ->
            buildThreadDisplay(state.posts, runtime)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ThreadDisplayState())

    private fun buildThreadDisplay(
        posts: List<ForumPost>,
        runtime: FilterRuntime,
    ): ThreadDisplayState {
        val settings = runtime.settings
        val matcher = runtime.matcher
        if (!settings.masterEnabled || matcher.ruleCount == 0) {
            return ThreadDisplayState(posts = posts, hiddenCount = 0)
        }
        val hiddenIds = HashSet<Long>()
        for (post in posts) {
            // The OP is kept unless the user opted into "也过滤楼主".
            if (post.isOp && !settings.filterOp) continue
            if (matcher.isBlocked(plainTextOf(post))) hiddenIds += post.pid
        }
        // Blocked floors stay hidden: the rules are a local setting, not a per-view filter.
        val shown = posts.filterNot { it.pid in hiddenIds }
        return ThreadDisplayState(posts = shown, hiddenCount = hiddenIds.size)
    }

    private fun plainTextOf(post: ForumPost): String {
        plainTextCache[post.pid]?.let { return it }
        if (plainTextCache.size > PLAIN_TEXT_CACHE_LIMIT) plainTextCache.clear()
        return ForumBodyParser.plainText(post.contentHtml).also { plainTextCache[post.pid] = it }
    }

    // ---- Board order / hide ----------------------------------------------
    private val _boardPreferences = MutableStateFlow(ForumBoardPreferences())

    init {
        viewModelScope.launch {
            boardPreferences.preferences.collect { _boardPreferences.value = it }
        }
    }

    /**
     * The home board grid after the user's order/hide preferences are applied.
     *
     * Recomputed whenever the index arrives or the preferences change, so hiding/reordering on the
     * settings page updates the grid without a refetch. The merge rule (new server boards keep
     * their server slot) lives in [arrangeForums].
     */
    val homeBoards: StateFlow<List<Forum>> =
        combine(_homeState, _boardPreferences) { home, prefs ->
            arrangeForums(home.forums, prefs)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var homeJob: Job? = null
    private var homeLatestJob: Job? = null
    private var listJob: Job? = null
    private var threadJob: Job? = null
    /** Loads the board index once per VM; a manual refresh always re-fetches. */
    fun loadHomeIfNeeded() {
        if (_homeState.value.loaded) return
        loadHome(refresh = false)
    }

    /**
     * Loads the home page's 最新主题 feed's first page once per VM.
     *
     * The feed is a second request (the site renders it as its own block below 热帖排行), so it is
     * kept independent of the index: a failed index must not hide the threads the owner opened the
     * app for, and re-entering the tab must not re-fetch rows that are already on screen.
     */
    fun loadHomeLatestIfNeeded() {
        if (_homeState.value.latestLoaded || homeLatestJob?.isActive == true) return
        loadHomeLatest(page = 1, refresh = true)
    }

    fun refreshHome() {
        loadHome(refresh = true)
        loadHomeLatest(page = 1, refresh = true)
    }

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
                // Field-wise update, not a fresh state object: the feed may already be loading (or
                // even loaded) beside the index request, and replacing the state would drop it.
                _homeState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        forums = home.forums,
                        hotRanking = home.hotRanking,
                        loaded = true,
                    )
                }
                Timber.tag(TAG).i("loadHome ok forums=%d ranking=%d", home.forums.size, home.hotRanking.size)
                loadHomeLatestIfNeeded()
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

    /** Appends the next page of the home page's 最新主题 feed. */
    fun loadMoreHomeLatest() {
        val state = _homeState.value
        if (!state.canLoadMoreLatest || state.isAppendingLatest) return
        loadHomeLatest(page = state.latestPage + 1, refresh = false)
    }

    /**
     * Fetches one page of the home feed.
     *
     * [refresh] replaces the rows (first page, or a manual retry of a failed first page); otherwise
     * the rows are appended and de-duplicated by tid. At most one request is in flight at a time:
     * that guard is what keeps the footer's tap and the scroll trigger from firing twice for the
     * same page, and the tid de-duplication keeps a server that repeats a row across pages from
     * rendering it twice.
     */
    private fun loadHomeLatest(page: Int, refresh: Boolean) {
        if (homeLatestJob?.isActive == true) return
        if (!refresh && page <= 1) return
        homeLatestJob = viewModelScope.launch {
            _homeState.update {
                if (refresh) {
                    it.copy(latestError = null)
                } else {
                    it.copy(isAppendingLatest = true, latestError = null)
                }
            }
            try {
                Timber.tag(TAG).i("loadHomeLatest page=%d refresh=%s", page, refresh)
                val data = repository.getGuidePage(ForumGuideView.Latest, page)
                _homeState.update { current ->
                    val base = if (refresh) emptyList() else current.latestThreads
                    val known = base.map { it.tid }.toSet()
                    current.copy(
                        latestThreads = base + data.threads.filterNot { it.tid in known },
                        latestPage = data.page,
                        latestTotalPages = data.totalPages.coerceAtLeast(1),
                        isAppendingLatest = false,
                        latestLoaded = true,
                        latestError = null,
                    )
                }
                Timber.tag(TAG).i(
                    "loadHomeLatest ok page=%d rows=%d pages=%d",
                    page,
                    data.threads.size,
                    data.totalPages,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "loadHomeLatest page=%d failed", page)
                _homeState.update {
                    it.copy(
                        isAppendingLatest = false,
                        latestError = error.message ?: error.javaClass.simpleName,
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
                        // The recommend strip lives on the OP card, i.e. page 1 only: a page-2
                        // response parses 0 and must not wipe the count already on screen.
                        recommendCount = it.recommendCount,
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
        val tid = thread.tid
        _replyState.value = ReplyState(
            visible = true,
            tid = tid,
            quoteAuthor = quote?.authorName,
            // One plain line for the chip; the server builds the quoted block itself from the
            // reply form's noticetrimstr, so nothing hand-built is sent (see replyWithQuote).
            quoteText = replyQuoteExcerpt(quote?.contentHtml),
            quotePid = quote?.pid,
        )
    }

    /**
     * Closes the reply composer.
     *
     * Replies deliberately have **no** draft of any kind (the user asked for 帖子 and 评论 to be
     * separate): dismissing discards whatever was typed instead of persisting it, and the footer
     * offers no 保存草稿 action. Drafts belong to the new-thread composer only — see
     * `docs/HANDOVER_CN.md`.
     */
    fun dismissReply() {
        _replyState.value = _replyState.value.copy(
            visible = false,
            error = null,
            errorRes = null,
        )
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
                // The user's text goes out verbatim; Discuz generates the quote block from the
                // form's noticetrimstr, so the client never hand-builds BBCode (which used to let
                // a quoted floor containing [/quote] swallow the user's own text).
                repository.replyWithQuote(
                    tid = thread.tid,
                    fid = thread.fid ?: 0,
                    message = composer.text,
                    page = 1,
                    quotePid = composer.quotePid,
                    fallbackFormhash = formhash,
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
                .onSuccess { ok ->
                    _actionMessage.value = if (ok) "已推荐本帖" else "推荐失败"
                    if (ok) {
                        // Move the OP chip immediately, then re-read page 1 so the number is the
                        // site's own value instead of a client-side guess. The 推荐 endpoint is a
                        // one-shot GET with no count in its response, so a refresh is the only
                        // way to keep the count from going stale after the tap.
                        _threadState.update { it.copy(recommendCount = it.recommendCount + 1) }
                        refreshThread()
                    }
                }
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
                        recommendCount = detail.recommendCount,
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

        /**
         * Upper bound on cached floor plain texts. A thread lives at most a few pages, so this is
         * generous; crossing it just clears the cache and re-parses on demand.
         */
        const val PLAIN_TEXT_CACHE_LIMIT = 400
    }
}

// ---- Reply payload (quote block + user text) --------------------------------
//
// The reply form posts a single "message" field that the site parses as BBCode, so the client
// decides what is markup and what is prose. This used to be assembled inline from the quoted
// floor's plain text verbatim, which the floor itself could break out of: a post that literally
// contained [/quote] (or [b], [img], a code sample...) closed the quote block early, and
// everything after it - including the text the user typed - was re-parsed as BBCode. That is the
// reported bug "回复时输入的文字会被 BBCode 影响".
//
// Rules enforced here:
//   * only the generated part carries tags: [quote][b]author[/b] + excerpt + [/quote];
//   * the excerpt is one line of plain text whose square brackets are defused, so it can never
//     open or close anything, and it never carries HTML (a floor body is HTML, not BBCode);
//   * the user's own text is appended verbatim and always OUTSIDE the quote block - never wrapped
//     in a second [quote], never HTML/BBCode escaped - so a literal [b] typed by the user leaves
//     the app exactly as typed.

/** Longest quoted excerpt embedded in [quote]; unchanged from the first implementation. */
private const val REPLY_QUOTE_MAX_CHARS = 120

/** Line breaks plus template whitespace (NBSP / full-width / zero-width) collapse to one space. */
private val REPLY_WHITESPACE_RUN = Regex("[\\s\\u00A0\\u3000\\u200B\\uFEFF]+")

/**
 * Markup the body walker would have dropped, matched again for the fallback path where
 * [ForumBodyParser.plainText] cannot parse the fragment and hands the raw source back.
 */
private val REPLY_HTML_TAG = Regex(
    "</?(?:a|b|blockquote|br|code|dd|div|dl|dt|em|font|hr|i|iframe|img|li|ol|p|pre|script" +
        "|small|span|strike|strong|table|tbody|td|th|thead|tr|u|ul)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)

/** Entities left behind when the excerpt comes from unparsed source. */
private val REPLY_ENTITIES = listOf(
    "&nbsp;" to " ",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&amp;" to "&",
)

/**
 * Defuses the square brackets of a *generated* quote excerpt by turning them into full-width
 * look-alikes, so nothing inside the quote block can be parsed as a tag - [/quote] in particular,
 * which would close the block and spill the rest of the payload (the user's reply included) into
 * BBCode prose.
 *
 * Only generated text goes through this; the user's own input is never rewritten.
 */
private fun defuseQuoteBbcode(text: String): String = text.replace('[', '［').replace(']', '］')

/**
 * Flattens a floor's body HTML into the single plain-text line used as the quoted excerpt.
 *
 * HTML to text stays in [ForumBodyParser.plainText] (rule 25: body parsing lives there); this
 * only normalises what that text may still carry: HTML entities, any tag that survives (the
 * unparseable-fragment fallback, or a floor that displays HTML as source), and the line breaks /
 * template whitespace that would otherwise show up as blank lines inside the quote. The
 * historical 120-char cap is kept, and it is applied *after* the brackets are defused so
 * truncation can never cut a tag in half.
 *
 * @return null when the floor has no text at all (image-only floor): the composer still quotes
 *   the author line, but leaves the excerpt blank instead of inventing content.
 */
internal fun replyQuoteExcerpt(html: String?): String? {
    if (html.isNullOrBlank()) return null
    var text = ForumBodyParser.plainText(html)
    REPLY_ENTITIES.forEach { (entity, replacement) -> text = text.replace(entity, replacement) }
    if (REPLY_HTML_TAG.containsMatchIn(text)) text = REPLY_HTML_TAG.replace(text, " ")
    text = REPLY_WHITESPACE_RUN.replace(text, " ")
    return text.trim().take(REPLY_QUOTE_MAX_CHARS).trim().ifBlank { null }
}

/**
 * The reply payload exactly as "forum.php?mod=post&action=reply" receives it.
 *
 * Shape: [quote][b]author[/b] newline {excerpt} [/quote] newline {user text} - the quote block is
 * generated (and defused), the user's text is appended verbatim and always outside it. With no
 * quoted floor the payload is just the user's text.
 */
internal fun replyMessageBody(quoteAuthor: String?, quoteText: String?, text: String): String = buildString {
    val author = quoteAuthor?.trim().orEmpty()
    if (author.isNotEmpty()) {
        append("[quote][b]").append(defuseQuoteBbcode(author)).append("[/b]\n")
        append(defuseQuoteBbcode(quoteText.orEmpty().trim()))
        append("[/quote]\n")
    }
    append(text.trim())
}

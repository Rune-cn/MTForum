// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumCategory
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumFavoritesPage
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumHotEntry
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumReplyDraft
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import io.rune.mt.forum.domain.forum.model.ForumSignInState
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.ui.page.forum.ForumViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Idempotency of the paged appends, asserted on the state the lists render from.
 *
 * The home page's 最新主题 feed is the new paged list; the board listing keeps its own append. Both
 * must obey the same contract:
 *
 * - a page is requested **once**, even if the footer tap and the scroll trigger both fire;
 * - appended rows are **added**, never replacing the pages already in hand;
 * - a row the server repeats across pages is not rendered twice;
 * - paging stops at the server's `totalPages`, and nothing is requested before the first page has
 *   arrived (the footer cannot offer "load page 1").
 *
 * These are view-model contracts, so they run without Robolectric and without a UI: the UI classes
 * assert the same contract through the composed screen (`ForumHomeFeedPagingTest`,
 * `ForumListPagingTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForumPagingGuardTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: ForumRepository) =
        ForumViewModel(repository, GuardFakeDraftRepository, GuardFakeDraftService)

    @Test
    fun `home feed loads page 1 once and appends page 2 without losing or duplicating rows`() {
        val repository = GuardFeedRepository()
        val viewModel = viewModel(repository)

        viewModel.loadHomeIfNeeded()
        viewModel.loadHomeIfNeeded()
        viewModel.loadHomeLatestIfNeeded()
        assertEquals("the first feed page must be requested exactly once", listOf(1), repository.feedCalls)
        assertEquals(10, viewModel.homeState.value.latestThreads.size)
        assertEquals(1, viewModel.homeState.value.latestPage)
        assertEquals(4, viewModel.homeState.value.latestTotalPages)
        assertTrue(viewModel.homeState.value.canLoadMoreLatest)

        viewModel.loadMoreHomeLatest()

        assertEquals(listOf(1, 2), repository.feedCalls)
        assertEquals("page 2 must be appended to page 1", 20, viewModel.homeState.value.latestThreads.size)
        assertEquals(
            "no row may be rendered twice",
            viewModel.homeState.value.latestThreads.size,
            viewModel.homeState.value.latestThreads.map { it.tid }.distinct().size,
        )
        // Page 2 starts at tid 201, right after page 1's 110.
        assertEquals(201, viewModel.homeState.value.latestThreads[10].tid)
    }

    /**
     * A server that repeats a row across pages must not produce a duplicate: the listing is keyed
     * by tid, and a repeated row is exactly the shape a real "page 2" can have while other threads
     * are published between the two requests.
     */
    @Test
    fun `home feed drops rows the server repeats across pages`() {
        val repository = GuardFeedRepository().apply { repeatLastRowOnNextPage = true }
        val viewModel = viewModel(repository)

        viewModel.loadHomeIfNeeded()
        viewModel.loadMoreHomeLatest()

        val rows = viewModel.homeState.value.latestThreads
        assertEquals("the repeated row must not be appended twice", 20, rows.size)
        assertEquals(rows.size, rows.map { it.tid }.distinct().size)
        assertEquals("the repeated row must appear once", 1, rows.count { it.tid == 110 })
    }

    @Test
    fun `a second append request for the same page is ignored while one is in flight`() {
        val repository = GuardFeedRepository().apply { gatePage = 2 }
        val viewModel = viewModel(repository)

        viewModel.loadHomeIfNeeded()
        viewModel.loadMoreHomeLatest()
        // The scroll trigger and the footer tap can fire together; the second one must not request.
        viewModel.loadMoreHomeLatest()

        assertEquals("page 2 must be requested once, not twice", listOf(1, 2), repository.feedCalls)

        repository.release()
        assertEquals(20, viewModel.homeState.value.latestThreads.size)
        assertEquals(2, viewModel.homeState.value.latestPage)
        assertFalse(viewModel.homeState.value.isAppendingLatest)
    }

    @Test
    fun `home feed stops at the server's last page`() {
        val repository = GuardFeedRepository().apply { totalPages = 2 }
        val viewModel = viewModel(repository)

        viewModel.loadHomeIfNeeded()
        viewModel.loadMoreHomeLatest()
        assertEquals(2, viewModel.homeState.value.latestPage)

        viewModel.loadMoreHomeLatest()
        viewModel.loadMoreHomeLatest()
        assertEquals("no request may be made past the last page", listOf(1, 2), repository.feedCalls)
        assertFalse(viewModel.homeState.value.canLoadMoreLatest)
    }

    @Test
    fun `board listing appends page 2 once and keeps page 1`() {
        val repository = GuardBoardRepository().apply { gatePage = 2 }
        val viewModel = viewModel(repository)

        viewModel.openForum(fid = 44, forumName = "综合交流")
        viewModel.loadMoreThreads()
        viewModel.loadMoreThreads()

        assertEquals("page 2 must be requested once", listOf(1, 2), repository.boardCalls)

        repository.release()
        assertEquals(20, viewModel.listState.value.threads.size)
        assertEquals(2, viewModel.listState.value.page)
        assertEquals(
            "no row may be rendered twice",
            viewModel.listState.value.threads.size,
            viewModel.listState.value.threads.map { it.tid }.distinct().size,
        )
        viewModel.loadMoreThreads()
        assertEquals(listOf(1, 2, 3), repository.boardCalls)
    }
}

/**
 * Feed fake with a real server's shape: 10 rows per page, four pages, and an optional gate that
 * holds a request open so the "one request at a time" guard becomes observable.
 */
private class GuardFeedRepository : ForumRepository {

    var totalPages = 4
    var pageSize = 10
    var gatePage: Int = -1
    var repeatLastRowOnNextPage = false

    val feedCalls = mutableListOf<Int>()

    private val gate = CompletableDeferred<Unit>()

    fun release() = gate.complete(Unit)

    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage {
        feedCalls += page
        if (page == gatePage) gate.await()
        val rows = buildList {
            if (page > 1 && repeatLastRowOnNextPage) {
                // The row the previous page ended with; the list must not render it twice.
                add(threadOf(page - 1, pageSize))
            }
            (1..pageSize).forEach { add(threadOf(page, it)) }
        }
        return ForumThreadPage(fid = 0, threads = rows, page = page, totalPages = totalPages)
    }

    private fun threadOf(page: Int, index: Int) = ForumThread(
        tid = page * 100 + index,
        fid = 44,
        title = "Feed $page-$index",
        authorName = "poster",
        authorUid = 1,
    )

    override val session: StateFlow<ForumSession> = MutableStateFlow(ForumSession())

    override suspend fun getHome(): ForumHomeData = ForumHomeData(
        forums = (1..12).map { Forum(fid = it, name = "Board $it") },
        hotRanking = (1..10).map { ForumHotEntry(rank = it, tid = 9000 + it, title = "Hot $it") },
    )

    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage = notWired()

    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = notWired()

    override suspend fun searchThreads(keyword: String, page: Int): ForumThreadPage = notWired()

    override suspend fun getSignInState(): ForumSignInState = ForumSignInState()

    override suspend fun signIn(formhash: String): ForumSignInOutcome = notWired()

    override suspend fun reply(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): String = notWired()

    override suspend fun restoreSession(cookies: Map<String, String>): Boolean = notWired()

    override suspend fun loginWithCookie(cookieHeader: String): Boolean = notWired()

    override suspend fun logout() = notWired<Unit>()

    override suspend fun getUserProfile(uid: Int): ForumUser? = notWired()

    override suspend fun getUserProfileDetail(uid: Int): ForumUserProfile = notWired()

    override suspend fun getConversations(page: Int): List<ForumPrivateConversation> = notWired()

    override suspend fun getConversation(uid: Int, page: Int): ForumConversationDetail = notWired()

    override suspend fun sendPrivateMessage(
        uid: Int,
        message: String,
        formhash: String,
        pmid: String?,
    ): Boolean = notWired()

    override suspend fun getFavorites(page: Int): List<ForumFavorite> = notWired()

    override suspend fun getFavoritesPage(page: Int): ForumFavoritesPage = notWired()

    override suspend fun addFavorite(tid: Int, formhash: String): Boolean = notWired()

    override suspend fun removeFavorite(favid: Long, formhash: String): Boolean = notWired()

    override suspend fun getNotices(tab: ForumNoticeTab, page: Int): ForumNoticePage = notWired()

    override suspend fun followUser(uid: Int, formhash: String): Boolean = notWired()

    override suspend fun unfollowUser(uid: Int, formhash: String): Boolean = notWired()

    override suspend fun recommendThread(tid: Int, formhash: String): Boolean = notWired()

    override suspend fun getNewThreadForm(fid: Int): ForumNewThreadForm = notWired()

    override suspend fun createThread(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): NewThreadResult = notWired()

    private fun <T> notWired(): T = error("not wired in the feed guard fake")
}

/** Board-listing fake with the same gating shape, so the append guard is observable there too. */
private class GuardBoardRepository : ForumRepository {

    var totalPages = 3
    var pageSize = 10
    var gatePage: Int = -1

    val boardCalls = mutableListOf<Int>()

    private val gate = CompletableDeferred<Unit>()

    fun release() = gate.complete(Unit)

    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage {
        boardCalls += page
        if (page == gatePage) gate.await()
        return ForumThreadPage(
            fid = fid,
            threads = (1..pageSize).map { index ->
                ForumThread(
                    tid = page * 100 + index,
                    fid = fid,
                    title = "Board thread $page-$index",
                    authorName = "poster",
                    authorUid = 1,
                )
            },
            page = page,
            totalPages = totalPages,
            categories = listOf(ForumCategory(typeId = null, name = "全部")),
        )
    }

    override val session: StateFlow<ForumSession> = MutableStateFlow(ForumSession())

    override suspend fun getHome(): ForumHomeData = ForumHomeData(emptyList(), emptyList())

    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage = notWired()

    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = notWired()

    override suspend fun searchThreads(keyword: String, page: Int): ForumThreadPage = notWired()

    override suspend fun getSignInState(): ForumSignInState = ForumSignInState()

    override suspend fun signIn(formhash: String): ForumSignInOutcome = notWired()

    override suspend fun reply(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): String = notWired()

    override suspend fun restoreSession(cookies: Map<String, String>): Boolean = notWired()

    override suspend fun loginWithCookie(cookieHeader: String): Boolean = notWired()

    override suspend fun logout() = notWired<Unit>()

    override suspend fun getUserProfile(uid: Int): ForumUser? = notWired()

    override suspend fun getUserProfileDetail(uid: Int): ForumUserProfile = notWired()

    override suspend fun getConversations(page: Int): List<ForumPrivateConversation> = notWired()

    override suspend fun getConversation(uid: Int, page: Int): ForumConversationDetail = notWired()

    override suspend fun sendPrivateMessage(
        uid: Int,
        message: String,
        formhash: String,
        pmid: String?,
    ): Boolean = notWired()

    override suspend fun getFavorites(page: Int): List<ForumFavorite> = notWired()

    override suspend fun getFavoritesPage(page: Int): ForumFavoritesPage = notWired()

    override suspend fun addFavorite(tid: Int, formhash: String): Boolean = notWired()

    override suspend fun removeFavorite(favid: Long, formhash: String): Boolean = notWired()

    override suspend fun getNotices(tab: ForumNoticeTab, page: Int): ForumNoticePage = notWired()

    override suspend fun followUser(uid: Int, formhash: String): Boolean = notWired()

    override suspend fun unfollowUser(uid: Int, formhash: String): Boolean = notWired()

    override suspend fun recommendThread(tid: Int, formhash: String): Boolean = notWired()

    override suspend fun getNewThreadForm(fid: Int): ForumNewThreadForm = notWired()

    override suspend fun createThread(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): NewThreadResult = notWired()

    private fun <T> notWired(): T = error("not wired in the board guard fake")
}

/** Draft plumbing the VM asks for at construction; none of these tests touch drafts. */
private object GuardFakeDraftRepository : ForumDraftRepository {
    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = null
    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) = Unit
    override suspend fun deleteNewThreadDraft(fid: Int) = Unit
    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = null
    override suspend fun saveReplyDraft(draft: ForumReplyDraft) = Unit
    override suspend fun deleteReplyDraft(tid: Int) = Unit
    override suspend fun draftCount(): Int = 0
}

/** Same for the site draft service. */
private object GuardFakeDraftService : ForumDraftService {
    override suspend fun getDrafts(page: Int): ForumDraftPage = ForumDraftPage(page = page)

    override suspend fun saveNewThreadDraft(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): DraftActionResult = error("drafts unused here")

    override suspend fun saveReplyDraft(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): DraftActionResult = error("drafts unused here")

    override suspend fun loadDraft(tid: Int, fid: Int, pid: Long?): ForumDraftEdit =
        error("drafts unused here")

    override suspend fun publishDraft(edit: ForumEditForm): DraftActionResult =
        error("drafts unused here")

    override suspend fun deleteDraft(edit: ForumEditForm): DraftActionResult =
        error("drafts unused here")

    override suspend fun updateDraft(
        edit: ForumEditForm,
        subject: String,
        message: String,
    ): DraftActionResult = error("drafts unused here")
}
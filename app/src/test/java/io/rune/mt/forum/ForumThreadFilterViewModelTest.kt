// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.CommentFilterRule
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleType
import io.rune.mt.forum.domain.forum.model.CommentFilterSettings
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumFavoritesPage
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumReplyDraft
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import io.rune.mt.forum.domain.forum.model.ForumSignInState
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.CommentFilterRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.forum.repository.NoopForumBoardPreferencesRepository
import io.rune.mt.forum.ui.page.forum.ForumViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The thread page's comment filter, driven through [ForumViewModel].
 *
 * The requirements are behavioural: a matching reply disappears, the OP stays unless "也过滤楼主"
 * is on, the footer count is the number of matched floors, and the reveal toggle temporarily brings
 * them back. The filtering is done in the ViewModel (see `threadDisplay`) so these checks do not
 * need Compose at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForumThreadFilterViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val filter = FakeCommentFilterRepository(
        CommentFilterSettings(
            masterEnabled = true,
            rules = listOf(
                CommentFilterRule(id = "r1", pattern = "spam", type = CommentFilterRuleType.KEYWORD),
            ),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ForumViewModel(
        repository = ThreadFakeRepository(),
        drafts = ThreadFakeDraftRepository,
        draftService = ThreadFakeDraftService,
        commentFilter = filter,
        boardPreferences = NoopForumBoardPreferencesRepository,
    )

    @Test
    fun `a matching reply is hidden while the OP stays`() = runTest(dispatcher.scheduler) {
        val vm = viewModel()

        vm.openThread(7, "title")
        advanceUntilIdle()

        val display = vm.threadDisplay.value
        assertEquals(listOf(1L, 3L), display.posts.map { it.pid })
        assertEquals(1, display.hiddenCount)
        assertTrue(display.revealAvailable)
        assertFalse(display.revealed)
    }

    @Test
    fun `the footer count is the number of matched floors`() = runTest(dispatcher.scheduler) {
        val vm = viewModel()

        vm.openThread(7, "title")
        advanceUntilIdle()

        // Only the one spam reply matched (the OP text is clean).
        assertEquals(1, vm.threadDisplay.value.hiddenCount)
    }

    @Test
    fun `reveal temporarily brings the blocked reply back`() = runTest(dispatcher.scheduler) {
        val vm = viewModel()
        vm.openThread(7, "title")
        advanceUntilIdle()
        assertEquals(2, vm.threadDisplay.value.posts.size)

        vm.toggleBlockedReveal()
        advanceUntilIdle()

        val revealed = vm.threadDisplay.value
        assertEquals(3, revealed.posts.size)
        assertEquals(1, revealed.hiddenCount)
        assertTrue(revealed.revealed)

        vm.toggleBlockedReveal()
        advanceUntilIdle()
        assertEquals(2, vm.threadDisplay.value.posts.size)
        assertFalse(vm.threadDisplay.value.revealed)
    }

    @Test
    fun `the OP is filtered too when the user opts in`() = runTest(dispatcher.scheduler) {
        filter.state.value = CommentFilterSettings(
            masterEnabled = true,
            filterOp = true,
            rules = listOf(
                CommentFilterRule(id = "r1", pattern = "OPWARNING", type = CommentFilterRuleType.KEYWORD),
            ),
        )
        val vm = viewModel()

        vm.openThread(7, "title")
        advanceUntilIdle()

        // OP contained OPWARNING; with 也过滤楼主 on it disappears too, leaving the two replies.
        assertEquals(listOf(2L, 3L), vm.threadDisplay.value.posts.map { it.pid })
        assertEquals(1, vm.threadDisplay.value.hiddenCount)
    }

    @Test
    fun `nothing is hidden while the master switch is off`() = runTest(dispatcher.scheduler) {
        filter.state.value = CommentFilterSettings(
            masterEnabled = false,
            rules = listOf(
                CommentFilterRule(id = "r1", pattern = "spam", type = CommentFilterRuleType.KEYWORD),
            ),
        )
        val vm = viewModel()

        vm.openThread(7, "title")
        advanceUntilIdle()

        assertEquals(3, vm.threadDisplay.value.posts.size)
        assertEquals(0, vm.threadDisplay.value.hiddenCount)
    }
}

/** One thread: an OP with OPWARNING, a spam reply, and a clean reply. */
private class ThreadFakeRepository : ForumRepository {
    override val session: StateFlow<ForumSession> = MutableStateFlow(ForumSession())

    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = ForumThreadDetail(
        tid = tid,
        title = "title",
        posts = listOf(
            ForumPost(pid = 1, tid = tid, authorName = "op", authorUid = 1, contentHtml = "<p>OPWARNING welcome</p>", isOp = true),
            ForumPost(pid = 2, tid = tid, authorName = "x", authorUid = 2, contentHtml = "<p>this is spam</p>"),
            ForumPost(pid = 3, tid = tid, authorName = "y", authorUid = 3, contentHtml = "<p>a clean reply</p>"),
        ),
        page = 1,
        totalPages = 1,
        formhash = "fh",
    )

    override suspend fun getHome(): ForumHomeData = ForumHomeData(emptyList(), emptyList())
    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage = notWired()
    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage = notWired()
    override suspend fun searchThreads(keyword: String, page: Int): ForumThreadPage = notWired()
    override suspend fun getSignInState(): ForumSignInState = ForumSignInState()
    override suspend fun signIn(formhash: String): ForumSignInOutcome = notWired()
    override suspend fun reply(tid: Int, fid: Int, message: String, formhash: String, noticeAuthor: String?): String = notWired()
    override suspend fun restoreSession(cookies: Map<String, String>): Boolean = notWired()
    override suspend fun loginWithCookie(cookieHeader: String): Boolean = notWired()
    override suspend fun logout() = notWired<Unit>()
    override suspend fun getUserProfile(uid: Int): ForumUser? = notWired()
    override suspend fun getUserProfileDetail(uid: Int): ForumUserProfile = notWired()
    override suspend fun getConversations(page: Int): List<ForumPrivateConversation> = notWired()
    override suspend fun getConversation(uid: Int, page: Int): ForumConversationDetail = notWired()
    override suspend fun sendPrivateMessage(uid: Int, message: String, formhash: String, pmid: String?): Boolean = notWired()
    override suspend fun getFavorites(page: Int): List<ForumFavorite> = notWired()
    override suspend fun getFavoritesPage(page: Int): ForumFavoritesPage = notWired()
    override suspend fun addFavorite(tid: Int, formhash: String): Boolean = notWired()
    override suspend fun removeFavorite(favid: Long, formhash: String): Boolean = notWired()
    override suspend fun getNotices(tab: ForumNoticeTab, page: Int): ForumNoticePage = notWired()
    override suspend fun followUser(uid: Int, formhash: String): Boolean = notWired()
    override suspend fun unfollowUser(uid: Int, formhash: String): Boolean = notWired()
    override suspend fun recommendThread(tid: Int, formhash: String): Boolean = notWired()
    override suspend fun getNewThreadForm(fid: Int): ForumNewThreadForm = notWired()
    override suspend fun createThread(fid: Int, subject: String, message: String, form: ForumNewThreadForm, images: List<NewThreadImage>): NewThreadResult = notWired()

    private fun <T> notWired(): T = error("not wired in the thread fake")
}

private object ThreadFakeDraftRepository : ForumDraftRepository {
    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = null
    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) = Unit
    override suspend fun deleteNewThreadDraft(fid: Int) = Unit
    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = null
    override suspend fun saveReplyDraft(draft: ForumReplyDraft) = Unit
    override suspend fun deleteReplyDraft(tid: Int) = Unit
    override suspend fun draftCount(): Int = 0
}

private object ThreadFakeDraftService : ForumDraftService {
    override suspend fun getDrafts(page: Int): ForumDraftPage = ForumDraftPage(page = page)
    override suspend fun saveNewThreadDraft(fid: Int, subject: String, message: String, form: ForumNewThreadForm, images: List<NewThreadImage>): DraftActionResult = error("unused")
    override suspend fun saveReplyDraft(tid: Int, fid: Int, message: String, formhash: String, noticeAuthor: String?): DraftActionResult = error("unused")
    override suspend fun loadDraft(tid: Int, fid: Int, pid: Long?): ForumDraftEdit = error("unused")
    override suspend fun publishDraft(edit: ForumEditForm): DraftActionResult = error("unused")
    override suspend fun deleteDraft(edit: ForumEditForm): DraftActionResult = error("unused")
    override suspend fun updateDraft(edit: ForumEditForm, subject: String, message: String): DraftActionResult = error("unused")
}

private class FakeCommentFilterRepository(initial: CommentFilterSettings) : CommentFilterRepository {
    val state = MutableStateFlow(initial)
    override val settings: StateFlow<CommentFilterSettings> = state

    override suspend fun setMasterEnabled(enabled: Boolean) {
        state.value = state.value.copy(masterEnabled = enabled)
    }

    override suspend fun setFilterOp(enabled: Boolean) {
        state.value = state.value.copy(filterOp = enabled)
    }

    override suspend fun addRule(pattern: String, type: CommentFilterRuleType): CommentFilterRule = error("unused")
    override suspend fun updateRule(id: String, pattern: String, type: CommentFilterRuleType) = Unit
    override suspend fun deleteRule(id: String) = Unit
    override suspend fun clearRules() = Unit
}

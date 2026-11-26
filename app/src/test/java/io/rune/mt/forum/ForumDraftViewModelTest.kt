// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumFavoritesPage
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
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
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.ui.page.forum.ForumViewModel
import io.rune.mt.forum.ui.page.forum.NewThreadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * View-model level checks for the client-local drafts (发帖 + 回复).
 *
 * The repository and the draft store are fakes, so this covers the decisions the UI depends on:
 * a saved draft is only *offered*, restoring fills the fields, discarding deletes it, a successful
 * publish/reply removes it, and an un-restored draft is never overwritten by auto-save.
 * Robolectric is here for the `Context` that `NewThreadViewModel.submit()` takes (the screen cache
 * reads picked images through the content resolver; no images are involved in these cases).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class ForumDraftViewModelTest {

    @Before
    fun setUp() {
        // viewModelScope launches on Main; unconfined runs the non-blocking part eagerly so the
        // state assertions do not need to pump a scheduler. Disk-like hops are polled below.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val context get() = RuntimeEnvironment.getApplication()

    // ---- 发帖 (new thread) ---------------------------------------------------

    @Test
    fun `opening a board with a saved draft offers restore instead of filling the fields`() {
        val drafts = FakeDraftRepository()
        drafts.newThreads[38] = ForumNewThreadDraft(fid = 38, forumName = "建议反馈", subject = "旧标题", message = "旧正文")
        val viewModel = NewThreadViewModel(DraftTestRepository(), drafts)

        viewModel.open(38, "建议反馈")

        val state = viewModel.state.value
        assertNotNull("an existing draft must surface the restore prompt", state.pendingDraft)
        assertEquals("旧标题", state.pendingDraft?.subject)
        assertEquals("the composer must stay empty until the user chooses 恢复草稿", "", state.title)
        assertEquals("", state.message)
    }

    @Test
    fun `restoring the offered draft fills subject and message`() {
        val drafts = FakeDraftRepository()
        drafts.newThreads[38] = ForumNewThreadDraft(fid = 38, subject = "旧标题", message = "旧正文")
        val viewModel = NewThreadViewModel(DraftTestRepository(), drafts)
        viewModel.open(38, "建议反馈")

        viewModel.restoreDraft()

        val state = viewModel.state.value
        assertEquals("旧标题", state.title)
        assertEquals("旧正文", state.message)
        assertNull("the prompt closes once restored", state.pendingDraft)
    }

    @Test
    fun `discarding the offered draft deletes the stored copy`() {
        val drafts = FakeDraftRepository()
        drafts.newThreads[38] = ForumNewThreadDraft(fid = 38, subject = "旧标题", message = "旧正文")
        val viewModel = NewThreadViewModel(DraftTestRepository(), drafts)
        viewModel.open(38, "建议反馈")

        viewModel.discardDraft()
        eventually("the stored draft should be deleted") { drafts.newThreads[38] == null }

        assertEquals("", viewModel.state.value.title)
        assertNull(viewModel.state.value.pendingDraft)
    }

    @Test
    fun `explicit save stores the draft and sends nothing to the server`() {
        val repository = DraftTestRepository()
        val drafts = FakeDraftRepository()
        val viewModel = NewThreadViewModel(repository, drafts)
        viewModel.open(44, "综合交流")
        viewModel.updateTitle("新标题")
        viewModel.updateMessage("新正文")

        viewModel.saveDraft()
        eventually("the draft should be written") { drafts.newThreads[44] != null }

        val draft = drafts.newThreads.getValue(44)
        assertEquals("新标题", draft.subject)
        assertEquals("新正文", draft.message)
        assertEquals("综合交流", draft.forumName)
        assertEquals("save draft must not POST anything", 0, repository.createThreadCalls)
        assertTrue("a manual save surfaces the confirmation flag", viewModel.state.value.draftSaved)
        viewModel.consumeDraftSaved()
        assertTrue(!viewModel.state.value.draftSaved)
    }

    @Test
    fun `a successful publish deletes the stored draft`() {
        val repository = DraftTestRepository()
        repository.createThreadResult = NewThreadResult.Success(tid = 9001)
        val drafts = FakeDraftRepository()
        drafts.newThreads[44] = ForumNewThreadDraft(fid = 44, subject = "旧标题", message = "旧正文")
        val viewModel = NewThreadViewModel(repository, drafts)
        viewModel.open(44, "综合交流")
        viewModel.restoreDraft()
        viewModel.submit(context)
        eventually("the thread should be published") { viewModel.state.value.successTid == 9001 }

        assertEquals(1, repository.createThreadCalls)
        eventually("the draft must be dropped after publishing") { drafts.newThreads[44] == null }
    }

    @Test
    fun `leaving the page without restoring never overwrites the older draft`() {
        val repository = DraftTestRepository()
        val drafts = FakeDraftRepository()
        drafts.newThreads[38] = ForumNewThreadDraft(fid = 38, subject = "旧标题", message = "旧正文")
        val viewModel = NewThreadViewModel(repository, drafts)
        viewModel.open(38, "建议反馈")
        // The user ignores the prompt and starts typing something else.
        viewModel.updateTitle("新标题")

        viewModel.saveOnLeave()
        // Give a wrongly scheduled save a chance to show up before asserting it did not happen.
        Thread.sleep(100)

        assertEquals("旧标题", drafts.newThreads.getValue(38).subject)
        assertEquals("旧正文", drafts.newThreads.getValue(38).message)
        assertEquals("nothing was published", 0, repository.createThreadCalls)
    }

    // ---- 回复 (reply) --------------------------------------------------------

    @Test
    fun `reply composer offers the stored draft of the same thread only`() {
        val drafts = FakeDraftRepository()
        drafts.replies[177_093] = ForumReplyDraft(tid = 177_093, text = "上次的回复")
        val viewModel = ForumViewModel(DraftTestRepository(), drafts)
        viewModel.openThread(177_093, "帖子")
        eventually("the thread should load") { viewModel.threadState.value.formhash != null }

        viewModel.requestReply()

        assertTrue(viewModel.replyState.value.visible)
        assertEquals(177_093, viewModel.replyState.value.tid)
        assertEquals("上次的回复", viewModel.replyState.value.pendingDraft?.text)
        assertEquals("text is not applied until 恢复草稿", "", viewModel.replyState.value.text)

        // A different thread has no draft for this tid.
        viewModel.dismissReply()
        assertEquals(177_093, drafts.replies.keys.single())
    }

    @Test
    fun `restoring the reply draft fills the text and quoted floor`() {
        val drafts = FakeDraftRepository()
        drafts.replies[177_093] = ForumReplyDraft(
            tid = 177_093,
            quoteAuthor = "Rune",
            quoteText = "引用内容",
            text = "上次的回复",
        )
        val viewModel = ForumViewModel(DraftTestRepository(), drafts)
        viewModel.openThread(177_093, "帖子")
        eventually("the thread should load") { viewModel.threadState.value.formhash != null }
        viewModel.requestReply()

        viewModel.restoreReplyDraft()

        val state = viewModel.replyState.value
        assertEquals("上次的回复", state.text)
        assertEquals("Rune", state.quoteAuthor)
        assertEquals("引用内容", state.quoteText)
        assertNull(state.pendingDraft)
    }

    @Test
    fun `discarding the reply draft deletes it`() {
        val drafts = FakeDraftRepository()
        drafts.replies[177_093] = ForumReplyDraft(tid = 177_093, text = "上次的回复")
        val viewModel = ForumViewModel(DraftTestRepository(), drafts)
        viewModel.openThread(177_093, "帖子")
        eventually("the thread should load") { viewModel.threadState.value.formhash != null }
        viewModel.requestReply()

        viewModel.discardReplyDraft()
        eventually("the stored reply draft should be deleted") { drafts.replies.isEmpty() }

        assertEquals("", viewModel.replyState.value.text)
        assertNull(viewModel.replyState.value.pendingDraft)
    }

    @Test
    fun `a successful reply deletes the stored draft`() {
        val repository = DraftTestRepository()
        val drafts = FakeDraftRepository()
        drafts.replies[177_093] = ForumReplyDraft(tid = 177_093, text = "上次的回复")
        val viewModel = ForumViewModel(repository, drafts)
        viewModel.openThread(177_093, "帖子")
        eventually("the thread should load") { viewModel.threadState.value.formhash != null }
        viewModel.requestReply()
        viewModel.restoreReplyDraft()

        viewModel.submitReply()
        eventually("the reply should be sent") { repository.replyCalls == 1 }
        eventually("the draft must be dropped after replying") { drafts.replies[177_093] == null }

        assertTrue("the composer closes after a successful send", !viewModel.replyState.value.visible)
    }

    /** Polls a background-completed condition; every hop in these fakes finishes in microseconds. */
    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail(message)
    }
}

/** In-memory [ForumDraftRepository]; the real serialization path is covered by [ForumDraftStoreTest]. */
private class FakeDraftRepository : ForumDraftRepository {
    val newThreads = mutableMapOf<Int, ForumNewThreadDraft>()
    val replies = mutableMapOf<Int, ForumReplyDraft>()

    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = newThreads[fid]
    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) {
        newThreads[draft.fid] = draft
    }

    override suspend fun deleteNewThreadDraft(fid: Int) {
        newThreads.remove(fid)
    }

    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = replies[tid]
    override suspend fun saveReplyDraft(draft: ForumReplyDraft) {
        replies[draft.tid] = draft
    }

    override suspend fun deleteReplyDraft(tid: Int) {
        replies.remove(tid)
    }

    override suspend fun draftCount(): Int = newThreads.size + replies.size
}

/**
 * Minimal [ForumRepository] fake: only the new-thread and thread/reply surfaces are wired, and the
 * counters let the tests assert that "save draft" never became a network call.
 */
private class DraftTestRepository : ForumRepository {

    var createThreadResult: NewThreadResult = NewThreadResult.Success(tid = 9001)
    var createThreadCalls = 0
    var newThreadFormCalls = 0
    var replyCalls = 0
    var replyResult: String = ""

    override val session = MutableStateFlow(
        ForumSession(
            cookies = mapOf("cQWy_2132_auth" to "test"),
            user = ForumUser(uid = 153466, name = "Rune", isLoggedIn = true),
        ),
    )

    override suspend fun getNewThreadForm(fid: Int): ForumNewThreadForm {
        newThreadFormCalls++
        return ForumNewThreadForm(formhash = "formhash-new", posttime = "1700000000")
    }

    override suspend fun createThread(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): NewThreadResult {
        createThreadCalls++
        return createThreadResult
    }

    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = ForumThreadDetail(
        tid = tid,
        title = "帖子",
        posts = emptyList(),
        page = 1,
        totalPages = 1,
        formhash = "formhash-thread",
    )

    override suspend fun reply(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): String {
        replyCalls++
        return replyResult
    }

    override suspend fun getHome(): ForumHomeData = notWired()
    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage = notWired()
    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage = notWired()
    override suspend fun searchThreads(keyword: String, page: Int): ForumThreadPage = notWired()
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
    override suspend fun followUser(uid: Int, formhash: String): Boolean = notWired()
    override suspend fun unfollowUser(uid: Int, formhash: String): Boolean = notWired()
    override suspend fun recommendThread(tid: Int, formhash: String): Boolean = notWired()
    override suspend fun getSignInState(): ForumSignInState = notWired()
    override suspend fun signIn(formhash: String): ForumSignInOutcome = notWired()
    override suspend fun getNotices(tab: ForumNoticeTab, page: Int): ForumNoticePage = notWired()

    private fun <T> notWired(): T = error("not wired in this fake")
}
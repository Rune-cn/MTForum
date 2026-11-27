// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumDraft
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import io.rune.mt.forum.domain.forum.model.ImageHostResult
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
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
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
 * View-model level checks for the draft flows (发帖 + 回复).
 *
 * Drafts are now stored by the **site** (`save=1`), so these tests pin the decisions the UI
 * depends on: 保存草稿 actually posts a draft, a site draft is preferred over the device copy,
 * restoring a site draft switches the composer into edit mode, publishing and deleting are their
 * own explicit actions, and a failed save keeps the text (and falls back to the device copy).
 *
 * The repository and the draft service are fakes, so nothing here touches the network. Robolectric
 * is present for the `Context` the screen passes to `saveDraft`/`submit` (no images are attached in
 * these cases).
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
    fun `saving a new-thread draft posts it to the site instead of the device`() {
        val repository = DraftTestRepository()
        val drafts = FakeDraftRepository()
        val draftsService = FakeDraftService()
        val viewModel = NewThreadViewModel(repository, drafts, draftsService, UnusedImageHost)
        viewModel.open(44, "综合交流")
        viewModel.updateTitle("新标题")
        viewModel.updateMessage("新正文")

        viewModel.saveDraft(context)
        eventually("the draft must be posted to the site") { draftsService.saveNewThreadCalls == 1 }

        val posted = draftsService.lastNewThreadDraft
        assertEquals(44, posted?.fid)
        assertEquals("新标题", posted?.subject)
        assertEquals("新正文", posted?.message)
        assertTrue("save draft must surface the confirmation flag", viewModel.state.value.draftSaved)
        assertNull("a site save must not leave a device copy behind", drafts.newThreads[44])
        assertEquals("save draft must not publish a thread", 0, repository.createThreadCalls)
    }

    @Test
    fun `a saved new-thread draft switches the composer into edit mode`() {
        val draftsService = FakeDraftService()
        draftsService.saveNewThreadResult = DraftActionResult.Success(tid = 5001)
        // The server created the draft; reading its edit form is how the composer adopts it.
        draftsService.edit = ForumDraftEdit(
            tid = 5001,
            fid = 44,
            pid = 900_003,
            subject = "新标题",
            message = "新正文",
            form = editForm(),
        )
        val viewModel = NewThreadViewModel(DraftTestRepository(), FakeDraftRepository(), draftsService, UnusedImageHost)
        viewModel.open(44, "综合交流")
        viewModel.updateTitle("新标题")
        viewModel.updateMessage("新正文")

        viewModel.saveDraft(context)
        eventually("the composer should adopt the draft the server created") {
            viewModel.state.value.draftTid == 5001
        }

        // A second save must update that draft, not create another one.
        viewModel.updateMessage("改过的正文")
        viewModel.saveDraft(context)
        eventually("the second save updates the same draft") { draftsService.updateDraftCalls == 1 }
        assertEquals("no second draft may be created", 1, draftsService.saveNewThreadCalls)
        assertEquals("改过的正文", draftsService.lastUpdateMessage)
    }

    @Test
    fun `a failed site save keeps the text and falls back to the device copy`() {
        val drafts = FakeDraftRepository()
        val draftsService = FakeDraftService()
        draftsService.saveNewThreadResult = DraftActionResult.Failure("抱歉，您的请求来路不正确")
        val viewModel = NewThreadViewModel(DraftTestRepository(), drafts, draftsService, UnusedImageHost)
        viewModel.open(44, "综合交流")
        viewModel.updateTitle("新标题")
        viewModel.updateMessage("新正文")

        viewModel.saveDraft(context)
        eventually("the device copy is the safety net") { drafts.newThreads[44] != null }

        assertEquals("the text must not be cleared on a failed save", "新标题", viewModel.state.value.title)
        assertEquals("新正文", viewModel.state.value.message)
        assertEquals("抱歉，您的请求来路不正确", viewModel.state.value.draftError)
        assertTrue("a failed save must not claim success", !viewModel.state.value.draftSaved)
        assertEquals("新正文", drafts.newThreads.getValue(44).message)
    }

    @Test
    fun `opening a board offers the site draft when one exists`() {
        val drafts = FakeDraftRepository()
        // A device copy from an earlier failed save exists too; the site copy must win, and the
        // prompt must say so.
        drafts.newThreads[38] = ForumNewThreadDraft(fid = 38, forumName = "建议反馈", subject = "旧标题", message = "旧正文")
        val draftsService = FakeDraftService()
        draftsService.drafts = mutableListOf(
            ForumDraft(tid = 7001, fid = 38, title = "站点草稿", forumName = "建议反馈"),
        )
        val viewModel = NewThreadViewModel(DraftTestRepository(), drafts, draftsService, UnusedImageHost)

        viewModel.open(38, "建议反馈")
        eventually("the site draft must replace the local prompt") {
            viewModel.state.value.pendingServerDraft != null
        }

        assertEquals(7001, viewModel.state.value.pendingServerDraft?.tid)
        assertNull("the device prompt must yield to the site draft", viewModel.state.value.pendingDraft)
        assertEquals("the composer stays empty until the user chooses 恢复草稿", "", viewModel.state.value.title)
    }

    @Test
    fun `restoring the site draft fills the composer from its edit form`() {
        val draftsService = FakeDraftService()
        draftsService.drafts = mutableListOf(ForumDraft(tid = 7001, fid = 38, title = "站点草稿"))
        draftsService.edit = ForumDraftEdit(
            tid = 7001,
            fid = 38,
            pid = 900_001,
            subject = "站点标题",
            message = "站点正文",
            form = editForm(),
        )
        val viewModel = NewThreadViewModel(DraftTestRepository(), FakeDraftRepository(), draftsService, UnusedImageHost)
        viewModel.open(38, "建议反馈")
        eventually("the site draft should be offered") {
            viewModel.state.value.pendingServerDraft != null
        }

        viewModel.restoreServerDraft()
        eventually("the edit form should be loaded") { viewModel.state.value.isDraftEdit }

        assertEquals("站点标题", viewModel.state.value.title)
        assertEquals("站点正文", viewModel.state.value.message)
        assertEquals(7001, viewModel.state.value.draftTid)
        assertTrue("restoring a site draft is not a publish", viewModel.state.value.successTid == null)
    }

    @Test
    fun `leaving the page only writes the device copy and never posts`() {
        val drafts = FakeDraftRepository()
        val draftsService = FakeDraftService()
        val viewModel = NewThreadViewModel(DraftTestRepository(), drafts, draftsService, UnusedImageHost)
        viewModel.open(44, "综合交流")
        viewModel.updateTitle("未保存的标题")
        viewModel.updateMessage("未保存的正文")

        viewModel.saveOnLeave()
        eventually("the offline copy should be written") { drafts.newThreads[44] != null }

        assertEquals("leaving must not save a site draft", 0, draftsService.saveNewThreadCalls)
        assertEquals("未保存的正文", drafts.newThreads.getValue(44).message)
    }

    @Test
    fun `leaving the page never overwrites an un-actioned draft`() {
        val drafts = FakeDraftRepository()
        drafts.newThreads[38] = ForumNewThreadDraft(fid = 38, subject = "旧标题", message = "旧正文")
        val viewModel = NewThreadViewModel(DraftTestRepository(), drafts, FakeDraftService(), UnusedImageHost)
        viewModel.open(38, "建议反馈")
        // The user ignores the prompt and starts typing something else.
        viewModel.updateTitle("新标题")

        viewModel.saveOnLeave()
        Thread.sleep(100)

        assertEquals("旧标题", drafts.newThreads.getValue(38).subject)
        assertEquals("旧正文", drafts.newThreads.getValue(38).message)
    }

    @Test
    fun `publishing an open draft is its own confirmed action`() {
        val draftsService = FakeDraftService()
        val repository = DraftTestRepository()
        val viewModel = NewThreadViewModel(repository, FakeDraftRepository(), draftsService, UnusedImageHost)
        draftsService.edit = ForumDraftEdit(
            tid = 5001,
            fid = 44,
            pid = 900_002,
            subject = "草稿标题",
            message = "草稿正文",
            form = editForm(),
        )
        viewModel.open(44, "综合交流", draftTid = 5001)
        eventually("the draft should open") { viewModel.state.value.isDraftEdit }

        viewModel.publishDraft()
        eventually("the publish should reach the site") { draftsService.publishCalls == 1 }
        eventually("publishing succeeds") { viewModel.state.value.successTid == 5001 }

        assertEquals("publish must not create a second thread", 0, repository.createThreadCalls)
        assertEquals("the edit form carries the composer's current text", "草稿正文", draftsService.lastPublishMessage)
    }

    @Test
    fun `deleting an open draft removes it on the site`() {
        val draftsService = FakeDraftService()
        draftsService.edit = ForumDraftEdit(
            tid = 5001,
            fid = 44,
            pid = 900_002,
            subject = "草稿标题",
            message = "草稿正文",
            form = editForm(),
        )
        val viewModel = NewThreadViewModel(DraftTestRepository(), FakeDraftRepository(), draftsService, UnusedImageHost)
        viewModel.open(44, "综合交流", draftTid = 5001)
        eventually("the draft should open") { viewModel.state.value.isDraftEdit }

        viewModel.deleteDraft()
        eventually("the delete should reach the site") { draftsService.deleteCalls == 1 }
        eventually("the page should hear about it") { viewModel.state.value.draftDeleted }

        assertEquals("delete must send the harvested form, not a publish", 0, draftsService.publishCalls)
    }

    // ---- 回复 (reply): deliberately has NO draft feature ---------------------

    /**
     * Replies are not draftable any more (the user asked for 帖子 and 评论 to be separate).
     *
     * The behaviour that replaced the old flow is negative and therefore asserted from the outside:
     * closing the composer must not write any device copy, must not call the site's reply-draft
     * save, and reopening the same thread must not prompt anything. The compile-time absence of
     * `saveReplyDraft`/`restoreReplyDraft` on `ForumViewModel` and of the draft fields on
     * `ReplyState` is itself part of the contract — this test pins the runtime side.
     */
    @Test
    fun `the reply composer never stores a draft`() {
        val repository = DraftTestRepository()
        val drafts = FakeDraftRepository()
        val draftsService = FakeDraftService()
        val viewModel = ForumViewModel(repository, drafts, draftsService)
        viewModel.openThread(177_093, "主题")
        eventually("the thread should load") { viewModel.threadState.value.formhash != null }

        viewModel.requestReply()
        viewModel.updateReplyText("半截回复")
        viewModel.dismissReply()

        assertEquals("reopening must not offer a restore prompt", true, viewModel.replyState.value.visible.not())
        assertTrue("no device reply draft may be written", drafts.replies.isEmpty())
        assertEquals("no site reply draft may be posted", 0, draftsService.saveReplyCalls)

        viewModel.requestReply()
        assertEquals("the composer reopens empty", "", viewModel.replyState.value.text)
        assertEquals(0, repository.replyCalls)
    }

    @Test
    fun `sending a reply still works without any draft plumbing`() {
        val repository = DraftTestRepository()
        val viewModel = ForumViewModel(repository, FakeDraftRepository(), FakeDraftService())
        viewModel.openThread(177_093, "主题")
        eventually("the thread should load") { viewModel.threadState.value.formhash != null }
        viewModel.requestReply()
        viewModel.updateReplyText("正常回复")

        viewModel.submitReply()
        eventually("the reply should be sent") { repository.replyCalls == 1 }

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

    private fun editForm() = ForumEditForm(
        action = "forum.php?mod=post&action=edit&extra=&editsubmit=yes&mobile=2",
        formhash = "16385279",
        tid = 5001,
        fid = 44,
        pid = 900_002,
        subject = "草稿标题",
        message = "草稿正文",
        fields = mapOf(
            "formhash" to "16385279",
            "posttime" to "1795733897",
            "delete" to "0",
            "fid" to "44",
            "tid" to "5001",
            "pid" to "900002",
            "page" to "",
            "editsubmit" to "yes",
            "subject" to "草稿标题",
            "message" to "草稿正文",
            "htmlon" to "0",
            "usesig" to "1",
            "save" to "",
        ),
    )
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
 * In-memory [ForumDraftService].
 *
 * The counters are what proves the client posts to the *right* endpoint: a save must go through
 * `saveNewThreadDraft`/`saveReplyDraft`, publishing and deletion must go through their own calls,
 * and `createThread`/`reply` must stay at zero for a draft write.
 */
private class FakeDraftService : ForumDraftService {
    var drafts: MutableList<ForumDraft> = mutableListOf()
    var edit: ForumDraftEdit? = null

    var saveNewThreadResult: DraftActionResult = DraftActionResult.Success(tid = 5001)
    var replyDraftResult: DraftActionResult = DraftActionResult.Success()

    // The assertions poll these from the test thread while the VM writes on Main, so every
    // observed field has to be volatile or the reads may see a stale value.
    @Volatile var saveNewThreadCalls = 0
    @Volatile var saveReplyCalls = 0
    @Volatile var updateDraftCalls = 0
    @Volatile var publishCalls = 0
    @Volatile var deleteCalls = 0
    @Volatile var loadDraftCalls = 0

    @Volatile var lastNewThreadDraft: NewThreadDraftCall? = null
    @Volatile var lastReplyDraftTid: Int? = null
    @Volatile var lastReplyDraftMessage: String? = null
    @Volatile var lastUpdateMessage: String? = null
    @Volatile var lastPublishMessage: String? = null

    data class NewThreadDraftCall(val fid: Int, val subject: String, val message: String)

    override suspend fun getDrafts(page: Int): ForumDraftPage = ForumDraftPage(drafts.toList(), page, 1)

    override suspend fun saveNewThreadDraft(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): DraftActionResult {
        // Payload first, counter last: the tests poll the counter, so it has to be the final
        // write or a poll can observe "called" before the arguments it asserts on.
        lastNewThreadDraft = NewThreadDraftCall(fid, subject, message)
        saveNewThreadCalls++
        return saveNewThreadResult
    }

    override suspend fun saveReplyDraft(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): DraftActionResult {
        lastReplyDraftTid = tid
        lastReplyDraftMessage = message
        saveReplyCalls++
        return replyDraftResult
    }

    override suspend fun loadDraft(tid: Int, fid: Int, pid: Long?): ForumDraftEdit {
        loadDraftCalls++
        return edit ?: error("no edit fixture configured")
    }

    override suspend fun publishDraft(edit: ForumEditForm): DraftActionResult {
        publishCalls++
        lastPublishMessage = edit.fields["message"]
        return DraftActionResult.Success(tid = edit.tid)
    }

    override suspend fun deleteDraft(edit: ForumEditForm): DraftActionResult {
        deleteCalls++
        return DraftActionResult.Success()
    }

    override suspend fun updateDraft(
        edit: ForumEditForm,
        subject: String,
        message: String,
    ): DraftActionResult {
        updateDraftCalls++
        lastUpdateMessage = message
        return DraftActionResult.Success()
    }
}

/**
 * Minimal [ForumRepository] fake: only the new-thread and thread/reply surfaces are wired, and the
 * counters let the tests assert that a draft save never became a publish.
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

/**
 * The image host is a *different* code path from the drafts these cases exercise; every call here
 * would fail loudly, which is exactly what "these tests never touch the image host" means.
 */
private object UnusedImageHost : ImageHostGateway {
    override suspend fun upload(bytes: ByteArray, fileName: String, mimeType: String): ImageHostResult =
        error("the draft tests must not upload to the image host")
}

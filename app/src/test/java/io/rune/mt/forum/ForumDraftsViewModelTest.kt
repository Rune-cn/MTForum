// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumDraft
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumFavoritesPage
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import io.rune.mt.forum.domain.forum.model.ForumSignInState
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.ui.page.forum.ForumDraftsPhase
import io.rune.mt.forum.ui.page.forum.ForumDraftsViewModel
import io.rune.mt.forum.ui.page.forum.phase
import java.io.IOException
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 草稿 screen's state machine.
 *
 * These pin the failure the user reported ("App 里看不到站点草稿箱原有的草稿"): the screen used to
 * stand on `!session.isLoggedIn`, and the session is restored asynchronously, so a page the site
 * had already returned could be hidden behind the session flag — and a login-required verdict
 * stayed stuck forever because `loadIfNeeded()` never asked again once `loaded` was true.
 *
 * The screen now renders [ForumDraftsPhase]; the checks below are the four honest states it must
 * keep apart: rows, login required, failure, and a genuinely empty draft box.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForumDraftsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rows the site returned become the list`() = runTest(dispatcher.scheduler) {
        val repository = DraftsFakeRepository(signedIn = true)
        val service = DraftsFakeService().apply { result = { page -> pageOf(page, 177381, 177363) } }
        val viewModel = ForumDraftsViewModel(repository, service)

        viewModel.loadIfNeeded()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(177381, 177363), state.drafts.map { it.tid })
        assertTrue(state.loaded)
        assertFalse(state.loginRequired)
        assertNull(state.error)
        assertEquals(ForumDraftsPhase.Rows, state.phase(signedIn = true))
    }

    @Test
    fun `fetched rows are shown even while the session flag still says signed out`() =
        runTest(dispatcher.scheduler) {
            // The reported bug: a signed-out flag (restore still running, or a stale one) must not
            // hide a page the site did serve. The rows are the evidence, not the flag.
            val repository = DraftsFakeRepository(signedIn = false)
            val service = DraftsFakeService().apply { result = { page -> pageOf(page, 177381) } }
            val viewModel = ForumDraftsViewModel(repository, service)

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse("the session really is signed out", repository.session.value.isLoggedIn)
            assertFalse(state.loginRequired)
            assertEquals(1, state.drafts.size)
            assertEquals(ForumDraftsPhase.Rows, state.phase(signedIn = false))
            assertNotEquals(ForumDraftsPhase.LoginRequired, state.phase(signedIn = false))
        }

    @Test
    fun `rows stay visible when the session signs out after the load`() =
        runTest(dispatcher.scheduler) {
            val repository = DraftsFakeRepository(signedIn = true)
            val service = DraftsFakeService().apply { result = { page -> pageOf(page, 177363) } }
            val viewModel = ForumDraftsViewModel(repository, service)
            viewModel.loadIfNeeded()
            advanceUntilIdle()

            repository.signOut()

            assertEquals(ForumDraftsPhase.Rows, viewModel.state.value.phase(signedIn = false))
            assertFalse(viewModel.state.value.loginRequired)
        }

    @Test
    fun `a login form is login required and is not confused with an empty box`() =
        runTest(dispatcher.scheduler) {
            val repository = DraftsFakeRepository(signedIn = false)
            val service = DraftsFakeService().apply {
                result = { throw ForumLoginRequiredException() }
            }
            val viewModel = ForumDraftsViewModel(repository, service)

            viewModel.loadIfNeeded()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.loginRequired)
            assertTrue("a guest has no drafts, so the list stays empty", state.drafts.isEmpty())
            assertNull("login required is not a network failure", state.error)
            assertEquals(ForumDraftsPhase.LoginRequired, state.phase(signedIn = true))
            assertNotEquals(ForumDraftsPhase.Empty, state.phase(signedIn = true))
        }

    @Test
    fun `a genuinely empty draft box is empty and loaded`() = runTest(dispatcher.scheduler) {
        val repository = DraftsFakeRepository(signedIn = true)
        val service = DraftsFakeService().apply { result = { page -> ForumDraftPage(page = page) } }
        val viewModel = ForumDraftsViewModel(repository, service)

        viewModel.loadIfNeeded()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.drafts.isEmpty())
        assertTrue("an empty page is a completed load", state.loaded)
        assertFalse(state.loginRequired)
        assertNull(state.error)
        assertEquals(ForumDraftsPhase.Empty, state.phase(signedIn = true))
    }

    @Test
    fun `a failed fetch is an error and never reads as an empty box`() = runTest(dispatcher.scheduler) {
        val repository = DraftsFakeRepository(signedIn = true)
        val service = DraftsFakeService().apply { result = { throw IOException("HTTP 502 for drafts") } }
        val viewModel = ForumDraftsViewModel(repository, service)

        viewModel.loadIfNeeded()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("HTTP 502 for drafts", state.error)
        assertFalse(state.loginRequired)
        assertEquals(ForumDraftsPhase.Error, state.phase(signedIn = true))
        assertNotEquals(ForumDraftsPhase.Empty, state.phase(signedIn = true))
    }

    @Test
    fun `a login-required verdict is retried once the session is signed in`() =
        runTest(dispatcher.scheduler) {
            // The sticky-login-card bug: the first fetch races the cookie restore, reports "login
            // required", and the screen must ask again as soon as the session settles — it must not
            // wait for a pull-to-refresh.
            val repository = DraftsFakeRepository(signedIn = false)
            val service = DraftsFakeService().apply { result = { throw ForumLoginRequiredException() } }
            val viewModel = ForumDraftsViewModel(repository, service)

            viewModel.loadIfNeeded()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.loginRequired)
            assertEquals(1, service.calls)

            repository.signIn()
            service.result = { page -> pageOf(page, 177381, 177363) }
            viewModel.loadIfNeeded()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("the signed-in session must re-ask the site", 2, service.calls)
            assertEquals(2, state.drafts.size)
            assertFalse(state.loginRequired)
            assertEquals(ForumDraftsPhase.Rows, state.phase(signedIn = true))
        }

    @Test
    fun `a signed-out session does not re-ask on every entry`() = runTest(dispatcher.scheduler) {
        val repository = DraftsFakeRepository(signedIn = false)
        val service = DraftsFakeService().apply { result = { throw ForumLoginRequiredException() } }
        val viewModel = ForumDraftsViewModel(repository, service)

        viewModel.loadIfNeeded()
        advanceUntilIdle()
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        assertEquals("the login card is not re-fetched while the session stays signed out", 1, service.calls)
        assertEquals(ForumDraftsPhase.LoginRequired, viewModel.state.value.phase(signedIn = false))
    }

    private fun pageOf(page: Int, vararg tids: Int) = ForumDraftPage(
        drafts = tids.map { tid -> ForumDraft(tid = tid, fid = 44, title = "草稿 $tid", forumName = "综合交流") },
        page = page,
        totalPages = 1,
    )
}

/** [ForumDraftService] fake: [result] stands in for the site, [calls] counts the asks. */
private class DraftsFakeService : ForumDraftService {

    @Volatile
    var calls = 0

    var result: suspend (Int) -> ForumDraftPage = { page -> ForumDraftPage(page = page) }

    override suspend fun getDrafts(page: Int): ForumDraftPage {
        calls++
        return result(page)
    }

    override suspend fun saveNewThreadDraft(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): DraftActionResult = notWired()

    override suspend fun saveReplyDraft(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): DraftActionResult = notWired()

    override suspend fun loadDraft(tid: Int, fid: Int, pid: Long?): ForumDraftEdit = notWired()

    override suspend fun publishDraft(edit: ForumEditForm): DraftActionResult = notWired()

    override suspend fun deleteDraft(edit: ForumEditForm): DraftActionResult = notWired()

    override suspend fun updateDraft(
        edit: ForumEditForm,
        subject: String,
        message: String,
    ): DraftActionResult = notWired()

    private fun <T> notWired(): T = error("not used by the drafts screen")
}

/** [ForumRepository] fake with a flip-able session; every other surface is outside this screen. */
private class DraftsFakeRepository(signedIn: Boolean) : ForumRepository {

    private val _session = MutableStateFlow(if (signedIn) signedInSession() else ForumSession())
    override val session: StateFlow<ForumSession> = _session

    fun signIn() {
        _session.value = signedInSession()
    }

    fun signOut() {
        _session.value = ForumSession()
    }

    private fun signedInSession() = ForumSession(
        cookies = mapOf("cQWy_2132_auth" to "test"),
        user = ForumUser(uid = 153466, name = "Rune", isLoggedIn = true),
    )

    override suspend fun getHome(): ForumHomeData = notWired()
    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage = notWired()
    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage = notWired()
    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = notWired()
    override suspend fun searchThreads(keyword: String, page: Int): ForumThreadPage = notWired()
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
    override suspend fun followUser(uid: Int, formhash: String): Boolean = notWired()
    override suspend fun unfollowUser(uid: Int, formhash: String): Boolean = notWired()
    override suspend fun recommendThread(tid: Int, formhash: String): Boolean = notWired()
    override suspend fun getSignInState(): ForumSignInState = notWired()
    override suspend fun signIn(formhash: String): ForumSignInOutcome = notWired()
    override suspend fun getNotices(tab: ForumNoticeTab, page: Int): ForumNoticePage = notWired()
    override suspend fun getNewThreadForm(fid: Int): ForumNewThreadForm = notWired()
    override suspend fun createThread(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): NewThreadResult = notWired()

    private fun <T> notWired(): T = error("not used by the drafts screen")
}
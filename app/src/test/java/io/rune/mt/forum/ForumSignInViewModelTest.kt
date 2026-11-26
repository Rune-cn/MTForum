// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
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
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.ui.page.forum.ForumSignInViewModel
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
 * Offline checks for the 签到 card's state machine: first load, sign-in + refresh, login bounce and
 * a rejected sign-in.
 *
 * The repository is faked, so this covers the two-request shape (sign-in GET → page re-read)
 * without touching the network; the selectors and the CDATA mapping are covered by
 * [ForumSignInParserTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForumSignInViewModelTest {

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
    fun `load publishes the counters and the pending state`() = runTest(dispatcher.scheduler) {
        val repository = FakeSignInRepository()
        repository.page = page(rank = 0, streak = 2, total = 271, signed = false)
        val viewModel = ForumSignInViewModel(repository)

        viewModel.loadIfNeeded()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(271, state.signIn.totalDays)
        assertEquals(2, state.signIn.streakDays)
        assertEquals("Lv.8", state.signIn.signInLevel)
        assertEquals("16385279", state.signIn.formhash)
        assertFalse(state.alreadySignedToday)
        assertFalse(state.loginRequired)
        assertTrue(state.loaded)
        assertEquals("one read on first load", 1, repository.stateCalls)
    }

    @Test
    fun `sign in refreshes the numbers and keeps the server message`() = runTest(dispatcher.scheduler) {
        val repository = FakeSignInRepository()
        repository.page = page(rank = 0, streak = 2, total = 271, signed = false)
        repository.outcome = ForumSignInOutcome.Success("签到成功")
        val viewModel = ForumSignInViewModel(repository)
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        // The site reloads the page after signing; the fake serves the post-sign-in numbers.
        repository.page = page(rank = 1033, streak = 3, total = 272, signed = true)
        viewModel.signIn()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("16385279", repository.signedWith.single())
        assertEquals(1033, state.signIn.todayRank)
        assertEquals(3, state.signIn.streakDays)
        assertEquals(272, state.signIn.totalDays)
        assertTrue(state.alreadySignedToday)
        assertFalse(state.isSigningIn)
        assertEquals("the server's own wording is what the user sees", "签到成功", state.message)
        assertFalse(state.messageIsError)

        viewModel.clearMessage()
        assertEquals(null, viewModel.state.value.message)
    }

    @Test
    fun `an already-signed reply still marks the day done`() = runTest(dispatcher.scheduler) {
        val repository = FakeSignInRepository()
        repository.page = page(rank = 0, streak = 2, total = 271, signed = false)
        repository.outcome = ForumSignInOutcome.AlreadySigned("已签到")
        val viewModel = ForumSignInViewModel(repository)
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        viewModel.signIn()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.alreadySignedToday)
        assertEquals("已签到", viewModel.state.value.message)
        assertFalse(viewModel.state.value.messageIsError)
    }

    @Test
    fun `login required becomes the login card state`() = runTest(dispatcher.scheduler) {
        val repository = FakeSignInRepository()
        repository.pageException = ForumLoginRequiredException()
        val viewModel = ForumSignInViewModel(repository)

        viewModel.loadIfNeeded()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("guests must get the login card, not an empty one", state.loginRequired)
        assertTrue(state.loaded)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a rejected sign-in surfaces the error without flipping the state`() = runTest(dispatcher.scheduler) {
        val repository = FakeSignInRepository()
        repository.page = page(rank = 0, streak = 2, total = 271, signed = false)
        repository.outcome = ForumSignInOutcome.Failure("抱歉，您所在的用户组(游客)无法进行此操作")
        val viewModel = ForumSignInViewModel(repository)
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        viewModel.signIn()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("抱歉，您所在的用户组(游客)无法进行此操作", state.error)
        assertEquals("抱歉，您所在的用户组(游客)无法进行此操作", state.message)
        assertTrue(state.messageIsError)
        assertFalse("a rejection must not look signed", state.alreadySignedToday)
        assertEquals(271, state.signIn.totalDays)
    }

    private fun page(
        rank: Int,
        streak: Int,
        total: Int,
        signed: Boolean,
    ) = ForumSignInState(
        alreadySignedToday = signed,
        signInLevel = "Lv.8",
        todayRank = rank,
        streakDays = streak,
        totalDays = total,
        nickname = "Rune",
        avatarUrl = "https://cdn.binmt.cc/uc_server/data/avatar/000/15/34/66_avatar_middle.jpg",
        formhash = "16385279",
    )
}

/**
 * Minimal [ForumRepository] fake: only the sign-in surface is wired, everything else fails loudly
 * if a test starts depending on it.
 */
private class FakeSignInRepository : ForumRepository {

    var page = ForumSignInState()
    var pageException: Exception? = null
    var outcome: ForumSignInOutcome = ForumSignInOutcome.Success("签到成功")
    var stateCalls = 0
    val signedWith = mutableListOf<String>()

    override val session: StateFlow<ForumSession> = MutableStateFlow(
        ForumSession(
            cookies = mapOf("cQWy_2132_auth" to "test"),
            user = ForumUser(uid = 153466, name = "Rune", isLoggedIn = true),
        ),
    )

    override suspend fun getSignInState(): ForumSignInState {
        stateCalls++
        pageException?.let { throw it }
        return page
    }

    override suspend fun signIn(formhash: String): ForumSignInOutcome {
        signedWith += formhash
        return outcome
    }

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

    override suspend fun sendPrivateMessage(uid: Int, message: String, formhash: String, pmid: String?): Boolean =
        notWired()

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

    private fun <T> notWired(): T = error("not wired in this fake")
}

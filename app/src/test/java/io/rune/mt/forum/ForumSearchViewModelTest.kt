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
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.ui.page.forum.ForumSearchViewModel
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
 * The result page's state machine around `Route.ForumSearch(keyword)`.
 *
 * The behaviour that matters for the home magnifier is the route hand-off: a route that carries
 * the keyword must put that exact keyword in the field **and** run the search immediately (the
 * user already confirmed it in the overlay), while an empty route only focuses the field. Refining
 * keeps the field's text so a second submit re-runs without leaving the page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForumSearchViewModelTest {

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
    fun `a route keyword seeds the field and searches immediately`() = runTest(dispatcher.scheduler) {
        val repository = FakeSearchRepository()
        val viewModel = ForumSearchViewModel(repository)

        viewModel.prepare("mt管理器")
        advanceUntilIdle()

        assertEquals(listOf("mt管理器"), repository.searchedKeywords)
        assertEquals("mt管理器", viewModel.state.value.keyword)
        assertTrue(viewModel.state.value.searched)
        assertEquals(1, viewModel.state.value.threads.size)
    }

    @Test
    fun `prepare is idempotent so a recomposition does not replay the route keyword`() = runTest(dispatcher.scheduler) {
        val repository = FakeSearchRepository()
        val viewModel = ForumSearchViewModel(repository)

        viewModel.prepare("签到")
        advanceUntilIdle()
        viewModel.prepare("签到")
        advanceUntilIdle()

        assertEquals(1, repository.searchedKeywords.size)
    }

    @Test
    fun `an empty route keyword does not fire a request`() = runTest(dispatcher.scheduler) {
        val repository = FakeSearchRepository()
        val viewModel = ForumSearchViewModel(repository)

        viewModel.prepare("")
        advanceUntilIdle()

        assertTrue(repository.searchedKeywords.isEmpty())
        assertFalse(viewModel.state.value.searched)
    }

    @Test
    fun `refining keeps the keyword in the field and re-runs the search`() = runTest(dispatcher.scheduler) {
        val repository = FakeSearchRepository()
        val viewModel = ForumSearchViewModel(repository)

        viewModel.prepare("mt管理器")
        advanceUntilIdle()

        viewModel.updateKeyword("逆向")
        viewModel.search()
        advanceUntilIdle()

        assertEquals(listOf("mt管理器", "逆向"), repository.searchedKeywords)
        assertEquals("the field must keep the refined keyword", "逆向", viewModel.state.value.keyword)
        assertEquals(1, viewModel.state.value.page)
    }

    @Test
    fun `a guest gets the login card instead of a request`() = runTest(dispatcher.scheduler) {
        val repository = FakeSearchRepository(loggedIn = false)
        val viewModel = ForumSearchViewModel(repository)

        viewModel.prepare("mt管理器")
        advanceUntilIdle()

        assertTrue(repository.searchedKeywords.isEmpty())
        assertTrue(viewModel.state.value.loginRequired)
        assertEquals("the keyword stays in the field for after the login", "mt管理器", viewModel.state.value.keyword)
    }

    @Test
    fun `a server error is surfaced and retry re-runs the same keyword`() = runTest(dispatcher.scheduler) {
        val repository = FakeSearchRepository().apply { failNext = true }
        val viewModel = ForumSearchViewModel(repository)

        viewModel.prepare("mt管理器")
        advanceUntilIdle()
        assertEquals("搜索间隔，请稍后再试", viewModel.state.value.error)

        viewModel.search()
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.error)
        assertEquals(listOf("mt管理器", "mt管理器"), repository.searchedKeywords)
    }
}

/**
 * Fake search repository: records the keywords the page actually asked for, then serves one row.
 *
 * Every other [ForumRepository] member fails loudly, so a test cannot accidentally depend on an
 * endpoint this feature never touches.
 */
private class FakeSearchRepository(loggedIn: Boolean = true) : ForumRepository {

    val searchedKeywords = mutableListOf<String>()
    var failNext = false

    private val sessionFlow = MutableStateFlow(
        if (loggedIn) {
            // ForumSession.isLoggedIn is derived from the Discuz auth cookie, not from `user`.
            ForumSession(
                cookies = mapOf("cQWy_2132_auth" to "test"),
                user = ForumUser(uid = 1, name = "Rune", isLoggedIn = true),
            )
        } else {
            ForumSession()
        },
    )
    override val session: StateFlow<ForumSession> = sessionFlow

    override suspend fun searchThreads(keyword: String, page: Int): ForumThreadPage {
        searchedKeywords += keyword
        if (failNext) {
            failNext = false
            error("搜索间隔，请稍后再试")
        }
        return ForumThreadPage(
            fid = 0,
            threads = listOf(
                ForumThread(
                    tid = 177_093,
                    title = "关于${keyword}的结果",
                    authorName = "Rune",
                    authorUid = 1,
                ),
            ),
            page = page,
            totalPages = 1,
        )
    }

    override suspend fun getHome(): ForumHomeData = notWired()

    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage = notWired()

    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage = notWired()

    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = notWired()

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

    override suspend fun getSignInState(): ForumSignInState = notWired()

    override suspend fun signIn(formhash: String): ForumSignInOutcome = notWired()

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
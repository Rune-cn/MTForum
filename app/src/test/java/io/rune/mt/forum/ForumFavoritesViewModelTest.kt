// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumFavoritesPage
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import io.rune.mt.forum.domain.forum.model.ForumSignInState
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.ui.page.forum.ForumFavoritesViewModel
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
 * Offline checks for the favourites screen's state machine: load, optimistic removal and rollback.
 *
 * The repository is faked, so this covers the "confirmation → removal → rollback" decision
 * without touching the network; the parser side is covered by [ForumProfileParserTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForumFavoritesViewModelTest {

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
    fun `load publishes rows formhash and pager`() = runTest(dispatcher.scheduler) {
        val repository = FakeForumRepository()
        repository.favoritesPage = ForumFavoritesPage(
            favorites = listOf(favorite(712378L, 177295), favorite(710934L, 176770)),
            formhash = "16385279",
            page = 1,
            totalPages = 4,
        )
        val viewModel = ForumFavoritesViewModel(repository)

        viewModel.loadIfNeeded()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.favorites.size)
        assertEquals("16385279", state.formhash)
        assertEquals(4, state.totalPages)
        assertTrue(state.canLoadMore)
        assertFalse(state.isLoading)
    }

    @Test
    fun `remove hides the row at once and submits the page formhash`() = runTest(dispatcher.scheduler) {
        val repository = FakeForumRepository()
        val target = favorite(712378L, 177295)
        repository.favoritesPage = ForumFavoritesPage(
            favorites = listOf(target, favorite(710934L, 176770)),
            formhash = "16385279",
            totalPages = 1,
        )
        val viewModel = ForumFavoritesViewModel(repository)
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        viewModel.removeFavorite(target)
        // Optimistic: the row is already gone before the POST resolves.
        assertEquals(1, viewModel.state.value.favorites.size)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.favorites.size)
        assertEquals(listOf(712378L), repository.removedFavids.map { it.first })
        assertEquals("16385279", repository.removedFavids.single().second)
        assertFalse(viewModel.state.value.deleteFailed)
    }

    @Test
    fun `rejected removal restores the row at its old position`() = runTest(dispatcher.scheduler) {
        val repository = FakeForumRepository()
        val first = favorite(712378L, 177295)
        val second = favorite(710934L, 176770)
        repository.favoritesPage = ForumFavoritesPage(
            favorites = listOf(first, second),
            formhash = "16385279",
            totalPages = 1,
        )
        repository.removeResult = false
        val viewModel = ForumFavoritesViewModel(repository)
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        viewModel.removeFavorite(first)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.favorites.size)
        assertEquals("the first row must return to the top", first.favid, state.favorites.first().favid)
        assertTrue("a rejected removal must surface a toast", state.deleteFailed)

        viewModel.clearDeleteFailed()
        assertFalse(viewModel.state.value.deleteFailed)
    }

    @Test
    fun `login bounce is reported instead of an empty list`() = runTest(dispatcher.scheduler) {
        val repository = FakeForumRepository()
        repository.pageException = ForumLoginRequiredException()
        val viewModel = ForumFavoritesViewModel(repository)

        viewModel.loadIfNeeded()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.loginRequired)
        assertTrue(state.favorites.isEmpty())
        assertTrue(state.loaded)
    }

    @Test
    fun `load more appends without duplicating favids`() = runTest(dispatcher.scheduler) {
        val repository = FakeForumRepository()
        repository.favoritesPage = ForumFavoritesPage(
            favorites = listOf(favorite(712378L, 177295)),
            formhash = "16385279",
            page = 1,
            totalPages = 2,
        )
        val viewModel = ForumFavoritesViewModel(repository)
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        // Page 2 repeats the first favid (the site shifts rows between requests) plus a new one.
        repository.favoritesPage = ForumFavoritesPage(
            favorites = listOf(favorite(712378L, 177295), favorite(706090L, 176000)),
            formhash = "16385279",
            page = 2,
            totalPages = 2,
        )
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.favorites.size)
        assertEquals(2, state.page)
        assertFalse(state.canLoadMore)
    }

    private fun favorite(favid: Long, tid: Int) = ForumFavorite(favid = favid, tid = tid, title = "thread $tid")
}

/**
 * Minimal [ForumRepository] fake: only the favourites surface is wired, everything else fails
 * loudly if a test starts depending on it.
 */
private class FakeForumRepository : ForumRepository {

    var favoritesPage = ForumFavoritesPage()
    var pageException: Exception? = null
    var removeResult = true
    var removeException: Exception? = null
    val removedFavids = mutableListOf<Pair<Long, String>>()

    override val session: StateFlow<ForumSession> = MutableStateFlow(
        ForumSession(
            cookies = mapOf("cQWy_2132_auth" to "test"),
            user = ForumUser(uid = 153466, name = "Rune", isLoggedIn = true),
        ),
    )

    override suspend fun getFavoritesPage(page: Int): ForumFavoritesPage {
        pageException?.let { throw it }
        return favoritesPage
    }

    override suspend fun getFavorites(page: Int): List<ForumFavorite> = favoritesPage.favorites

    override suspend fun removeFavorite(favid: Long, formhash: String): Boolean {
        removedFavids += favid to formhash
        removeException?.let { throw it }
        return removeResult
    }

    override suspend fun getHome(): ForumHomeData = notWired()

    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage = notWired()

    override suspend fun searchThreads(keyword: String, page: Int): ForumThreadPage = notWired()

    override suspend fun getNotices(tab: ForumNoticeTab, page: Int): ForumNoticePage = notWired()

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

    override suspend fun addFavorite(tid: Int, formhash: String): Boolean = notWired()

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
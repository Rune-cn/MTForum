// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumBoardPreferences
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.Forum
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
import io.rune.mt.forum.domain.forum.repository.ForumBoardPreferencesRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.forum.repository.NoopCommentFilterRepository
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
import org.junit.Before
import org.junit.Test

/**
 * The home grid honours the persisted board order/hide preferences, and a preference change updates
 * the grid without a re-fetch. (`arrangeForums` itself, including the new-board merge rule, is
 * covered by `ForumBoardPreferencesTest`; this checks the ViewModel wiring the real grid reads.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForumBoardGridViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: FakeBoardPrefsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefs = FakeBoardPrefsRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ForumViewModel(
        repository = GridFakeRepository(),
        drafts = GridFakeDraftRepository,
        draftService = GridFakeDraftService,
        commentFilter = NoopCommentFilterRepository,
        boardPreferences = prefs,
    )

    @Test
    fun `the home grid honours the stored order and hidden set`() = runTest(dispatcher.scheduler) {
        prefs.state.value = ForumBoardPreferences(orderedFids = listOf(3, 1, 2), hiddenFids = listOf(2))
        val vm = viewModel()

        vm.loadHomeIfNeeded()
        advanceUntilIdle()

        assertEquals(listOf(3, 1), vm.homeBoards.value.map { it.fid })
    }

    @Test
    fun `toggling a preference updates the grid without a refetch`() = runTest(dispatcher.scheduler) {
        val vm = viewModel()
        vm.loadHomeIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), vm.homeBoards.value.map { it.fid })

        prefs.update { it.copy(hiddenFids = listOf(2)) }
        advanceUntilIdle()

        assertEquals(listOf(1, 3), vm.homeBoards.value.map { it.fid })
    }
}

/** In-memory board preferences that behaves like the DataStore-backed one for `update`. */
private class FakeBoardPrefsRepository : ForumBoardPreferencesRepository {
    val state = MutableStateFlow(ForumBoardPreferences())
    override val preferences: StateFlow<ForumBoardPreferences> = state

    override suspend fun update(transform: (ForumBoardPreferences) -> ForumBoardPreferences) {
        state.value = transform(state.value)
    }
}

private class GridFakeRepository : ForumRepository {
    override val session: StateFlow<ForumSession> = MutableStateFlow(ForumSession())

    override suspend fun getHome(): ForumHomeData = ForumHomeData(
        forums = listOf(Forum(1, "a"), Forum(2, "b"), Forum(3, "c")),
        hotRanking = emptyList(),
    )

    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage =
        ForumThreadPage(fid = 0, threads = emptyList(), page = page, totalPages = 1)

    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = notWired()
    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage = notWired()
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

    private fun <T> notWired(): T = error("not wired in the grid fake")
}

private object GridFakeDraftRepository : ForumDraftRepository {
    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = null
    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) = Unit
    override suspend fun deleteNewThreadDraft(fid: Int) = Unit
    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = null
    override suspend fun saveReplyDraft(draft: ForumReplyDraft) = Unit
    override suspend fun deleteReplyDraft(tid: Int) = Unit
    override suspend fun draftCount(): Int = 0
}

private object GridFakeDraftService : ForumDraftService {
    override suspend fun getDrafts(page: Int): ForumDraftPage = ForumDraftPage(page = page)
    override suspend fun saveNewThreadDraft(fid: Int, subject: String, message: String, form: ForumNewThreadForm, images: List<NewThreadImage>): DraftActionResult = error("unused")
    override suspend fun saveReplyDraft(tid: Int, fid: Int, message: String, formhash: String, noticeAuthor: String?): DraftActionResult = error("unused")
    override suspend fun loadDraft(tid: Int, fid: Int, pid: Long?): ForumDraftEdit = error("unused")
    override suspend fun publishDraft(edit: ForumEditForm): DraftActionResult = error("unused")
    override suspend fun deleteDraft(edit: ForumEditForm): DraftActionResult = error("unused")
    override suspend fun updateDraft(edit: ForumEditForm, subject: String, message: String): DraftActionResult = error("unused")
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.ForumCategory
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.ui.page.forum.ForumViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Checks the 主题分类 selection plumbing in [ForumViewModel].
 *
 * The site filters server-side, so this is really a check that the chosen `typeid` reaches every
 * request of the board (page 1, refresh, "load more") and that a switch drops the previous rows
 * instead of leaving unfiltered ones under a filtered tab label.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForumListCategoryStateTest {

    @Before
    fun setUp() {
        // viewModelScope launches on Dispatchers.Main; unconfined keeps the assertions synchronous.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selecting a category reloads page 1 and keeps the choice for paging and refresh`() {
        val repository = RecordingRepository()
        val viewModel = ForumViewModel(repository)

        viewModel.openForum(fid = 38, forumName = "建议反馈")
        assertEquals(listOf(1 to null), repository.threadCalls)
        assertNull(viewModel.listState.value.typeId)
        assertEquals(
            listOf(null, 62, 63),
            viewModel.listState.value.categories.map { it.typeId },
        )

        viewModel.selectCategory(63)
        assertEquals(listOf(1 to null, 1 to 63), repository.threadCalls)
        assertEquals(63, viewModel.listState.value.typeId)
        assertEquals(1, viewModel.listState.value.page)
        assertEquals(
            "the unfiltered rows must be gone, got ${viewModel.listState.value.threads}",
            listOf(63 * 1000 + 1),
            viewModel.listState.value.threads.map { it.tid },
        )

        // Re-selecting the same tab is a no-op (no request, no flicker).
        viewModel.selectCategory(63)
        assertEquals(listOf(1 to null, 1 to 63), repository.threadCalls)

        // Paging and pull-to-refresh keep the filter.
        viewModel.loadMoreThreads()
        viewModel.refreshForum()
        assertEquals(listOf(1 to null, 1 to 63, 2 to 63, 1 to 63), repository.threadCalls)
        assertEquals(63, viewModel.listState.value.typeId)

        // 全部 restores the unfiltered listing.
        viewModel.selectCategory(null)
        assertEquals(listOf(1 to null, 1 to 63, 2 to 63, 1 to 63, 1 to null), repository.threadCalls)
        assertNull(viewModel.listState.value.typeId)
        assertTrue(viewModel.listState.value.categories.isNotEmpty())
    }

    @Test
    fun `reopening the same board keeps its category and a new board starts at 全部`() {
        val repository = RecordingRepository()
        val viewModel = ForumViewModel(repository)

        viewModel.openForum(fid = 38, forumName = "建议反馈")
        viewModel.selectCategory(62)
        assertEquals(62, viewModel.listState.value.typeId)

        // Back from a thread / tab re-entry: same board, same filter, and no extra request.
        viewModel.openForum(fid = 38, forumName = "建议反馈")
        assertEquals(62, viewModel.listState.value.typeId)
        assertEquals(2, repository.threadCalls.size)

        viewModel.openForum(fid = 44, forumName = "综合交流")
        assertNull(viewModel.listState.value.typeId)
        assertEquals(44, viewModel.listState.value.fid)
        assertEquals(1 to null, repository.threadCalls.last())
    }

    /** Records every board request and answers with a deterministic page. */
    private class RecordingRepository : ForumRepository {

        val threadCalls = mutableListOf<Pair<Int, Int?>>()

        override val session = MutableStateFlow(ForumSession())

        override suspend fun getHome(): ForumHomeData = ForumHomeData(emptyList(), emptyList())

        override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage {
            threadCalls += page to typeId
            return ForumThreadPage(
                fid = fid,
                threads = listOf(thread(fid, page, typeId)),
                page = page,
                totalPages = 3,
                categories = listOf(
                    ForumCategory(typeId = null, name = "全部"),
                    ForumCategory(typeId = 62, name = "BUG反馈"),
                    ForumCategory(typeId = 63, name = "意见反馈"),
                ),
            )
        }

        private fun thread(fid: Int, page: Int, typeId: Int?) = ForumThread(
            tid = (typeId ?: 0) * 1000 + page,
            fid = fid,
            title = "thread $page",
            authorName = "tester",
            authorUid = 1,
        )

        override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage =
            error("unused")

        override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = error("unused")

        override suspend fun reply(
            tid: Int,
            fid: Int,
            message: String,
            formhash: String,
            noticeAuthor: String?,
        ): String = error("unused")

        override suspend fun restoreSession(cookies: Map<String, String>): Boolean = error("unused")

        override suspend fun loginWithCookie(cookieHeader: String): Boolean = error("unused")

        override suspend fun logout() = error("unused")

        override suspend fun getUserProfile(uid: Int): ForumUser? = error("unused")

        override suspend fun getConversations(page: Int): List<ForumPrivateConversation> = error("unused")

        override suspend fun getConversation(uid: Int, page: Int): ForumConversationDetail = error("unused")

        override suspend fun sendPrivateMessage(
            uid: Int,
            message: String,
            formhash: String,
            pmid: String?,
        ): Boolean = error("unused")

        override suspend fun getFavorites(page: Int): List<ForumFavorite> = error("unused")

        override suspend fun addFavorite(tid: Int, formhash: String): Boolean = error("unused")

        override suspend fun removeFavorite(favid: Long, formhash: String): Boolean = error("unused")

        override suspend fun followUser(uid: Int, formhash: String): Boolean = error("unused")

        override suspend fun unfollowUser(uid: Int, formhash: String): Boolean = error("unused")

        override suspend fun recommendThread(tid: Int, formhash: String): Boolean = error("unused")

        override suspend fun getNewThreadForm(fid: Int): ForumNewThreadForm = error("unused")

        override suspend fun createThread(
            fid: Int,
            subject: String,
            message: String,
            form: ForumNewThreadForm,
            images: List<NewThreadImage>,
        ): NewThreadResult = error("unused")
    }
}
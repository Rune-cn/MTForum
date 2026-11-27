// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumCategory
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
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Navigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.forum.ForumListPage
import io.rune.mt.forum.ui.page.forum.ForumViewModel
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixComponentHost
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * End-to-end paging of a **board listing** (`ForumListPage`), hosted in the bottom-bar pager.
 *
 * The report 「主页的帖子还是显示不完整」 was answered last round by adding `ForumAppendEffect` and a
 * "load page N" footer to the paged lists. This class is the check that round never had: it scrolls
 * a real composed board page to its tail and asserts the next page is both **requested and
 * appended** (the page-2 row exists in the list and page 1 is still there), with the page hosted in
 * the `HorizontalPager` the app puts it in — a nested scroll inside a pager is the classic place an
 * end-of-list signal dies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumListPagingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repository: FakeBoardRepository
    private lateinit var viewModel: ForumViewModel

    @Before
    fun setUp() {
        repository = FakeBoardRepository()
        viewModel = ForumViewModel(repository, BoardFakeDraftRepository, BoardFakeDraftService)
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                module {
                    single<ForumRepository> { repository }
                    single<ForumDraftRepository> { BoardFakeDraftRepository }
                    single<ForumDraftService> { BoardFakeDraftService }
                },
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `scrolling a board list to the end appends page 2 inside the pager`() {
        composeBoardInPager()
        compose.waitForIdle()

        assertEquals(
            "page 1 must be requested once when the board opens",
            listOf(1),
            repository.threadCalls,
        )

        val list = boardList()
        list.performScrollToIndex(lastItemIndex())
        compose.waitForIdle()

        assertEquals(
            "reaching the tail must request page 2 exactly once",
            listOf(1, 2),
            repository.threadCalls,
        )
        // The page-2 row can only be found in the item list if the append really happened.
        list.performScrollToNode(hasText("Board thread 2-1"))
        compose.waitForIdle()
        assertEquals(
            "the appended page-2 row must be rendered",
            1,
            compose.onAllNodesWithText("Board thread 2-1").fetchSemanticsNodes().size,
        )
        // Appended, not replaced.
        list.performScrollToNode(hasText("Board thread 1-10"))
        compose.waitForIdle()
        assertEquals(
            "page 1 rows must survive the append",
            1,
            compose.onAllNodesWithText("Board thread 1-10").fetchSemanticsNodes().size,
        )
    }

    /**
     * The footer's tap is the path that must stay reachable when the scroll trigger is off — the
     * documented retry after a failed page. It must fetch the page once and append it.
     */
    @Test
    fun `footer tap inside the pager retries and appends the failed page`() {
        repository.failingPage = 2
        composeBoardInPager()
        compose.waitForIdle()

        val list = boardList()
        list.performScrollToIndex(lastItemIndex())
        compose.waitForIdle()
        assertEquals(
            "the failed append must stay off and not loop",
            listOf(1, 2),
            repository.threadCalls,
        )

        compose.onNodeWithText("Load page 2").performClick()
        compose.waitForIdle()

        assertEquals(
            "the tap must be the retry, and only one request for the page",
            listOf(1, 2, 2),
            repository.threadCalls,
        )
        list.performScrollToNode(hasText("Board thread 2-1"))
        compose.waitForIdle()
        assertEquals(
            "the tapped page must be appended to the rendered list",
            1,
            compose.onAllNodesWithText("Board thread 2-1").fetchSemanticsNodes().size,
        )
    }

    /** The list's own last item index, read from the scroll container's collection info. */
    private fun lastItemIndex(): Int {
        val node = boardList()
        val rowCount = node.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        return (rowCount - 1).coerceAtLeast(0)
    }

    /** The page's vertical list, not the horizontal pager that wraps it. */
    private fun boardList() = compose.onNode(hasScrollAction() and isVerticalList())

    private fun isVerticalList() =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    /** The real board page hosted as the app hosts its pages: inside the bottom-bar pager. */
    private fun composeBoardInPager() {
        compose.setContent {
            CompositionLocalProvider(LocalSquircleEnabled provides false) {
                AppTheme(
                    useMiuix = true,
                    themeMode = ThemeMode.LIGHT,
                    paletteStyle = PaletteStyle.TonalSpot,
                    colorSpec = ThemeColorSpec.SPEC_2021,
                    useDynamicColor = false,
                    useMiuixMonet = false,
                    seedColor = Color(0xFF2E9E5B),
                    useMiuixOs4 = false,
                ) {
                    val backStack = rememberNavBackStack<Route>(Route.Main)
                    val shellNavigator = remember(backStack) { Navigator(backStack) }
                    MiuixComponentHost(useOs4 = false, useBlur = false) {
                        CompositionLocalProvider(LocalNavigator provides shellNavigator) {
                            val pagerState = rememberPagerState(pageCount = { 2 })
                            HorizontalPager(
                                state = pagerState,
                                beyondViewportPageCount = 1,
                                modifier = Modifier.fillMaxSize(),
                            ) { page ->
                                if (page == 0) {
                                    ForumListPage(
                                        fid = 44,
                                        forumName = "综合交流",
                                        enableBlur = false,
                                        outerPadding = PaddingValues(),
                                        viewModel = viewModel,
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fake board listing: ten rows per page across three server pages, in the server's order.
 *
 * `failingPage` makes the first request for that page fail, which is the state in which the
 * automatic trigger deliberately stays off so the footer becomes the only way forward.
 */
private class FakeBoardRepository : ForumRepository {

    var pageSize = 10
    var totalPages = 3
    var failingPage: Int? = null

    val threadCalls = mutableListOf<Int>()

    override val session: StateFlow<ForumSession> = MutableStateFlow(ForumSession())

    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage {
        threadCalls += page
        if (page == failingPage) {
            failingPage = null
            throw IOException("board page $page failed")
        }
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

    private fun <T> notWired(): T = error("not wired in the board fake")
}

/** Draft plumbing the VM asks for at construction; the listing never touches it. */
private object BoardFakeDraftRepository : ForumDraftRepository {
    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = null
    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) = Unit
    override suspend fun deleteNewThreadDraft(fid: Int) = Unit
    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = null
    override suspend fun saveReplyDraft(draft: ForumReplyDraft) = Unit
    override suspend fun deleteReplyDraft(tid: Int) = Unit
    override suspend fun draftCount(): Int = 0
}

/** Same for the site draft service: the listing issues no draft call. */
private object BoardFakeDraftService : ForumDraftService {
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
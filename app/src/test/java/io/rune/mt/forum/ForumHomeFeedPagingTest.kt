// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumFavoritesPage
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumHotEntry
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
import io.rune.mt.forum.ui.page.forum.ForumHomePage
import io.rune.mt.forum.ui.page.forum.ForumSignInViewModel
import io.rune.mt.forum.ui.page.forum.ForumViewModel
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixComponentHost
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import io.rune.mt.forum.domain.forum.repository.ForumSearchHistoryRepository
import io.rune.mt.forum.ui.page.forum.ForumSearchHistoryViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * The home page must be a thread feed, not only a top-10 leaderboard.
 *
 * 机主反馈（第二次）「主页的帖子还是显示不完整」：上一轮把「加载下一页」加到了**有分页的列表**
 * （版块 / 导读 / 帖子），但主页本身从来没有列表——它只渲染版块宫格和站点 `热帖排行` 的 Top-10。
 * 站点的 `forum.php` 在榜单下面接着用 AJAX 无限加载帖子流（`index.html` 末尾的
 * `comiis_loadlistbo_1` + `plugin.php?id=comiis_app_portal&pid=1&page=N&comiis_list=yes`），
 * 客户端从未实现这一段，所以主页的「帖子」永远停在 10 条。上一轮修的翻页在别的页面上，
 * 修不到这里——这就是「还是」的原因。
 *
 * These tests are black-box on purpose: they assert what the user can see (a feed section, the
 * next page's rows, the footer's page counter), so the same file reproduces the truncation before
 * the fix and passes after it. The home page is composed **inside the bottom-bar pager**, exactly
 * how the app hosts it (`MiuixSettingsPage`'s `HorizontalPager`, `beyondViewportPageCount = 1`),
 * because a nested scroll inside a pager is where an end-of-list signal usually dies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumHomeFeedPagingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private lateinit var repository: FakeFeedRepository
    private lateinit var viewModel: ForumViewModel

    @Before
    fun setUp() {
        repository = FakeFeedRepository()
        viewModel = ForumViewModel(repository, FeedFakeDraftRepository, FeedFakeDraftService)
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                module {
                    single<ForumRepository> { repository }
                    single<ForumDraftRepository> { FeedFakeDraftRepository }
                    single<ForumDraftService> { FeedFakeDraftService }
                    single<ForumSearchHistoryRepository> { FeedFakeSearchHistory() }
                    viewModelOf(::ForumViewModel)
                    viewModelOf(::ForumSignInViewModel)
                    viewModelOf(::ForumSearchHistoryViewModel)
                },
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    /**
     * The truncation itself.
     *
     * The fake serves a small board grid, two ranking rows and a 40-row 最新主题 feed spread over
     * four server pages, so the first feed row sits inside the viewport. Before the fix the home
     * page rendered **zero** feed rows (it stopped at the leaderboard); the owner sees exactly
     * that. After the fix the feed section and its first page are on screen.
     */
    @Test
    fun `home page renders the latest-thread feed beyond the top-10 ranking`() {
        repository.boardCount = 3
        repository.rankingCount = 2
        composeHomeInPager()
        compose.waitForIdle()

        // The ranking is still the site's own top-10 (never re-sorted, never truncated).
        assertEquals(
            "the ranking rows must stay on screen",
            1,
            compose.onAllNodesWithText("Hot thread 1").fetchSemanticsNodes().size,
        )

        // The feed's first page: these rows only exist after the fix.
        assertEquals(
            "home page must render the latest-thread feed; before the fix it rendered 0 feed rows",
            1,
            compose.onAllNodesWithText("Feed 1-1").fetchSemanticsNodes().size,
        )
        assertEquals(
            "the feed must have its own section title, not be dumped under the ranking",
            1,
            compose.onAllNodesWithText("Latest threads").fetchSemanticsNodes().size,
        )
        assertEquals(
            "the feed must come from the guide repository call, never a hand-built URL",
            listOf(ForumGuideView.Latest to 1),
            repository.guideCalls,
        )

        capture("home-feed-page-1.png")
    }

    /**
     * Scrolling to the tail of the home list must request page 2 **and** append it: the footer
     * counter moves to `2 / 4` and a page-2 row becomes reachable. This is the check the previous
     * round never had — it only proved "a call happened".
     */
    @Test
    fun `scrolling to the end of the home feed requests and appends page 2 inside the pager`() {
        repository.boardCount = 3
        repository.rankingCount = 2
        composeHomeInPager()
        compose.waitForIdle()

        val list = homeList()
        list.performScrollToIndex(lastItemIndex())
        compose.waitForIdle()

        assertTrue(
            "page 2 must be requested, got ${repository.guideCalls}",
            repository.guideCalls.contains(ForumGuideView.Latest to 2),
        )
        // Reaching the page-2 row proves the rows were appended to the list, not merely fetched:
        // `performScrollToNode` only succeeds when the row exists in the LazyColumn's item list.
        list.performScrollToNode(hasText("Feed 2-1"))
        compose.waitForIdle()
        assertEquals(
            "the appended page-2 row must be rendered",
            1,
            compose.onAllNodesWithText("Feed 2-1").fetchSemanticsNodes().size,
        )
        // Appended, not replaced: the tail of page 1 is still in the list.
        list.performScrollToNode(hasText("Feed 1-10"))
        compose.waitForIdle()
        assertEquals(
            "page 1 rows must survive the append",
            1,
            compose.onAllNodesWithText("Feed 1-10").fetchSemanticsNodes().size,
        )
    }

    /**
     * The footer's tap is the committed path, independent of the scroll trigger.
     *
     * A page that cannot be auto-appended is exactly the state the footer exists for: the first
     * page request for page 2 fails, the automatic trigger stays off while an error is shown, and
     * the only way forward is the explicit "load page N" row. Tapping it must retry, append the
     * page, and not produce a duplicate request for the same page.
     */
    @Test
    fun `footer tap loads the next page that the scroll trigger could not append`() {
        repository.boardCount = 3
        repository.rankingCount = 2
        repository.failingPage = 2
        composeHomeInPager()
        compose.waitForIdle()

        // Reach the footer: the automatic append fires and fails, so the footer stays on page 1.
        val list = homeList()
        list.performScrollToIndex(lastItemIndex())
        compose.waitForIdle()
        assertEquals(
            "the failed page must not be retried in a loop",
            listOf(ForumGuideView.Latest to 1, ForumGuideView.Latest to 2),
            repository.guideCalls,
        )

        // The explicit row is still offering page 2 — tap it.
        compose.onNodeWithText("Load page 2").performClick()
        compose.waitForIdle()

        assertEquals(
            "the tap must be the retry that fetched page 2 exactly once",
            listOf(ForumGuideView.Latest to 1, ForumGuideView.Latest to 2, ForumGuideView.Latest to 2),
            repository.guideCalls,
        )
        list.performScrollToNode(hasText("Feed 2-1"))
        compose.waitForIdle()
        assertEquals(
            "the tapped page must be appended to the rendered list",
            1,
            compose.onAllNodesWithText("Feed 2-1").fetchSemanticsNodes().size,
        )
    }

    /** The list's own last item index, read from the scroll container's collection info. */
    private fun lastItemIndex(): Int {
        val node = homeList()
        val rowCount = node.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        return (rowCount - 1).coerceAtLeast(0)
    }

    /**
     * The page's vertical list, not the horizontal pager that wraps it.
     *
     * Inside the pager both the pager and the LazyColumn expose a scroll action, so the target has
     * to be narrowed by the vertical axis range; `onNode(hasScrollAction())` would match twice and
     * fail with "Expected exactly '1' node".
     */
    private fun homeList() = compose.onNode(hasScrollAction() and isVerticalList())

    private fun isVerticalList() =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    /**
     * The real home page hosted the way the app hosts it: slot 0 of the bottom-bar pager.
     *
     * `beyondViewportPageCount = 1` matches `MiuixSettingsPage.SettingsPagerContent`, so the page
     * is measured exactly like production (a sibling page is composed off-screen as well).
     */
    private fun composeHomeInPager() {
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
                                    ForumHomePage(
                                        enableBlur = false,
                                        title = "Forum",
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

    /** Screenshots are visual evidence only; a capture failure must not fail the suite. */
    private fun capture(name: String) {
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SCREENSHOT: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure {
            println("SCREENSHOT skipped: ${it::class.java.name}: ${it.message}")
        }
    }
}

/**
 * Fake forum backend for the home page: a board grid, the site's ranking rows, and a four-page
 * 最新主题 feed served through the guide call the fix is required to use.
 */
private class FakeFeedRepository : ForumRepository {

    var feedPageSize = 10
    var boardCount = 12
    var rankingCount = 10

    /** When set, the first request for this feed page throws (the footer is then the only retry). */
    var failingPage: Int? = null

    /** Every guide request in order; the paging assertions read this. */
    val guideCalls = mutableListOf<Pair<ForumGuideView, Int>>()

    override val session: StateFlow<ForumSession> = MutableStateFlow(ForumSession())

    override suspend fun getHome(): ForumHomeData = ForumHomeData(
        forums = (1..boardCount).map { Forum(fid = it, name = "Board $it") },
        hotRanking = (1..rankingCount).map { rank ->
            ForumHotEntry(rank = rank, tid = 9000 + rank, title = "Hot thread $rank")
        },
    )

    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage {
        guideCalls += view to page
        if (page == failingPage) {
            failingPage = null
            throw IOException("feed page $page failed")
        }
        return ForumThreadPage(
            fid = 0,
            threads = (1..feedPageSize).map { index -> feedThread(page, index) },
            page = page,
            totalPages = 4,
        )
    }

    private fun feedThread(page: Int, index: Int) = ForumThread(
        tid = page * 100 + index,
        fid = 44,
        title = "Feed $page-$index",
        authorName = "poster",
        authorUid = 1,
    )

    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage = notWired()

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

    private fun <T> notWired(): T = error("not wired in the home feed fake")
}

/** Draft plumbing the home VM asks for at construction; the home screen never touches it. */
private object FeedFakeDraftRepository : ForumDraftRepository {
    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = null
    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) = Unit
    override suspend fun deleteNewThreadDraft(fid: Int) = Unit
    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = null
    override suspend fun saveReplyDraft(draft: ForumReplyDraft) = Unit
    override suspend fun deleteReplyDraft(tid: Int) = Unit
    override suspend fun draftCount(): Int = 0
}

/** Same for the site draft service: the home screen issues no draft call. */
private object FeedFakeDraftService : ForumDraftService {
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

/** In-memory search history for the home page's overlay; the page only needs it to resolve. */
private class FeedFakeSearchHistory : ForumSearchHistoryRepository {
    override val recent: kotlinx.coroutines.flow.StateFlow<List<String>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    override suspend fun record(keyword: String) = Unit

    override suspend fun clear() = Unit
}

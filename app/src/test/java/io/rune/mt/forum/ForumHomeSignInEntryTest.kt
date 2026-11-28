// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.forum.repository.ForumSearchHistoryRepository
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Navigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.forum.ForumHomePage
import io.rune.mt.forum.ui.page.forum.ForumSearchHistoryViewModel
import io.rune.mt.forum.ui.page.forum.ForumSignInViewModel
import io.rune.mt.forum.ui.page.forum.ForumViewModel
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixComponentHost
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
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
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * The 签到 entry point lives in the **home top bar**, not in the settings page.
 *
 * This composes the real [ForumHomePage] over a faked forum repository, so the assertion is about
 * the production wiring: the top-right action exists, its tap opens the sign-in sheet, and the
 * sheet shows the cached counters, the guest prompt and the server's own result wording. The
 * settings card being gone is pinned separately in [SettingsAccountEntryTest] (the section is
 * built inside a `LazyListScope`, so that test scans the page source).
 *
 * Robolectric resolves `stringResource` against `values/` (English), so the labels asserted here
 * are the English ones. Kernels/boards come from the fake, never from the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumHomeSignInEntryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private val repository = FakeHomeRepository()

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                module {
                    single<ForumRepository> { repository }
                    single<ForumDraftRepository> { HomeFakeDraftRepository() }
                    single<ForumDraftService> { HomeFakeDraftService() }
                    single<io.rune.mt.forum.domain.forum.repository.CommentFilterRepository> {
                        io.rune.mt.forum.domain.forum.repository.NoopCommentFilterRepository
                    }
                    single<io.rune.mt.forum.domain.forum.repository.ForumBoardPreferencesRepository> {
                        io.rune.mt.forum.domain.forum.repository.NoopForumBoardPreferencesRepository
                    }
                    // 帖子缓存 (ForumViewModel's optional dependency): the Noop store keeps these
                    // tests off the filesystem while the constructor stays satisfied for Koin,
                    // which resolves every parameter (rule 34).
                    single<io.rune.mt.forum.ui.page.forum.ForumThreadCacheRepository> {
                        io.rune.mt.forum.ui.page.forum.NoopForumThreadCacheRepository
                    }
                    // The home page always mounts the search overlay, whose recent list comes from
                    // this view model; the sheet tests never open it.
                    single<ForumSearchHistoryRepository> { HomeFakeSearchHistory() }
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

    @Test
    fun `check-in action sits in the home top bar and opens the sign-in sheet`() {
        composeHome(useOs4 = false)
        compose.waitForIdle()

        // The bar now hosts the 搜索 magnifier, the 发帖 action and the 签到 action, and the 消息提醒
        // bell is gone on purpose: notices moved into the 消息 tab of the bottom bar.
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("New thread").assertIsDisplayed()
        compose.onNodeWithContentDescription("Daily check-in").assertIsDisplayed()
        compose.onNodeWithContentDescription("Notifications").assertDoesNotExist()

        capture("os3-home-sign-in-entry.png")

        openSheet()

        // The sheet's panel asked the page once (the fake serves the pending state) and rendered
        // the counters — the node's presence is what proves the tap reached the surface.
        compose.onNodeWithText("Not signed in yet").assertIsDisplayed()
        compose.onNodeWithText("Check in").assertIsDisplayed()
    }

    @Test
    fun `signed-out guest gets a login prompt that routes to the login page`() {
        repository.sessionFlow.value = ForumSession()
        composeHome(useOs4 = false)
        compose.waitForIdle()

        openSheet()

        // A guest must never get a dead 签到 button: the panel says the page is member-only and
        // offers the login route (rule 18), the same behaviour the card had in settings.
        compose.onNodeWithText("Sign in to check in every day.").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithText("Check in").assertDoesNotExist()

        compose.onNodeWithText("Sign in").performClick()
        compose.waitForIdle()

        assertEquals(
            "the guest prompt must push the login route",
            Route.Login,
            navigator?.current(),
        )
    }

    @Test
    fun `signing in updates the open sheet and shows the server's message`() {
        composeHome(useOs4 = false)
        compose.waitForIdle()
        openSheet()

        compose.onNodeWithText("271 days").assertIsDisplayed()
        compose.onNodeWithText("Check in").performClick()
        // The VM re-reads the page after the GET (the site reloads it); the fake serves the
        // post-sign-in numbers, so the sheet must show them without being closed and reopened.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        // Both the header and the now-disabled action button read 今日已签到, which is the point:
        // the same surface that said "Check in" a tap ago says the day is done.
        compose.onAllNodesWithText("Signed in today").assertCountEquals(2)
        compose.onNodeWithText("272 days").assertIsDisplayed()
        // The snackbar carries the server's CDATA text verbatim, inside the sheet where the user
        // is looking (the shell's host sits behind the sheet).
        compose.onNodeWithText("签到成功").assertExists()
    }

    /**
     * The OS4 sheet deliberately has **no** rendering test here.
     *
     * Robolectric's bundled Skia cannot compile miuix's progressive-blur AGSL shader (it fails with
     * `error: 13: cannot swizzle value of type 'shader'` inside `createProgressiveBlurEffect`), and
     * the OS4 top bar samples a backdrop as soon as OS4 is on, so any `useMiuixOs4 = true`
     * composition of this page dies in the shader builder before the sheet is reachable. This is
     * the same limitation recorded for the thread overflow menu's page-backdrop path
     * (`ThreadActionsPopupLayoutTest`), i.e. an OS4 glass surface can only be checked on a device.
     *
     * What is covered instead: the action is the same `MiuixTopBarIconButton` in both styles (the
     * widget itself branches on `AppTheme.useMiuixOs4`, rule 7), the open/tap wiring is asserted by
     * the OS3 cases above, and `ForumSignInSheet` picks its OS4/OS3 presentation from the ambient
     * theme alone. The OS4 pixel result stays an on-device check.
     */

    /** Clicks the 签到 action and lets the sheet's open animation settle. */
    private fun openSheet() {
        compose.onNodeWithContentDescription("Daily check-in").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /**
     * The real home page, hosted exactly as the pager hosts it: the page fills its slot and the
     * navigator the page reads comes from the shell.
     */
    private fun composeHome(useOs4: Boolean) {
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
                    useMiuixOs4 = useOs4,
                ) {
                    val backStack = rememberNavBackStack<Route>(Route.Main)
                    val shellNavigator = remember(backStack) { Navigator(backStack) }
                    navigator = shellNavigator
                    // The app always mounts this host (`SettingsActivity`), and its Scaffold owns
                    // the dialog layer the OS4 glass sheet renders into — without it the sheet
                    // composes nothing in a test host. useBlur stays off: Robolectric's Skia cannot
                    // compile the glass blur shader (see ThreadActionsPopupLayoutTest).
                    MiuixComponentHost(useOs4 = useOs4, useBlur = false) {
                        CompositionLocalProvider(LocalNavigator provides shellNavigator) {
                            ForumHomePage(
                                enableBlur = false,
                                title = "Forum",
                                outerPadding = PaddingValues(),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Latest navigator built by [composeHome], so a test can assert the pushed route. */
    private var navigator: Navigator? = null

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
 * Minimal [ForumRepository] fake for the home screen: the board index and the sign-in page are
 * wired, everything else fails loudly if a test starts depending on it.
 */
private class FakeHomeRepository : ForumRepository {

    val sessionFlow = MutableStateFlow(
        ForumSession(
            cookies = mapOf("cQWy_2132_auth" to "test"),
            user = ForumUser(uid = 153466, name = "Rune", isLoggedIn = true),
        ),
    )
    override val session: StateFlow<ForumSession> = sessionFlow

    var page = ForumSignInState(
        alreadySignedToday = false,
        signInLevel = "Lv.8",
        todayRank = 0,
        streakDays = 2,
        totalDays = 271,
        nickname = "Rune",
        formhash = "16385279",
    )
    var outcome: ForumSignInOutcome = ForumSignInOutcome.Success("签到成功")
    var stateCalls = 0

    override suspend fun getHome(): ForumHomeData = ForumHomeData(
        forums = listOf(
            Forum(fid = 1, name = "综合交流"),
            Forum(fid = 41, name = "逆向工坊"),
            Forum(fid = 7, name = "求助问答"),
        ),
        hotRanking = listOf(
            ForumHotEntry(rank = 1, tid = 177003, title = "破甲系统提示词 DeepSeek Flash 实测可用"),
            ForumHotEntry(rank = 2, tid = 176770, title = "逆向工坊正式发布"),
        ),
    )

    override suspend fun getSignInState(): ForumSignInState {
        stateCalls++
        return page
    }

    /** Mimics the site: the day's numbers only change when the page is re-read afterwards. */
    override suspend fun signIn(formhash: String): ForumSignInOutcome {
        page = page.copy(alreadySignedToday = true, todayRank = 1033, streakDays = 3, totalDays = 272)
        return outcome
    }

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

/** Draft plumbing the home VM asks for at construction; the home screen never touches it. */
private class HomeFakeDraftRepository : ForumDraftRepository {
    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = null

    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) = Unit

    override suspend fun deleteNewThreadDraft(fid: Int) = Unit

    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = null

    override suspend fun saveReplyDraft(draft: ForumReplyDraft) = Unit

    override suspend fun deleteReplyDraft(tid: Int) = Unit

    override suspend fun draftCount(): Int = 0
}

private class HomeFakeDraftService : ForumDraftService {
    override suspend fun getDrafts(page: Int): ForumDraftPage = notWired()

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

    override suspend fun updateDraft(edit: ForumEditForm, subject: String, message: String): DraftActionResult =
        notWired()

    private fun <T> notWired(): T = error("not wired in this fake")
}

/** Empty recent-search list: the home test never opens the search surface. */
private class HomeFakeSearchHistory : ForumSearchHistoryRepository {
    override val recent = kotlinx.coroutines.flow.flowOf(emptyList<String>())

    override suspend fun record(keyword: String) = Unit

    override suspend fun clear() = Unit
}
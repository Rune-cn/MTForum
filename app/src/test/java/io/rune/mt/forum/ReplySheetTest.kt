// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumHttpClient
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
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPost
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
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Navigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.forum.ForumThreadPage
import io.rune.mt.forum.ui.page.forum.ForumViewModel
import io.rune.mt.forum.ui.page.forum.REPLY_SHEET_TAG
import io.rune.mt.forum.ui.page.forum.ReplyComposer
import io.rune.mt.forum.ui.page.forum.ReplyComposerBody
import io.rune.mt.forum.ui.page.forum.ReplyState
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBottomSheetCloseAction
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBottomSheetInsideMargin
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixComponentHost
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * The reply composer must be the 签到 sheet's surface, not a centred `WindowDialog`.
 *
 * The user asked for 「评论功能的样式换成签到功能的那种」, so the composer now builds on the shared
 * `MiuixBottomSheet` (OS4 glass / OS3 window) with the sheet's own title header and a close
 * affordance, while keeping the field, quote chip, smiley picker, error line, send progress and
 * the no-draft rule (§19.1).
 *
 * These tests are Robolectric: `stringResource` resolves against `values/` (English), so the
 * asserted labels are the English ones. The OS3 branch is the one that renders here — the OS4
 * glass sheet needs the AGSL blur shader, which Robolectric's Skia cannot compile (same limitation
 * recorded for the thread menu and the 签到 panel), so the glass branch is covered by the wiring
 * assertions below only.
 *
 * The sheet is opened through the real [ForumThreadPage] floating button, exactly as a user would,
 * so the FAB → `requestReply()` → sheet wiring is part of the assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplySheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private val repository = FakeThreadRepository()

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                module {
                    single<ForumRepository> { repository }
                    single<ForumDraftRepository> { ReplyFakeDraftRepository() }
                    single<ForumDraftService> { ReplyFakeDraftService() }
                    single<io.rune.mt.forum.domain.forum.repository.CommentFilterRepository> {
                        io.rune.mt.forum.domain.forum.repository.NoopCommentFilterRepository
                    }
                    single<io.rune.mt.forum.domain.forum.repository.ForumBoardPreferencesRepository> {
                        io.rune.mt.forum.domain.forum.repository.NoopForumBoardPreferencesRepository
                    }
                    // The page builds the authenticated attachment downloader eagerly; the test
                    // never downloads, the client only has to exist.
                    single { ForumHttpClient(ForumCookieJar()) }
                    viewModelOf(::ForumViewModel)
                },
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    /** The whole user path: FAB → sheet, then type and close, with no draft affordance anywhere. */
    @Test
    fun `reply FAB opens the sheet with header close field and send`() {
        composeThread()
        compose.waitForIdle()

        // Nothing is composed before the tap: the sheet is the FAB's result, not page decoration.
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()

        openSheet()

        // The sheet header (rules 8/12): title on the sheet, close inside that header row. Each
        // floor card also carries a 回复 badge and a per-floor 回复 action, so only the lower bound
        // is fixed here: the sheet must add at least one more occurrence than the cards.
        assertTrue(
            "the sheet title must add at least one Reply on top of the floor cards",
            compose.onAllNodesWithText("Reply").fetchSemanticsNodes().size >= 2,
        )
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        // The field exists and the footer starts with a disabled 发送 until the field has text.
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithTag(REPLY_SHEET_TAG).assertIsDisplayed()

        // Replies have no draft (§19.1): the composer must never resurrect the draft affordance.
        compose.onNodeWithText("Save draft").assertDoesNotExist()
        compose.onNodeWithText("Restore").assertDoesNotExist()

        // Typing enables send. The field auto-focuses (the sheet keeps the old auto-focus path),
        // so the text goes straight into it.
        compose.onNode(hasSetTextAction()).performTextInput("Thanks, this is useful.")
        compose.waitForIdle()
        compose.onNodeWithText("Thanks, this is useful.").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsEnabled()

        // Closing is a discard, not a draft save: the sheet goes away and the page stays.
        compose.onNodeWithContentDescription("Close").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reply").assertIsDisplayed()
    }

    /**
     * The quoted-floor path must survive the container change: the sheet's own title carries
     * 「回复 @author」 and the quote excerpt is still the chip above the field.
     */
    @Test
    fun `quoted floor shows the quote chip and the addressed title`() {
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
                    MiuixComponentHost(useOs4 = false, useBlur = false) {
                        ReplyComposer(
                            state = ReplyState(
                                visible = true,
                                quoteAuthor = "wuti",
                                quoteText = "有佬友知道目前靠谱的刷课软件吗？",
                            ),
                            onTextChange = {},
                            onSubmit = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithText("Reply to @wuti").assertIsDisplayed()
        compose.onNodeWithText("有佬友知道目前靠谱的刷课软件吗？").assertIsDisplayed()
        // The quote alone is not a reply body: the field is still empty, so send stays disabled.
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Save draft").assertDoesNotExist()
    }

    /**
     * Layout assertion: both call sites are the shared container, and the container is the only
     * place that picks the OS4/OS3 presentation.
     *
     * A Robolectric render cannot prove the OS4 glass branch (AGSL shader), so the style split is
     * pinned at the source level: the helper owns `GlassBottomSheet` and `WindowBottomSheet`, and
     * the 签到 sheet and the reply composer both call that helper. Placement is pinned too: the OS4
     * call sits one indent level deeper (inside the page root `Box`), the OS3 call outside it.
     */
    @Test
    fun `reply sheet and sign-in sheet build on one shared sheet container`() {
        val helper = source("ui/page/miuix/widgets/MiuixBottomSheet.kt")
        val signIn = source("ui/page/forum/ForumSignInSheet.kt")
        val reply = source("ui/page/forum/ReplyComposer.kt")
        val page = source("ui/page/forum/ForumThreadPage.kt")

        assertTrue(
            "the 签到 sheet must build on the shared container",
            signIn.contains("MiuixBottomSheet("),
        )
        assertTrue(
            "the reply composer must build on the shared container",
            reply.contains("MiuixBottomSheet("),
        )
        assertTrue("the OS4 glass branch lives in the helper", helper.contains("GlassBottomSheet("))
        assertTrue("the OS3 window branch lives in the helper", helper.contains("WindowBottomSheet("))
        assertTrue(
            "the helper, not its callers, decides the style",
            helper.contains("if (AppTheme.useMiuixOs4)") && !reply.contains("AppTheme.useMiuixOs4"),
        )

        // The old dialog and the stacked insets must be gone (rule 24): one union() pass only.
        assertFalse("the reply composer must not use its own WindowDialog", reply.contains("WindowDialog("))
        assertFalse("imePadding() would stack with the navigation bar inset", reply.contains(".imePadding()"))
        assertFalse(
            "navigationBarsPadding() would stack with the IME inset",
            reply.contains(".navigationBarsPadding()"),
        )
        assertTrue(
            "the body must take the larger of keyboard/navigation bar once",
            reply.contains("windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))"),
        )

        // Header + close affordance both come from the sheet, not from a hand-rolled row.
        assertTrue(reply.contains("MiuixBottomSheetCloseAction("))
        assertTrue(reply.contains("reply_composer_title"))

        // Placement (rules 8/12): OS4 inline sheet inside the page root Box, OS3 window outside.
        val os4Guard = page.indexOf("if (AppTheme.useMiuixOs4)")
        val os3Guard = page.indexOf("if (!AppTheme.useMiuixOs4)")
        assertTrue("the page must branch the sheet placement by style", os4Guard >= 0 && os3Guard > os4Guard)
        val callIndents = Regex("^[ \t]*ReplyComposer\\(", RegexOption.MULTILINE)
            .findAll(page)
            .map { match -> match.value.takeWhile { it == ' ' }.length }
            .toList()
        assertEquals(
            "one reply-sheet call per style branch; the OS4 call sits inside the page root Box",
            listOf(12, 8),
            callIndents,
        )
        assertTrue(
            "the floating reply button is still the single entry point",
            Regex("viewModel\\.requestReply\\(\\)").findAll(page).count() == 1,
        )
    }

    /**
     * Screenshot evidence: the OS3 reply sheet with text typed.
     *
     * The production OS3 sheet is a `WindowBottomSheet` — a separate dialog *window* — and under
     * Robolectric every compose root hands back the activity window's pixels (the same limitation
     * `ForumRichBodyScreenshotTest` records for the attachment chooser), so a dialog capture cannot
     * show the sheet. The capture therefore draws the **inline** Miuix `OverlayBottomSheet` with the
     * same title, the same close action, the same `insideMargin` and the very same
     * `ReplyComposerBody`. Both Miuix variants are one `BottomSheetContentLayout`, so the corner
     * radius, header row, drag handle and body geometry in the PNG are the production geometry;
     * only the window-level motion (and the OS3 window itself) is not represented.
     *
     * Evidence only: a capture failure must not fail the suite (same convention as the other
     * screenshot tests). The PNG lands in `build/ui-screenshots/reply-sheet.png`; the committed copy
     * lives in `docs/screenshots/`.
     */
    @Test
    fun `capture os3 reply sheet with typed text`() {
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
                    val navigator = remember(backStack) { Navigator(backStack) }
                    MiuixComponentHost(useOs4 = false, useBlur = false) {
                        CompositionLocalProvider(LocalNavigator provides navigator) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // The real thread behind the sheet, exactly as the user sees it.
                                ForumThreadPage(
                                    tid = 177093,
                                    title = "Sample thread",
                                    enableBlur = false,
                                    outerPadding = PaddingValues(),
                                )
                                var bodyState by remember {
                                    mutableStateOf(
                                        ReplyState(
                                            visible = true,
                                            text = "感谢分享，先收藏了。Thanks!",
                                        ),
                                    )
                                }
                                OverlayBottomSheet(
                                    show = true,
                                    onDismissRequest = {},
                                    title = stringResource(R.string.reply_composer_title),
                                    startAction = { MiuixBottomSheetCloseAction(onDismissRequest = {}) },
                                    insideMargin = MiuixBottomSheetInsideMargin,
                                ) {
                                    ReplyComposerBody(
                                        state = bodyState,
                                        onTextChange = { bodyState = bodyState.copy(text = it) },
                                        onSubmit = {},
                                        onDismiss = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        // The sheet's identifying nodes: the title (the floor's 回复 badge is the other "Reply"),
        // the close affordance, the typed text and the now-enabled send button.
        assertTrue(compose.onAllNodesWithText("Reply").fetchSemanticsNodes().size >= 2)
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.onNodeWithText("感谢分享，先收藏了。Thanks!").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsEnabled()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, "reply-sheet.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SCREENSHOT: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure { println("SCREENSHOT skipped: ${it::class.java.name}: ${it.message}") }
    }

    /** Clicks the page's reply FAB and lets the window sheet's enter animation settle. */
    private fun openSheet() {
        compose.onNodeWithContentDescription("Reply").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Composes the real thread page, hosted like the app hosts it. */
    private fun composeThread() {
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
                    val navigator = remember(backStack) { Navigator(backStack) }
                    // The app always mounts this host; the OS4 sheet (and future popups) need its
                    // root Scaffold. useBlur stays off: Robolectric cannot compile the glass blur.
                    MiuixComponentHost(useOs4 = false, useBlur = false) {
                        CompositionLocalProvider(LocalNavigator provides navigator) {
                            ForumThreadPage(
                                tid = 177093,
                                title = "Sample thread",
                                enableBlur = false,
                                outerPadding = PaddingValues(),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Reads a production source file from the module (or repo root) working directory. */
    private fun source(path: String): String {
        val root = "src/main/java/io/rune/mt/forum/"
        val file = File(root + path).takeIf { it.exists() } ?: File("app/$root$path")
        assertTrue("source file not found: $path", file.exists())
        return file.readText()
    }
}

/**
 * [ForumRepository] fake for the thread page: one thread with a `formhash` (the composer's
 * prerequisite) and a working `reply`. Everything else fails loudly if a test starts depending
 * on it — in particular nothing here touches the network.
 */
private class FakeThreadRepository : ForumRepository {

    override val session: StateFlow<ForumSession> = MutableStateFlow(
        ForumSession(
            cookies = mapOf("cQWy_2132_auth" to "test"),
            user = ForumUser(uid = 153466, name = "Rune", isLoggedIn = true),
        ),
    )

    /** The last reply body the page posted, for tests that care about the payload. */
    var lastReplyBody: String? = null

    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = ForumThreadDetail(
        tid = tid,
        fid = 41,
        title = "Sample thread",
        forumName = "综合交流",
        posts = listOf(
            ForumPost(
                pid = 1L,
                tid = tid,
                authorName = "Rune",
                authorUid = 153466,
                authorLevel = "Lv.8",
                timeText = "刚刚",
                contentHtml = "<div>Original post body.</div>",
                floor = 0,
                isOp = true,
            ),
            ForumPost(
                pid = 2L,
                tid = tid,
                authorName = "Other",
                authorUid = 2,
                contentHtml = "<div>A reply floor.</div>",
                floor = 1,
            ),
        ),
        formhash = "16385279",
    )

    override suspend fun reply(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): String {
        lastReplyBody = message
        return "ok"
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

/** Draft plumbing the page's VM asks for at construction; the reply path never touches it. */
private class ReplyFakeDraftRepository : ForumDraftRepository {
    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = null

    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) = Unit

    override suspend fun deleteNewThreadDraft(fid: Int) = Unit

    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = null

    override suspend fun saveReplyDraft(draft: ForumReplyDraft) = Unit

    override suspend fun deleteReplyDraft(tid: Int) = Unit

    override suspend fun draftCount(): Int = 0
}

private class ReplyFakeDraftService : ForumDraftService {
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
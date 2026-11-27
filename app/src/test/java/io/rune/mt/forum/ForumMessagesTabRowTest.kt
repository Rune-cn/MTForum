// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Navigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.forum.ForumMessagesPage
import io.rune.mt.forum.ui.page.forum.ForumMessagesViewModel
import io.rune.mt.forum.ui.page.forum.ForumNoticesViewModel
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixComponentHost
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The 消息 page's four tabs must show their labels in full at phone widths.
 *
 * The owner reported 系统提醒 rendering as 「系统···」: miuix's `TabRow` gives every tab the same
 * width (`available / count` clamped to 76..98dp) and `TabItem` draws the label with a single line
 * plus ellipsis, so at 360dp each tab got ~77dp — about 53dp of text for a 4-character label
 * (~64dp at `body1`'s 16sp).
 *
 * The assertion is a **layout** one, not a pixel one: the merged tab node's measured width must be
 * at least the label's intrinsic width plus `TabItem`'s 12dp padding per side. A tab narrower than
 * that cannot print its label in full, whatever the pixels look like. Intrinsic widths are
 * measured in the same style `TabItem` uses (body1 size, bold, single line, no letter spacing).
 * The fake repository means no network is touched, and the labels come from `values-zh-rCN`
 * because the qualifiers select that locale, matching the strings the owner sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumMessagesTabRowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private val repository = FakeMessagesRepository()

    /** Intrinsic width (px) of each label in the exact style `TabItem` renders it with. */
    private var intrinsicWidths: Map<String, Int> = emptyMap()

    /** The four labels as the page resolves them — read from the zh-rCN resources. */
    private fun tabLabels(): List<String> {
        val resources = RuntimeEnvironment.getApplication().resources
        return listOf(
            resources.getString(R.string.forum_messages_tab_private),
            resources.getString(R.string.forum_notice_tab_system),
            resources.getString(R.string.forum_notice_tab_reply),
            resources.getString(R.string.forum_notice_tab_mypost),
        )
    }

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                module {
                    single<ForumRepository> { repository }
                    single<ImageHostGateway> { TabRowUnusedImageHost }
                    viewModelOf(::ForumMessagesViewModel)
                    viewModelOf(::ForumNoticesViewModel)
                },
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
    fun `every tab label has room at 360dp`() {
        composePage()
        assertLabelsFitTheirTabs()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun `every tab label has room at 411dp`() {
        composePage()
        assertLabelsFitTheirTabs()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun `capture the four full tab labels`() {
        composePage()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, "messages-tab-row.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SCREENSHOT: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure {
            println("SCREENSHOT skipped: ${it::class.java.name}: ${it.message}")
        }
    }

    /**
     * 360dp evidence: the four content-sized tabs exceed the viewport, so the row scrolls and the
     * last tab is cut by the viewport edge — not by an ellipsis inside the label.
     */
    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
    fun `capture the 360dp row`() {
        composePage()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, "messages-tab-row-360.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SCREENSHOT: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure {
            println("SCREENSHOT skipped: ${it::class.java.name}: ${it.message}")
        }
    }

    /**
     * Every tab must be wide enough for its whole label.
     *
     * `TabRow`'s `TabItem` reserves 12dp of horizontal padding on each side and then draws the
     * label with `maxLines = 1` + ellipsis, so a tab narrower than `label + 24dp` **cannot** show
     * the label in full. This is a layout assertion: the merged tab node's measured width is
     * compared with the label's intrinsic width, measured in the same `body1`/bold style the tab
     * renders. Before this fix a 360dp phone gave each tab ~154px at 2x density while "系统提醒"
     * alone needed 128px + 48px of padding, i.e. it was guaranteed to ellipsize.
     */
    private fun assertLabelsFitTheirTabs() {
        val density = compose.density
        val neededPadding = with(density) { TAB_LABEL_PADDING_DP.toPx() } * 2
        tabLabels().forEach { label ->
            val labelWidth = intrinsicWidths[label]
                ?: error("no measured width for \"$label\"; composePage() must run first")
            val tab = compose.onNodeWithText(label).fetchSemanticsNode()
            val tabWidth = tab.size.width
            assertTrue(
                "tab \"$label\" is ${tabWidth}px wide but needs at least " +
                    "${labelWidth + neededPadding}px (label ${labelWidth}px + 24dp padding), " +
                    "so the label would be ellipsized",
                tabWidth >= labelWidth + neededPadding,
            )
        }
    }

    private fun composePage() {
        val labels = tabLabels()
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
                    // Measure every label the way `TabItem` renders it: body1 size, the selected
                    // tab's bold weight, one line, no letter spacing.
                    val textMeasurer = rememberTextMeasurer()
                    val tabLabelStyle = MiuixTheme.textStyles.body1.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.sp,
                    )
                    intrinsicWidths = labels.associateWith { label ->
                        textMeasurer.measure(
                            text = AnnotatedString(label),
                            style = tabLabelStyle,
                            maxLines = 1,
                            softWrap = false,
                        ).size.width
                    }
                    val backStack = rememberNavBackStack<Route>(Route.Main)
                    val navigator = Navigator(backStack)
                    MiuixComponentHost(useOs4 = false, useBlur = false) {
                        CompositionLocalProvider(LocalNavigator provides navigator) {
                            ForumMessagesPage(
                                enableBlur = false,
                                title = "消息",
                                outerPadding = androidx.compose.foundation.layout.PaddingValues(
                                    bottom = 0.dp,
                                ),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        /** `TabItem`'s label padding per side (`TabRow.kt`); a narrower tab must ellipsize. */
        val TAB_LABEL_PADDING_DP = 12.dp
    }
}

/** Logged-in session plus one conversation; nothing else is wired. */
private class FakeMessagesRepository : ForumRepository {

    private val sessionFlow = MutableStateFlow(
        ForumSession(
            cookies = mapOf("cQWy_2132_auth" to "test"),
            user = ForumUser(uid = 1, name = "Rune", isLoggedIn = true),
        ),
    )
    override val session: StateFlow<ForumSession> = sessionFlow

    override suspend fun getConversations(page: Int): List<ForumPrivateConversation> = listOf(
        ForumPrivateConversation(
            uid = 7,
            name = "好友 七号",
            avatarUrl = null,
            lastPreview = "最近一条私信",
            lastTimeText = "1 小时前",
        ),
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

/** The tab row never uploads; the page just needs the gateway to construct its view model. */
private object TabRowUnusedImageHost : ImageHostGateway {
    override suspend fun upload(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): io.rune.mt.forum.domain.forum.model.ImageHostResult =
        error("the tab row test must not upload")
}

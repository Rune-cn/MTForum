// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.forum.ForumThreadCard
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScrollBar
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * The long lists now overlay the Miuix scroll bar, the way the example pages do
 * (`MainPage.kt`, `SettingsPage.kt`, `ComponentPage.kt`, and `PullToRefreshPage.kt` — the last
 * one proves the bar coexists with pull-to-refresh because it is a sibling of the list).
 *
 * There is no emulator on this machine, so the geometry is measured through Robolectric: the
 * track spans the page height at the trailing edge. The captures are visual evidence only and
 * must not fail the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixPageScrollBarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    /// The list padding the production pages pass to both the list and the bar.
    private val contentPadding = PaddingValues(start = 12.dp, top = 64.dp, end = 12.dp, bottom = 24.dp)

    private fun threads(count: Int): List<ForumThread> = (1..count).map { index ->
        ForumThread(
            tid = 100000 + index,
            title = "示例主题 $index：一个足够长的标题用来占满两行，检查卡片与滚动条的关系",
            authorName = "user$index",
            authorUid = 1000 + index,
            authorAvatarUrl = null,
            replyCount = index * 3,
            viewCount = index * 41,
            summary = "这是列表摘要，用来让每一行有两行以上的高度，滚动条因此有可滚动的范围。",
        )
    }

    @Test
    fun `scroll bar track sits at the trailing edge over the full page height`() {
        composePage(useOs4 = true)
        compose.waitForIdle()

        val page = compose.onNodeWithTag(PAGE).rect()
        val bar = compose.onNodeWithTag(SCROLL_BAR).rect()

        // fillMaxHeight + CenterEnd: the track covers the page vertically and hugs the right edge.
        assertEquals(page.height, bar.height, 1f, "the scroll bar must span the page height")
        assertTrue(bar.right <= page.right, "bar right ${bar.right} must stay inside the page ${page.right}")
        assertTrue(
            page.right - bar.right <= with(compose.density) { 16.dp.toPx() },
            "the bar must sit at the trailing edge: inset=${page.right - bar.right}px",
        )
    }

    @Test
    fun `thread cards keep rendering beside the new overlay`() {
        composePage(useOs4 = false)
        compose.waitForIdle()
        compose.onNodeWithTag(SCROLL_BAR).assertExists()
        compose.onNodeWithText(threads(1).first().title).assertIsDisplayed()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, "os3-list-scrollbar.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("[capture] wrote ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure { println("[capture] skipped: ${it::class.java.name}: ${it.message}") }
    }

    @Test
    fun `capture the os4 page with the scroll bar overlay`() {
        composePage(useOs4 = true)
        compose.waitForIdle()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, "os4-page-scrollbar.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("[capture] wrote ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure { println("[capture] skipped: ${it::class.java.name}: ${it.message}") }
    }

    /**
     * The production shape: a page `Box`, the list, and the bar as the box's last child — exactly
     * what `MiuixPageScaffold` and the pull-to-refresh pages lay out.
     */
    private fun composePage(useOs4: Boolean) {
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
                    Box(modifier = Modifier.fillMaxSize().background(PageSurface).testTag(PAGE)) {
                        val state = rememberLazyListState()
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = state,
                            contentPadding = contentPadding,
                        ) {
                            items(items = threads(12)) { thread ->
                                ForumThreadCard(thread = thread, onClick = {})
                            }
                        }
                        // No top-bar behavior in this harness: the page bar's own coupling is
                        // covered by the pages, the track layout is what is measured here.
                        MiuixPageScrollBar(
                            listState = state,
                            contentPadding = contentPadding,
                            scrollBehavior = null,
                            modifier = Modifier.testTag(SCROLL_BAR),
                        )
                    }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.rect(): Rect =
        fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }

    private companion object {
        const val PAGE = "scrollbar_page"
        const val SCROLL_BAR = "page_scroll_bar"
        val PageSurface = Color(0xFFF2F3F5)
    }
}
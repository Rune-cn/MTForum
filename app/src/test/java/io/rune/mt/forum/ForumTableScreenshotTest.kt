// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import io.rune.mt.forum.ui.page.forum.ForumHtmlText
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * Visual proof for the 「表格方面显示不行」 fix: the real five-column 网盘 table from thread 156696.
 *
 * The window is made wide enough that the whole table is visible in one frame, so the screenshot
 * shows the *result of the weighting* (a wide 备注 column, narrow 网盘名称 column, centred header)
 * rather than only the left half that a 411dp phone shows before scrolling. On a phone the same
 * table keeps its column widths and scrolls horizontally inside the card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w1000dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumTableScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    @Test
    fun `netdisk five column table renders with content sized columns`() {
        val path = "src/test/resources/forum/rich_156696.html"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        val opHtml = ForumHtmlParser.parseThreadDetail(file.readText(), tid = 156696, page = 1)
            .posts.first { it.contentHtml.contains("<table") }.contentHtml
        val tableHtml = Jsoup.parseBodyFragment(opHtml).select("table").first()!!.outerHtml()

        // Guard: the renderer really receives a five-column table with a header row.
        val table = ForumBodyParser.parse(tableHtml).filterIsInstance<ForumBodyBlock.Table>().single()
        assertEquals("five columns expected", 5, table.rows.first().size)

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
                    useMiuixOs4 = true,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF2F3F5))
                            .padding(16.dp),
                    ) {
                        ForumHtmlText(html = tableHtml, onLinkClick = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("网盘名称").assertIsDisplayed()
        compose.onNodeWithText("备注说明").assertIsDisplayed()

        capture("10_netdisk_table.png")
    }

    /**
     * The same table on a phone-width window: the computed width exceeds the card, so it must
     * scroll horizontally rather than squeeze the columns. This exercises the scroll safety net
     * (and proves it does not crash / drop the table) — the leftmost column stays visible.
     */
    @Test
    @Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h1400dp-xhdpi")
    fun `netdisk table scrolls horizontally on a phone width`() {
        val path = "src/test/resources/forum/rich_156696.html"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        val opHtml = ForumHtmlParser.parseThreadDetail(file.readText(), tid = 156696, page = 1)
            .posts.first { it.contentHtml.contains("<table") }.contentHtml
        val tableHtml = Jsoup.parseBodyFragment(opHtml).select("table").first()!!.outerHtml()

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
                    useMiuixOs4 = true,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF2F3F5))
                            .padding(16.dp),
                    ) {
                        ForumHtmlText(html = tableHtml, onLinkClick = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("网盘名称").assertIsDisplayed()
        capture("10b_netdisk_table_phone.png")
    }

    private fun capture(name: String) {
        val view = compose.activity.window.decorView
        val width = view.width
        val height = view.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File(outputDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
    }
}

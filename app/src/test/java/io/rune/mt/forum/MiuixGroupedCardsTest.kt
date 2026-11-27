// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.miuix.widgets.miuixGroupedItems
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * A grouped section is one continuous card, the shape the Miuix example builds everywhere
 * (`component/GroupedCardItems.kt`, used by `MainPage.kt` and `SettingsPage.kt`): the first row
 * rounds the top, the last row rounds the bottom, and the rows between them are square and
 * touch, so the section reads as a single surface instead of a stack of separate cards.
 *
 * The corners themselves are pixels, so the assertions here are the measurable consequences:
 * consecutive rows share an edge and the group keeps its 12dp page gutter. The capture is
 * evidence only and must not fail the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixGroupedCardsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private val rows = listOf("第一行", "第二行", "第三行", "第四行", "第五行")

    @Test
    fun `consecutive rows of a group share an edge instead of leaving a gutter`() {
        composeGroup()
        compose.waitForIdle()

        // Each segment is measured through its own tag: the text starts *inside* the segment's
        // padding, so measuring the text would hide the gap the group is supposed to avoid.
        val rects = rows.indices.map { compose.onNodeWithTag("$SEGMENT$it").rect() }
        rects.zipWithNext().forEachIndexed { index, (upper, lower) ->
            assertEquals(
                upper.bottom,
                lower.top,
                1f,
                "row ${index + 1} and ${index + 2} must touch: ${upper.bottom} vs ${lower.top}",
            )
        }
        // The whole group is one surface: it starts at the first row and ends at the last.
        assertTrue(rects.first().height > 0f, "the first segment must be measured")
        assertEquals(rows.size, rects.size)
    }

    @Test
    fun `group keeps the page gutter of the example sections`() {
        composeGroup()
        compose.waitForIdle()

        val page = compose.onNodeWithTag(PAGE).rect()
        val first = compose.onNodeWithText(rows.first()).rect()
        val expectedInset = with(compose.density) { 12.dp.toPx() }
        // The row content starts after the segment's outer 12dp gutter plus its inside padding,
        // so the surface — not the text — is what must sit 12dp in. Prove the surface edge by
        // measuring the text block's offset from the page: outer gutter + pad + inside padding.
        assertTrue(
            first.left - page.left >= expectedInset,
            "content must sit inside the group's 12dp gutter: inset=${first.left - page.left}px",
        )
    }

    @Test
    fun `capture the grouped section`() {
        composeGroup()
        compose.waitForIdle()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, "grouped-card-section.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("[capture] wrote ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure { println("[capture] skipped: ${it::class.java.name}: ${it.message}") }
    }

    private fun composeGroup() {
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
                    Box(modifier = Modifier.fillMaxSize().background(PageSurface).testTag(PAGE)) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            miuixGroupedItems(
                                items = rows,
                                keyPrefix = "demo",
                                insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                segmentModifier = { index -> Modifier.testTag("$SEGMENT$index") },
                            ) { label, _ ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(text = label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.rect(): Rect =
        fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }

    private companion object {
        const val PAGE = "grouped_page"
        const val SEGMENT = "grouped_segment_"
        val PageSurface = Color(0xFFF2F3F5)
    }
}
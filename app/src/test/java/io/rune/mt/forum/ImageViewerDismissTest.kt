// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.ui.page.forum.VIEWER_DISMISS_SCALE_DELTA
import io.rune.mt.forum.ui.page.forum.imageViewerDismissVisuals
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lightbox's predictive-back mapping (user request: 灯箱页面加上预见式返回).
 *
 * The pure mapping is asserted here; the gesture wiring itself is pinned by a source contract, the
 * same way the thread menu's dismissal is (there is no emulator on this machine, so a real swipe
 * cannot be driven).
 */
class ImageViewerDismissTest {

    @Test
    fun `at rest the lightbox is untouched`() {
        val visuals = imageViewerDismissVisuals(0f)
        assertEquals(1f, visuals.scale, 0.0001f)
        assertEquals(1f, visuals.alpha, 0.0001f)
        assertEquals("the scrim must stay at its resting opacity", 0.96f, visuals.scrimAlpha, 0.0001f)
    }

    @Test
    fun `at commit the picture is fully faded`() {
        val visuals = imageViewerDismissVisuals(1f)
        assertEquals(1f - VIEWER_DISMISS_SCALE_DELTA, visuals.scale, 0.0001f)
        assertEquals(0f, visuals.alpha, 0.0001f)
        assertEquals(0f, visuals.scrimAlpha, 0.0001f)
    }

    @Test
    fun `progress is monotonic and clamped`() {
        val samples = listOf(-1f, 0f, 0.25f, 0.5f, 0.75f, 1f, 2f).map { imageViewerDismissVisuals(it) }
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue("scale must never grow with progress", b.scale <= a.scale + 0.0001f)
            assertTrue("alpha must never grow with progress", b.alpha <= a.alpha + 0.0001f)
            assertTrue("the scrim must never grow with progress", b.scrimAlpha <= a.scrimAlpha + 0.0001f)
        }
        assertEquals("a negative drag clamps to rest", 1f, samples.first().alpha, 0.0001f)
        assertEquals("an oversized drag clamps to commit", 0f, samples.last().alpha, 0.0001f)
    }

    @Test
    fun `the handler lives inside the dialog and is progress driven`() {
        val candidates = listOf(
            File("src/main/java/io/rune/mt/forum/ui/page/forum/ForumImageViewer.kt"),
            File("app/src/main/java/io/rune/mt/forum/ui/page/forum/ForumImageViewer.kt"),
        )
        val source = candidates.firstOrNull { it.exists() }?.readText()
            ?: error("cannot locate ForumImageViewer.kt")
        assertTrue("the lightbox needs PredictiveBackHandler", source.contains("PredictiveBackHandler("))
        assertTrue(
            "the drag must feed a progress state, not a boolean",
            source.contains("dismissProgress = event.progress"),
        )
        assertTrue(
            "the transform must be applied through graphicsLayer",
            source.contains("scaleX = scale * visuals.scale"),
        )
        assertTrue(
            "the handler must be registered after the Dialog opens (inside its content)",
            source.indexOf("Dialog(") < source.indexOf("PredictiveBackHandler("),
        )
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.ui.page.forum.MENU_DISMISS_ALPHA_DELTA
import io.rune.mt.forum.ui.page.forum.MENU_DISMISS_SCALE_DELTA
import io.rune.mt.forum.ui.page.forum.MENU_DISMISS_TRANSLATION_DP
import io.rune.mt.forum.ui.page.forum.threadMenuDismissVisuals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Predictive-back transform for the thread overflow menu.
 *
 * The menu used to vanish on commit with no preview. These assertions pin the mapping the glass
 * panel renders during the gesture: identity at rest (so the open menu looks unchanged), full
 * fade at commit, monotonic in between, and clamped outside the gesture range.
 */
class ThreadActionsDismissVisualsTest {

    @Test
    fun `progress zero is the identity transform`() {
        val v = threadMenuDismissVisuals(0f)
        assertEquals(1f, v.scale, 0.0001f)
        assertEquals(1f, v.alpha, 0.0001f)
        assertEquals(0f, v.translationDp, 0.0001f)
    }

    @Test
    fun `progress one fades the panel out`() {
        val v = threadMenuDismissVisuals(1f)
        assertEquals(1f - MENU_DISMISS_SCALE_DELTA, v.scale, 0.0001f)
        assertEquals(1f - MENU_DISMISS_ALPHA_DELTA, v.alpha, 0.0001f)
        assertEquals(MENU_DISMISS_TRANSLATION_DP, v.translationDp, 0.0001f)
        assertTrue(v.alpha <= 0.15f, "the panel must be effectively invisible at commit")
        assertTrue(v.scale >= 0.9f, "the scale change must stay subtle")
    }

    @Test
    fun `progress is monotonic and clamped`() {
        var previous = threadMenuDismissVisuals(0f)
        for (i in 1..100) {
            val v = threadMenuDismissVisuals(i / 100f)
            assertTrue(v.scale <= previous.scale + 0.0001f, "scale must not rise")
            assertTrue(v.alpha <= previous.alpha + 0.0001f, "alpha must not rise")
            assertTrue(v.translationDp >= previous.translationDp - 0.0001f, "translation must not retreat")
            previous = v
        }
        assertEquals(threadMenuDismissVisuals(0f).scale, threadMenuDismissVisuals(-1f).scale, 0.0001f)
        assertEquals(threadMenuDismissVisuals(1f).alpha, threadMenuDismissVisuals(2f).alpha, 0.0001f)
    }
}
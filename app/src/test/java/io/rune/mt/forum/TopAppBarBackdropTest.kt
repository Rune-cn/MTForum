// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.rune.mt.forum.ui.page.miuix.widgets.barBackdrop
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * The top app bar may only sample a layer that does **not** contain the bar itself.
 *
 * The Miuix example does this structurally: `MainPage.kt` records the page backdrop on a `Box`
 * inside `Scaffold`'s content and passes it to `BlurredBar`, so the bar samples the body. This app
 * additionally has a host-level layer (`MiuixComponentScope.pageBackdrop`) recorded around the
 * whole navigation tree; mounting a bar on it is the RenderNode self-sampling cycle that killed
 * the render thread on a device (HANDOVER §11 / §15.9).
 *
 * [barBackdrop] is the single decision point for that rule, so it is asserted directly: the
 * selector is pure, and its inputs are real `LayerBackdrop` instances (miuix's class is final, so
 * they come from `rememberLayerBackdrop` under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class TopAppBarBackdropTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `bar samples the page content layer`() {
        val (content, page) = layers()
        val chosen = barBackdrop(content, page)
        assertSame(content, chosen, "the bar must sample the page body layer")
    }

    @Test
    fun `bar refuses the host layer that contains it`() {
        val (content, page) = layers()
        // A caller wiring the host layer into the bar slot by mistake: refuse it rather than
        // building a sampling path that contains the bar.
        assertNull(barBackdrop(page, page), "the host layer must never be sampled by a bar")
        assertNull(barBackdrop(contentBackdrop = null, hostBackdrop = page), "no content layer, no sampling")
    }

    @Test
    fun `no layer means the opaque bar`() {
        assertNull(barBackdrop(null, null), "without a layer the bar paints its opaque surface")
    }

    /** Real backdrops, materialised through the library's own factory. */
    private fun layers(): Pair<LayerBackdrop, LayerBackdrop> {
        var content: LayerBackdrop? = null
        var page: LayerBackdrop? = null
        compose.setContent {
            content = rememberLayerBackdrop { drawRect(Color.Black) }
            page = rememberLayerBackdrop { drawRect(Color.White) }
        }
        compose.waitForIdle()
        return checkNotNull(content) to checkNotNull(page)
    }
}
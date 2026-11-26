// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.forum.ThreadActionItem
import io.rune.mt.forum.ui.page.forum.ThreadActions
import io.rune.mt.forum.ui.page.forum.ThreadActionsBackHandler
import io.rune.mt.forum.ui.page.forum.ThreadActionsGlassMenu
import io.rune.mt.forum.ui.page.forum.ThreadActionsState
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopAppBar
import io.rune.mt.forum.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Layout regression tests for the thread screen's overflow menu under the OS4 glass style.
 *
 * `GlassPopup` is an in-page overlay, not a popup window: it lays the panel out from window
 * coordinates and clamps it into `intersect(window, host)`. The original code composed it inside
 * the top bar's `actions` cell, so the host was that cell instead of the page — the panel was
 * clamped to the wrong side of the screen and the oversized host distorted the bar itself. There
 * is no emulator in this environment (nested virtualisation is blocked), so the geometry is
 * measured here through Robolectric + the real widgets instead of on a device.
 *
 * The rows are the library's own `GlassPopupItem` (icon slot + 48dp touch target), so a row's
 * merged semantics node is still the full-width selectable area the geometry maths relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadActionsPopupLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val items = listOf(
        ThreadActionItem("Favourite", Icons.Rounded.FavoriteBorder) {},
        ThreadActionItem("Like", Icons.Rounded.ThumbUp) {},
        ThreadActionItem("Follow author", Icons.Rounded.PersonAdd) {},
        ThreadActionItem("Copy link", Icons.Rounded.Link) {},
        ThreadActionItem("Share", Icons.Rounded.Share) {},
    )

    /** The production rows, with labels resolved from the (English) Robolectric resources. */
    private fun productionItems(): List<ThreadActionItem> {
        val context = RuntimeEnvironment.getApplication()
        return listOf(
            ThreadActionItem(context.getString(R.string.thread_action_favorite), Icons.Rounded.FavoriteBorder) {},
            ThreadActionItem(context.getString(R.string.thread_action_like), Icons.Rounded.ThumbUp) {},
            ThreadActionItem(context.getString(R.string.thread_action_follow_author), Icons.Rounded.PersonAdd) {},
            ThreadActionItem(context.getString(R.string.thread_action_copy_link), Icons.Rounded.Link) {},
            ThreadActionItem(context.getString(R.string.thread_action_share), Icons.Rounded.Share) {},
        )
    }

    /** Panel edges must land within 2 dp of the anchor / window edges. */
    private val tolerancePx: Float
        get() = with(compose.density) { 2.dp.toPx() }

    private val contentPaddingPx: Float
        get() = with(compose.density) { GlassPopupDefaults.ContentPaddingVertical.toPx() }

    /**
     * Horizontal inset `GlassPopupItem` applies before its selectable area, so a menu row's
     * reported rect is this much narrower than the panel on each side.
     */
    private val itemInsetPx: Float
        get() = with(compose.density) { GlassPopupDefaults.ItemPressInset.toPx() }

    @Test
    fun `glass menu rows keep the 48dp platform touch target`() {
        val state = ThreadActionsState()
        composePage(useOs4 = true, state = state, menuAtRoot = true)
        compose.waitForIdle()
        openMenu()

        // Every row is taller than the library's bare 44dp because the row modifier raises the
        // full-width selectable area to the 48dp touch-target minimum. The icon + label of each
        // row is the same production `GlassPopupItem`; the menu capture below shows it.
        val minRowHeight = with(compose.density) { 48.dp.toPx() }
        items.forEach { item ->
            val row = compose.onNodeWithText(item.label).rect()
            assertTrue(
                row.height >= minRowHeight - 1f,
                "row '${item.label}' must expose a >=48dp hit target: got ${row.height}px, " +
                    "minimum ${minRowHeight}px",
            )
        }
    }

    @Test
    fun `glass menu opens end aligned with the anchor at the page root`() {
        val state = ThreadActionsState()
        composePage(useOs4 = true, state = state, menuAtRoot = true)
        compose.waitForIdle()
        openMenu()

        val root = rect(PAGE)
        val host = rect(HOST)
        val anchor = rect(ANCHOR)
        val firstRow = compose.onNodeWithText("Favourite").rect()
        val lastRow = compose.onNodeWithText("Share").rect()
        // A menu row reports the inside of its press inset; grow it back to the panel's edges.
        val panelLeft = firstRow.left - itemInsetPx
        val panelRight = firstRow.right + itemInsetPx
        val panelTop = firstRow.top - contentPaddingPx
        val panelBottom = lastRow.bottom + contentPaddingPx

        println(
            "[fixed] root=${root.width}x${root.height} host=${host.width}x${host.height} " +
                "anchor=$anchor panel=($panelLeft,$panelTop,$panelRight,$panelBottom)",
        )

        // 1. The popup host must be the page, not the top-bar action cell.
        assertEquals(root.width, host.width, tolerancePx, "popup host width must equal the page width")
        assertEquals(root.height, host.height, tolerancePx, "popup host height must equal the page height")

        // With these short labels the icon rows still settle at the glass menu's minimum width.
        assertEquals(
            with(compose.density) { GlassPopupDefaults.MinWidth.toPx() },
            panelRight - panelLeft,
            tolerancePx,
            "panel must be the settled ${GlassPopupDefaults.MinWidth} wide",
        )

        // The library only end-aligns without clamping when the anchor keeps `safeMargin` from
        // the window edge; assert the harness is in that regime so the next check is meaningful.
        val safeMargin = with(compose.density) { GlassPopupDefaults.SafeMargin.toPx() }
        assertTrue(
            anchor.right <= root.right - safeMargin,
            "harness anchor must sit inside the safe margin: anchor.right=${anchor.right} " +
                "root.right=${root.right} margin=$safeMargin",
        )

        // 2. The panel's right edge must line up with the button's right edge.
        assertEquals(anchor.right, panelRight, tolerancePx, "panel right edge must align with the anchor")
        // 3. ...and its top edge with the anchor's top edge (opens downward, aligned at the top).
        assertEquals(anchor.top, panelTop, tolerancePx, "panel top edge must align with the anchor")

        // 4. The panel must be fully inside the window.
        assertTrue(panelLeft >= root.left, "panel left $panelLeft must not leave the window")
        assertTrue(panelRight <= root.right, "panel right $panelRight must not leave the window")
        assertTrue(panelTop >= root.top, "panel top $panelTop must not leave the window")
        assertTrue(panelBottom <= root.bottom, "panel bottom $panelBottom must not leave the window")
    }

    @Test
    fun `menu composed in the top bar cell is measured against that cell`() {
        // Counterfactual: the pre-fix nesting, built from the same production composables. It
        // exists to prove the host-size assertion above actually detects the bug (it fails here)
        // and to record the numbers the fix moved.
        val state = ThreadActionsState()
        composePage(useOs4 = true, state = state, menuAtRoot = false)
        compose.waitForIdle()
        openMenu()

        val root = rect(PAGE)
        val host = rect(HOST)
        val anchor = rect(ANCHOR)
        val row = compose.onNodeWithText("Favourite").rect()
        val panelRight = row.right + itemInsetPx

        println(
            "[pre-fix] root=${root.width}x${root.height} host=${host.width}x${host.height} " +
                "anchor=$anchor panelRight=$panelRight",
        )

        assertTrue(
            host.width != root.width || host.height != root.height,
            "nesting the menu in the actions cell must NOT produce a page-sized host " +
                "(got ${host.width}x${host.height} for a ${root.width}x${root.height} page)",
        )
        assertTrue(
            host.width < root.width,
            "the cell host is narrower than the page (${root.width}px): got ${host.width}px",
        )
    }

    @Test
    fun `classic style keeps using the window list popup and composes no glass overlay`() {
        val state = ThreadActionsState()
        composePage(useOs4 = false, state = state, menuAtRoot = true)
        compose.waitForIdle()
        openMenu()

        // OS3 menu lives in a real popup window; the glass overlay must stay out of the tree.
        compose.onNodeWithText("Favourite").assertExists()
        compose.onNodeWithTag(HOST).assertDoesNotExist()
        assertTrue(state.expanded, "the shared menu state must be open under OS3 too")
    }

    @Test
    fun `glass menu rows show the five english action labels`() {
        val expected = listOf("Favorite", "Upvote this thread", "Follow the author", "Copy link", "Share")
        val rows = productionItems()
        assertEquals(expected, rows.map { it.label }, "Robolectric must resolve the English default strings")

        val state = ThreadActionsState()
        composePage(useOs4 = true, state = state, menuAtRoot = true, menuItems = rows)
        compose.waitForIdle()
        openMenu()

        expected.forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun `back dismisses the open menu instead of popping the page`() {
        val state = ThreadActionsState()
        val probe = NavBackProbe()
        composePage(useOs4 = true, state = state, menuAtRoot = true, probe = probe)
        compose.waitForIdle()

        // Sanity: with the menu shut the page's callback is disabled, so back reaches the
        // nav-level callback (the stand-in for NavDisplay's predictive-back driver).
        pressBack()
        assertEquals(1, probe.backCount, "back must reach the nav handler while the menu is shut")

        openMenu()
        assertTrue(state.expanded, "the menu must be open before pressing back")

        pressBack()
        assertFalse(state.expanded, "back must close the open menu")
        assertEquals(1, probe.backCount, "dismissing the menu must not pop the thread")

        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("Share").assertDoesNotExist()
    }

    @Test
    fun `classic popup state closes on system back`() {
        val state = ThreadActionsState()
        val probe = NavBackProbe()
        composePage(useOs4 = false, state = state, menuAtRoot = true, probe = probe)
        compose.waitForIdle()
        openMenu()

        pressBack()
        assertFalse(state.expanded, "back must close the shared menu state under OS3 too")
        assertEquals(0, probe.backCount, "closing the menu must not let nav consume the back")
    }

    @Test
    fun `capture the open OS4 menu`() {
        val state = ThreadActionsState()
        composePage(useOs4 = true, state = state, menuAtRoot = true, menuItems = productionItems())
        compose.waitForIdle()
        openMenu()

        // Screenshots are visual evidence only; a capture failure must not fail the suite.
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/ui-screenshots").apply { mkdirs() }
            val file = File(dir, "os4-thread-actions-menu.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("[capture] wrote ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure { println("[capture] skipped: ${it::class.java.name}: ${it.message}") }
    }

    @Test
    fun `capture the OS4 menu mid predictive dismiss`() {
        val state = ThreadActionsState()
        composePage(useOs4 = true, state = state, menuAtRoot = true, menuItems = productionItems())
        compose.waitForIdle()
        openMenu()

        // Feed half of a predictive-back drag: the panel must still be on screen but shrinking
        // and fading, which is the preview the old BackHandler never rendered.
        compose.runOnUiThread { state.dismissProgress = 0.5f }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.onNodeWithText("Share").assertExists()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/ui-screenshots").apply { mkdirs() }
            val file = File(dir, "os4-thread-actions-predictive-back.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("[capture] wrote ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure { println("[capture] skipped: ${it::class.java.name}: ${it.message}") }
    }

    private fun openMenu() {
        compose.onNodeWithTag(ANCHOR).performClick()
        // Let the glass morph/unfurl animation settle before measuring the final panel geometry.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun pressBack() {
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /**
     * The thread page's real composition, minus the ViewModel-driven content: a fillMaxSize page
     * box, the Miuix top bar with [ThreadActions] in its `actions` slot, the page-root back
     * handler, and (for the fixed case) [ThreadActionsGlassMenu] as the page box's last child.
     */
    private fun composePage(
        useOs4: Boolean,
        state: ThreadActionsState,
        menuAtRoot: Boolean,
        menuItems: List<ThreadActionItem> = items,
        probe: NavBackProbe? = null,
    ) {
        compose.setContent {
            // Robolectric's bundled Skia cannot compile miuix's squircle AGSL shader
            // ("'color' is not a valid layout qualifier" in ShadowNativeRuntimeShader), so the
            // squircle fallback shape is used. It only changes corner drawing, not layout.
            CompositionLocalProvider(LocalSquircleEnabled provides false) {
                AppTheme(
                    useMiuix = true,
                    themeMode = ThemeMode.LIGHT,
                    paletteStyle = PaletteStyle.TonalSpot,
                    colorSpec = ThemeColorSpec.SPEC_2021,
                    useDynamicColor = false,
                    useMiuixMonet = false,
                    seedColor = androidx.compose.ui.graphics.Color(0xFF2E9E5B),
                    useMiuixOs4 = useOs4,
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag(PAGE)) {
                        // Composed first so its callback is the nav-level (older) one; the page
                        // handler registered below must take priority only while it is enabled.
                        if (probe != null) NavBackProbeEffect(probe)
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            topBar = {
                                MiuixTopAppBar(
                                    title = "thread",
                                    backdrop = null,
                                    actions = { ThreadActionsSlot(state, menuAtRoot, menuItems) },
                                )
                            },
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize().padding(innerPadding))
                        }
                        if (menuAtRoot) {
                            ThreadActionsGlassMenu(
                                items = menuItems,
                                state = state,
                                modifier = Modifier.testTag(HOST),
                            )
                        }
                        // The production page root installs exactly this handler.
                        ThreadActionsBackHandler(state = state)
                    }
                }
            }
        }
    }

    @Composable
    private fun ThreadActionsSlot(
        state: ThreadActionsState,
        menuAtRoot: Boolean,
        menuItems: List<ThreadActionItem>,
    ) {
        if (menuAtRoot) {
            ThreadActions(items = menuItems, state = state, modifier = Modifier.testTag(ANCHOR))
        } else {
            // Pre-fix structure: the overlay shares the button's small cell.
            Box {
                ThreadActions(items = menuItems, state = state, modifier = Modifier.testTag(ANCHOR))
                ThreadActionsGlassMenu(items = menuItems, state = state, modifier = Modifier.testTag(HOST))
            }
        }
    }

    @Composable
    private fun NavBackProbeEffect(probe: NavBackProbe) {
        val owner = checkNotNull(LocalOnBackPressedDispatcherOwner.current) {
            "the compose test host must provide an OnBackPressedDispatcherOwner"
        }
        DisposableEffect(owner) {
            val callback = probe.newCallback()
            owner.onBackPressedDispatcher.addCallback(callback)
            onDispose { callback.remove() }
        }
    }

    /** Unclipped bounds of a node in root pixels, so overflow past a parent is visible. */
    private fun SemanticsNodeInteraction.rect(): Rect {
        val node = fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    private fun rect(tag: String): Rect = compose.onNodeWithTag(tag).rect()

    /**
     * Stand-in for NavDisplay's nav-level back callback: registered before the page content, so
     * the page's handler only wins while it is enabled (the menu is open).
     */
    private class NavBackProbe {
        var backCount = 0

        fun newCallback(): OnBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backCount++
            }
        }
    }

    private companion object {
        const val PAGE = "page_root"
        const val ANCHOR = "thread_actions_anchor"
        const val HOST = "thread_actions_popup_host"
    }
}

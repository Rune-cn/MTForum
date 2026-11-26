// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
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
import io.rune.mt.forum.ui.page.forum.ThreadActionsGlassMenu
import io.rune.mt.forum.ui.page.forum.ThreadActionsState
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopAppBar
import io.rune.mt.forum.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import java.io.File
import kotlin.test.assertEquals
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadActionsPopupLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = listOf(
        ThreadActionItem("Favourite", {}),
        ThreadActionItem("Like", {}),
        ThreadActionItem("Follow author", {}),
        ThreadActionItem("Copy link", {}),
        ThreadActionItem("Share", {}),
    )

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

        // The panel settles at the glass menu's minimum width.
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
    fun `capture the open OS4 menu`() {
        val state = ThreadActionsState()
        composePage(useOs4 = true, state = state, menuAtRoot = true)
        compose.waitForIdle()
        openMenu()

        // Screenshots are visual evidence only; a capture failure must not fail the suite.
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File(repoRoot(), "artifacts").apply { mkdirs() }
            val file = File(dir, "os4-thread-actions-menu.png")
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

    /**
     * The thread page's real composition, minus the ViewModel-driven content: a fillMaxSize page
     * box, the Miuix top bar with [ThreadActions] in its `actions` slot, and (for the fixed case)
     * [ThreadActionsGlassMenu] as the page box's last child.
     */
    private fun composePage(useOs4: Boolean, state: ThreadActionsState, menuAtRoot: Boolean) {
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
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            MiuixTopAppBar(
                                title = "thread",
                                backdrop = null,
                                actions = { ThreadActionsSlot(state, menuAtRoot) },
                            )
                        },
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding))
                    }
                    if (menuAtRoot) {
                        ThreadActionsGlassMenu(items = items, state = state, modifier = Modifier.testTag(HOST))
                    }
                }
                }
            }
        }
    }

    @Composable
    private fun ThreadActionsSlot(state: ThreadActionsState, menuAtRoot: Boolean) {
        if (menuAtRoot) {
            ThreadActions(items = items, state = state, modifier = Modifier.testTag(ANCHOR))
        } else {
            // Pre-fix structure: the overlay shares the button's small cell.
            Box {
                ThreadActions(items = items, state = state, modifier = Modifier.testTag(ANCHOR))
                ThreadActionsGlassMenu(items = items, state = state, modifier = Modifier.testTag(HOST))
            }
        }
    }

    /** Unclipped bounds of a node in root pixels, so overflow past a parent is visible. */
    private fun SemanticsNodeInteraction.rect(): Rect {
        val node = fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    private fun rect(tag: String): Rect = compose.onNodeWithTag(tag).rect()

    private fun repoRoot(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        var dir: File? = File(cwd).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir ?: File(".")
    }

    private companion object {
        const val PAGE = "page_root"
        const val ANCHOR = "thread_actions_anchor"
        const val HOST = "thread_actions_popup_host"
    }
}

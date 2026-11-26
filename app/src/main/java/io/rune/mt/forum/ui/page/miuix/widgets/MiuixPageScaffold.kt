// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.ui.theme.AppTheme
import io.rune.mt.forum.ui.theme.rememberMiuixBlurBackdrop
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/*
 * Shared page scaffold for the Miuix UI family.
 *
 * Every settings screen repeated the same five steps: remember a scroll behavior, remember a
 * backdrop, build a Scaffold, render the shared top bar, then decorate a LazyColumn with the
 * backdrop layer and scroll modifiers. Keeping that inline per page is what let screens drift
 * apart — the theme page once drove its backdrop from the blur toggle alone, so its glass back
 * button silently degraded while the About page looked correct.
 *
 * Screens now describe their content and nothing else; the style-correct chrome comes from
 * here. A page with an unusual layout (the About page's large scrolling logo) still builds its
 * own Scaffold.
 */

/** Chrome owned by [MiuixPageScaffold]: the backdrop, the scroll behavior, and the layer modifier. */
class MiuixPageChrome internal constructor(
    val backdrop: LayerBackdrop?,
    val scrollBehavior: ScrollBehavior,
    val contentBackdropModifier: Modifier,
)

/**
 * Remember the chrome for a page.
 *
 * Both the backdrop and the scroll behavior are derived from the ambient style, so no caller
 * has to decide when glass applies.
 */
@Composable
fun rememberMiuixPageChrome(enableBlur: Boolean = true): MiuixPageChrome {
    val scope = LocalMiuixComponentScope.current
    val useOs4 = scope.useOs4 || AppTheme.useMiuixOs4
    // The style decides whether the bar can sample the scene; the blur preference only adds the
    // OS3 blur path on top of it.
    val backdrop = rememberMiuixBlurBackdrop(useOs4 || enableBlur)
    val scrollBehavior = MiuixScrollBehavior()
    val layerModifier = backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier
    return remember(backdrop, scrollBehavior, layerModifier) {
        MiuixPageChrome(backdrop, scrollBehavior, layerModifier)
    }
}

/**
 * Standard Miuix page: the shared top bar plus a scroll container already wired to the backdrop.
 *
 * @param title Bar title, already resolved from resources.
 * @param navigationIcon Leading control; typically [MiuixBackButton] on secondary screens.
 * @param actions Trailing controls, laid out by the bar.
 * @param horizontalPadding Extra inset applied to both sides of the content.
 * @param verticalPadding Extra inset above and below the content. Pages differ here: the home
 *   screen sits 12dp below the bar while the theme page relies on its own leading spacer.
 * @param outerPadding Bottom inset from the enclosing layout, so a floating navigation bar does
 *   not cover the last row.
 */
@Composable
fun MiuixPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    chrome: MiuixPageChrome = rememberMiuixPageChrome(),
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    horizontalPadding: Dp = 0.dp,
    verticalPadding: Dp = 0.dp,
    outerPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    // Landscape display cutouts must not clip the content, so reserve the horizontal safe area
    // in one place instead of each page re-deriving it.
    val horizontalSafeInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            MiuixTopAppBar(
                title = title,
                backdrop = chrome.backdrop,
                scrollBehavior = chrome.scrollBehavior,
                navigationIcon = navigationIcon,
                actions = { actions() },
            )
        },
    ) { innerPadding ->
        val contentPadding = insetsOf(
            innerPadding,
            horizontalSafeInsets,
            outerPadding,
            horizontalPadding,
            verticalPadding,
            layoutDirection,
        )
        // The scroll bar is a sibling overlay of the list, matching the Miuix example pages
        // (`VerticalScrollBar(rememberScrollBarAdapter(listState))` inside the content `Box`).
        // It reads the same content padding the list uses, so the thumb never covers the first
        // or last row and the track stops at the scaffold's own bar inset.
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(chrome.contentBackdropModifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(chrome.scrollBehavior.nestedScrollConnection),
                state = listState,
                contentPadding = contentPadding,
                overscrollEffect = null,
                content = content,
            )
            MiuixPageScrollBar(
                listState = listState,
                contentPadding = contentPadding,
                scrollBehavior = chrome.scrollBehavior,
            )
        }
    }
}

/** Combine scaffold insets with the enclosing layout's, keeping the horizontal edge consistent. */
private fun insetsOf(
    inner: PaddingValues,
    safeInsets: PaddingValues,
    outer: PaddingValues,
    horizontal: Dp,
    vertical: Dp,
    direction: LayoutDirection,
): PaddingValues = PaddingValues(
    // Sides and top come from the scaffold's own insets only. outerPadding already carries the
    // enclosing layout's insets, so adding it here counted the top inset twice and pushed every
    // page's content down by one bar height.
    start = inner.calculateStartPadding(direction) + safeInsets.calculateStartPadding(direction) + horizontal,
    top = inner.calculateTopPadding() + vertical,
    end = inner.calculateEndPadding(direction) + safeInsets.calculateEndPadding(direction) + horizontal,
    // The bottom is the one place outerPadding is wanted: it clears the floating navigation bar.
    bottom = outer.calculateBottomPadding() + vertical,
)

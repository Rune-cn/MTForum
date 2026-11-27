// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(ExperimentalScrollBarApi::class)

package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

/*
 * The Miuix example pages overlay `VerticalScrollBar(rememberScrollBarAdapter(listState))` on
 * every long list (`MainPage.kt`, `SettingsPage.kt`, `ComponentPage.kt`, and `PullToRefreshPage`
 * proves it coexists with pull-to-refresh because it is a sibling of the list, not a wrapper).
 *
 * The app had no scroll bar at all, which made a thread page (one post can be several screens
 * tall) impossible to position by feel. The overlay lives here rather than in each page so the
 * thumb keeps one set of insets — the same `contentPadding` the list scrolls under — everywhere.
 */

/**
 * Page scroll bar overlay: place it as the last child of the `Box` that holds the list.
 *
 * @param listState The list whose progress the thumb shows.
 * @param contentPadding The list's own content padding, so the track starts and stops with the
 *   visible rows instead of running under the top bar and the floating navigation bar.
 * @param scrollBehavior The page's bar behavior: the thumb settles below the expanded top bar.
 */
@Composable
internal fun BoxScope.MiuixPageScrollBar(
    listState: LazyListState,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
) {
    VerticalScrollBar(
        adapter = rememberScrollBarAdapter(listState),
        modifier = modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        trackPadding = contentPadding,
        topAppBarScrollBehavior = scrollBehavior,
    )
}
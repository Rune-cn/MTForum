// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * Requests the next page once the list's last item is on screen.
 *
 * Every paged list in this app renders [ForumPagingFooter] as its last row, so "the last item"
 * means the footer is visible: the user has reached the tail of the loaded rows. The trigger is
 * deliberately **not** the only way forward — the footer carries an explicit "load page N" row,
 * and auto-append never fires while an error is displayed, so a failed page can be retried by hand
 * instead of being retried in a loop.
 *
 * Why this is a helper instead of the old per-page `derivedStateOf` + `LaunchedEffect(shouldAppend,
 * canLoadMore)` pair: that pair only re-ran when the layout heuristic or `canLoadMore` *changed*.
 * After an append completed, the list grew but the viewport could still sit at the tail (short
 * pages, a page that exactly fills the screen, or a fast double append). The effect then stayed
 * armed yet never fired again, so page 3 was unreachable until the user scrolled away and back.
 * Keying the effect on [isAppending] as well re-arms it on every completed append, which chains
 * pages while the tail remains visible and stops as soon as the appended rows push it off-screen.
 *
 * @param enabled The list may fetch another page (logged in, page < totalPages, etc.).
 * @param isAppending A page request is in flight.
 * @param hasError The last request failed; auto-append stays off until the footer is tapped.
 * @param onLoadMore Appends the next page (the view model still guards duplicate calls).
 */
@Composable
internal fun ForumAppendEffect(
    listState: LazyListState,
    enabled: Boolean,
    isAppending: Boolean,
    hasError: Boolean,
    onLoadMore: () -> Unit,
) {
    val atEnd by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            total > 0 && layout.visibleItemsInfo.lastOrNull()?.index == total - 1
        }
    }
    LaunchedEffect(atEnd, enabled, isAppending, hasError) {
        if (atEnd && enabled && !isAppending && !hasError) onLoadMore()
    }
}
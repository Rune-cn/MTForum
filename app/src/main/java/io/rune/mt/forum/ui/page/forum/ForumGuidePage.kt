// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import org.koin.androidx.compose.koinViewModel

/**
 * 导读 tab: the site's "最新发表" guide (`forum.php?mod=guide&view=newthread`).
 *
 * Unlike the home tab — which shows a top-10 ranking — this page is the full list and pages
 * through the guide, matching what the site's own 导读 entry offers.
 */
@Composable
fun ForumGuidePage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    viewModel: ForumViewModel = koinViewModel(),
) {
    val state by viewModel.guideState.collectAsStateWithLifecycle()
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current

    LaunchedEffect(Unit) { viewModel.loadGuideIfNeeded() }

    MiuixPageScaffold(
        title = title,
        chrome = chrome,
        outerPadding = outerPadding,
        horizontalPadding = 12.dp,
        verticalPadding = 12.dp,
    ) {
        if (state.isLoading) {
            item { ForumLoadingCard() }
        } else if (state.error != null && state.threads.isEmpty()) {
            item {
                ForumErrorCard(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::refreshGuide,
                )
            }
        } else if (state.threads.isEmpty()) {
            item { ForumEmptyCard(text = stringResource(R.string.forum_empty_threads)) }
        } else {
            items(items = state.threads, key = { "guide_${it.tid}" }) { thread ->
                ForumThreadCard(
                    thread = thread,
                    onClick = { navigator.push(Route.ForumThread(thread.tid, thread.title)) },
                )
            }
            item {
                ForumPagingFooter(
                    page = state.page,
                    totalPages = state.totalPages,
                    isAppending = state.isAppending,
                    onLoadMore = viewModel::loadMoreGuide,
                )
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.TabRow

/**
 * 导读 tab: the site's guide, split into its five views (最新 / 热门 / 精华 / 回复 / 抢沙发).
 *
 * All five render the same thread-list DOM and differ only by the `view` query parameter, so the
 * screen is a Miuix [TabRow] over one independently paged list per view. Switching a tab shows
 * that view's own rows (never the previous tab's) and re-selecting a cached view is instant.
 */
@Composable
fun ForumGuidePage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    viewModel: ForumViewModel = koinViewModel(),
) {
    val state by viewModel.guideState.collectAsStateWithLifecycle()
    val current = state.current
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()
    val views = ForumGuideView.entries

    LaunchedEffect(Unit) { viewModel.loadGuideIfNeeded() }
    // A tab switch starts that view at its own first row instead of inheriting the scroll offset.
    LaunchedEffect(state.selected) { listState.scrollToItem(0) }
    // This page previously had no scroll trigger at all: page 2 was reachable only by finding and
    // tapping the footer row. It now appends like the board listing while keeping that row.
    ForumAppendEffect(
        listState = listState,
        enabled = current.canLoadMore,
        isAppending = current.isAppending,
        hasError = current.error != null,
        onLoadMore = viewModel::loadMoreGuide,
    )

    MiuixPageScaffold(
        title = title,
        chrome = chrome,
        outerPadding = outerPadding,
        horizontalPadding = 12.dp,
        verticalPadding = 12.dp,
        listState = listState,
    ) {
        item(key = "guide_tabs") {
            // Miuix TabRow scrolls horizontally on its own and paints the selected tab with the
            // theme's selected container in both OS3 and OS4, so no style branch is needed.
            TabRow(
                tabs = views.map { stringResource(it.labelRes) },
                selectedTabIndex = state.selected.ordinal,
                onTabSelected = { index -> viewModel.selectGuideView(views[index]) },
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        when {
            current.isLoading -> item { ForumLoadingCard() }

            current.error != null && current.threads.isEmpty() -> item {
                ForumErrorCard(
                    message = current.error.orEmpty(),
                    onRetry = viewModel::refreshGuide,
                )
            }

            current.threads.isEmpty() -> item {
                ForumEmptyCard(text = stringResource(R.string.forum_empty_threads))
            }

            else -> {
                items(items = current.threads, key = { "guide_${state.selected.value}_${it.tid}" }) { thread ->
                    ForumThreadCard(
                        thread = thread,
                        onClick = { navigator.push(Route.ForumThread(thread.tid, thread.title)) },
                    )
                }
                item {
                    ForumPagingFooter(
                        page = current.page,
                        totalPages = current.totalPages,
                        isAppending = current.isAppending,
                        onLoadMore = viewModel::loadMoreGuide,
                    )
                }
            }
        }
    }
}
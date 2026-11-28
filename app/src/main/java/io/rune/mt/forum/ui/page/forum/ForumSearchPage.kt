// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 论坛内搜索 results: the site's own thread search (`search.php?mod=forum`).
 *
 * This page is reached only from the home top bar's magnifier (through [ForumSearchOverlay]) or a
 * deep link; the board listing has no search icon (rule 18). A route that carries a keyword is a
 * confirmed search, so [ForumSearchViewModel.prepare] runs it immediately; with a blank keyword
 * nothing is requested and the page offers the surface again instead of showing an empty list.
 *
 * **There is no search field on this page.** The keyword already came from [ForumSearchOverlay],
 * so a second, always-present input here would ask for the same thing twice: the route keyword is
 * shown **read-only** in the bar title (`forum_search_submit_format`, e.g. 搜索「xxx」) and the
 * results fill the page. Refining **in place** is still supported: the bar's magnifier re-opens the
 * very same surface (`ForumSearchOverlay` — animation, recent searches and its single submit path
 * all unchanged) and its submit is applied to this page's view model instead of pushing another
 * route, so a new keyword re-runs the search and returns the list to the top. That icon is not a
 * second search entry (rule 18): it opens the same overlay that produced these results, from the
 * page that owns them, and it exists because the field is gone.
 *
 * The site serves search only to signed-in accounts, so an anonymous visit shows the sign-in card
 * and never sends a request. Results reuse [ForumThreadCard] and the paging footer of the board
 * listing; tapping a row opens the thread.
 *
 * The overlay is a sibling of the scaffold inside the page root `Box` (the same shape as
 * [ForumHomePage]): it is an inline surface that must be measured against the page, and it stays
 * outside the content subtree `chrome.backdrop` records, so no backdrop self-sampling (rules
 * 12 / 23).
 */
@Composable
fun ForumSearchPage(
    initialKeyword: String,
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumSearchViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()
    // The refine surface: the same component the home magnifier opens, hosted here instead of
    // navigating, so a new keyword re-searches this page in place.
    var showRefine by rememberSaveable { mutableStateOf(false) }
    // A refined keyword waiting for the current round trip to finish (see the effect below). Not
    // saveable on purpose: it only bridges one in-flight request, and after process death the route
    // keyword re-prepares the page.
    var pendingKeyword by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialKeyword) { viewModel.prepare(initialKeyword) }
    // Results append on scroll like every other paged list; the footer stays the manual path.
    ForumAppendEffect(
        listState = listState,
        enabled = state.canLoadMore && !state.loginRequired && state.searched,
        isAppending = state.isAppending,
        hasError = state.error != null,
        onLoadMore = viewModel::loadMore,
    )

    // Apply a refined keyword as soon as the page is idle. [ForumSearchViewModel.search] drops a
    // request that arrives while a round trip is still running, so parking the overlay's submit and
    // replaying it when `isSearching` flips back is what keeps a fast refine from being lost (the
    // title would otherwise show the new keyword next to the previous keyword's results).
    LaunchedEffect(pendingKeyword, state.isSearching) {
        val target = pendingKeyword ?: return@LaunchedEffect
        if (state.isSearching) return@LaunchedEffect
        pendingKeyword = null
        viewModel.updateKeyword(target)
        viewModel.search()
        listState.scrollToItem(0)
    }

    // Read-only keyword display in the bar: the query is a fact of the page, not something to edit
    // here. Refining goes back through the overlay, which owns the field, the recent list and the
    // "record the keyword" step.
    val keyword = state.keyword.trim()
    val title = if (keyword.isEmpty()) {
        stringResource(R.string.forum_search)
    } else {
        stringResource(R.string.forum_search_submit_format, keyword)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MiuixPageScaffold(
            title = title,
            chrome = chrome,
            outerPadding = outerPadding,
            navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
            actions = {
                // Re-opens the overlay (rule 7 icon; the overlay keeps its own animation).
                MiuixTopBarIconButton(onClick = { showRefine = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.forum_search_action),
                    )
                }
            },
            horizontalPadding = if (AppTheme.useMiuixOs4) 16.dp else 12.dp,
            verticalPadding = 12.dp,
            listState = listState,
        ) {
            if (!session.isLoggedIn) {
                item { ForumSearchLoginCard(onLogin = { navigator.push(Route.Login) }) }
                return@MiuixPageScaffold
            }

            when {
                state.isSearching -> item { ForumLoadingCard() }

                state.loginRequired -> item {
                    ForumSearchLoginCard(onLogin = { navigator.push(Route.Login) })
                }

                state.error != null && state.threads.isEmpty() -> item {
                    ForumErrorCard(
                        message = state.error.orEmpty(),
                        onRetry = viewModel::search,
                    )
                }

                state.threads.isEmpty() && state.searched -> item {
                    ForumEmptyCard(text = stringResource(R.string.forum_search_empty))
                }

                // A route without a keyword (a hand-made push or a deep link with no query) has no
                // round trip to show, so it invites one instead of rendering a blank page. The
                // overlay itself never submits a blank keyword, so this branch is a fallback only.
                keyword.isEmpty() -> item {
                    ForumSearchStartCard(onStartSearch = { showRefine = true })
                }

                state.threads.isNotEmpty() -> {
                    items(items = state.threads, key = { "search_${it.tid}" }) { thread ->
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
                            onLoadMore = viewModel::loadMore,
                        )
                    }
                }
            }
        }

        // Same surface as the home magnifier, applied in place: no second route, no flash of the
        // old results. The overlay records the keyword in the recent list before calling back, so
        // this handler only parks the keyword; the effect above applies it once the page is idle.
        ForumSearchOverlay(
            expanded = showRefine,
            enableBlur = enableBlur,
            onDismissRequest = { showRefine = false },
            onSubmit = { newKeyword ->
                if (newKeyword.isNotBlank()) {
                    showRefine = false
                    pendingKeyword = newKeyword
                }
            },
        )
    }
}

/** Sign-in card shown while anonymous (or after the session expired mid-search). */
@Composable
private fun ForumSearchLoginCard(onLogin: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.forum_search_login_required),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(R.string.login_required_action),
                onClick = onLogin,
            )
        }
    }
}

/**
 * Fallback for a keyword-less route: a single card that opens the search surface.
 *
 * It is deliberately a card with an explicit action, not a field — the field only ever exists in
 * the overlay, so there is exactly one place that can submit a keyword.
 */
@Composable
private fun ForumSearchStartCard(onStartSearch: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.forum_search_hint),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(R.string.forum_search_action),
                onClick = onStartSearch,
            )
        }
    }
}

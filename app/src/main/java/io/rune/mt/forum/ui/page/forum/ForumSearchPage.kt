// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 论坛内搜索: the site's own thread search (`search.php?mod=forum`).
 *
 * This page is reached only from the home top bar's magnifier (through
 * [`ForumSearchOverlay`]) or a deep link; the board listing has no search icon (rule 18). A route
 * that carries a keyword is a confirmed search, so [ForumSearchViewModel.prepare] runs it
 * immediately and the field keeps that keyword for refining. With a blank keyword the field
 * auto-focuses and nothing is requested until the user submits.
 *
 * The site serves search only to signed-in accounts, so an anonymous visit shows the sign-in card
 * and never sends a request. Results reuse [ForumThreadCard] and the paging footer of the board
 * listing; tapping a row opens the thread. A refined submit re-runs the search and returns the
 * list to the top, without popping back to type a new query elsewhere.
 */
@Composable
fun ForumSearchPage(
    initialKeyword: String,
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumSearchViewModel = koinViewModel(),
    historyViewModel: ForumSearchHistoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialKeyword) { viewModel.prepare(initialKeyword) }
    LaunchedEffect(Unit) {
        if (initialKeyword.isBlank()) focusRequester.requestFocus()
    }
    // Results append on scroll like every other paged list; the footer stays the manual path.
    ForumAppendEffect(
        listState = listState,
        enabled = state.canLoadMore && !state.loginRequired && state.searched,
        isAppending = state.isAppending,
        hasError = state.error != null,
        onLoadMore = viewModel::loadMore,
    )

    // Refine in place: the field keeps the keyword (including the one the route carried), and a
    // second submit re-runs against the edited text without leaving the page. The keyword is also
    // recorded here so the overlay's recent list reflects the query that actually produced results.
    val submit: () -> Unit = {
        keyboard?.hide()
        historyViewModel.record(state.keyword)
        viewModel.search()
        scope.launch { listState.scrollToItem(0) }
    }

    MiuixPageScaffold(
        title = stringResource(R.string.forum_search),
        chrome = chrome,
        outerPadding = outerPadding,
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        horizontalPadding = if (AppTheme.useMiuixOs4) 16.dp else 12.dp,
        verticalPadding = 12.dp,
        listState = listState,
    ) {
        item(key = "search_input") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = state.keyword,
                    onValueChange = viewModel::updateKeyword,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    label = stringResource(R.string.forum_search_hint),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    leadingIcon = {
                        Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                )
                TextButton(
                    text = stringResource(R.string.forum_search_action),
                    onClick = submit,
                )
            }
        }

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
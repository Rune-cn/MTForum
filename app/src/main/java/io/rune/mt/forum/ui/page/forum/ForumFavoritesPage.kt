// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixConfirmDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScrollBar
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopAppBar
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 我的收藏: the signed-in user's bookmarked threads, with paging, pull-to-refresh and removal.
 *
 * Chrome is built here rather than through `MiuixPageScaffold` for the same reason as the board
 * listing: the rows have to live inside `PullToRefresh`, which owns the scroll gesture.
 *
 * Deleting is deliberately two taps (row trash icon → confirmation dialog); the removal is
 * optimistic and rolls back with a toast when the site rejects it.
 */
@Composable
fun ForumFavoritesPage(
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumFavoritesViewModel = koinViewModel(),
) {
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val snackbarHostState = remember { SnackbarHostState() }
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val gutter = if (AppTheme.useMiuixOs4) 16.dp else 12.dp
    // The dialog holds the row being deleted so the confirmation text can name it.
    var pendingDelete by remember { mutableStateOf<ForumFavorite?>(null) }
    val deleteFailedMessage = stringResource(R.string.favorites_delete_failed)

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    LaunchedEffect(state.deleteFailed) {
        if (!state.deleteFailed) return@LaunchedEffect
        snackbarHostState.showSnackbar(deleteFailedMessage)
        viewModel.clearDeleteFailed()
    }

    // Append the next page once the tail of the list becomes visible (same heuristic as boards).
    val shouldAppend by remember(state.favorites.size, state.page, state.totalPages) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 2
        }
    }
    LaunchedEffect(shouldAppend, state.canLoadMore, state.loginRequired) {
        if (shouldAppend && state.canLoadMore && !state.isAppending && !state.loginRequired) {
            viewModel.loadMore()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MiuixTopAppBar(
                title = stringResource(R.string.favorites_title),
                backdrop = chrome.backdrop,
                scrollBehavior = chrome.scrollBehavior,
                navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
            )
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
    ) { innerPadding ->
        val contentPadding = forumInsets(
            inner = innerPadding,
            horizontal = horizontalInsets,
            outer = outerPadding,
            direction = layoutDirection,
            gutter = gutter,
        )
        Box(modifier = chrome.contentBackdropModifier.fillMaxSize()) {
            PullToRefresh(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                pullToRefreshState = pullToRefreshState,
                contentPadding = contentPadding,
            ) {
                // Scroll bar as a sibling of the list (the Miuix example's PullToRefreshPage
                // shape), so the pull gesture keeps a single owner.
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(chrome.scrollBehavior.nestedScrollConnection),
                        contentPadding = contentPadding,
                    ) {
                        when {
                            !session.isLoggedIn || state.loginRequired -> item {
                                FavoritesLoginCard(onLogin = { navigator.push(Route.Login) })
                            }

                            state.isLoading -> item { ForumLoadingCard() }

                            state.error != null && state.favorites.isEmpty() -> item {
                                ForumErrorCard(
                                    message = state.error.orEmpty(),
                                    onRetry = viewModel::refresh,
                                )
                            }

                            state.favorites.isEmpty() -> item {
                                ForumEmptyCard(text = stringResource(R.string.favorites_empty))
                            }

                            else -> {
                                items(items = state.favorites, key = { "favorite_${it.favid}" }) { favorite ->
                                    FavoriteRow(
                                        favorite = favorite,
                                        onOpen = { tid ->
                                            favorite.title.let { title ->
                                                navigator.push(Route.ForumThread(tid, title))
                                            }
                                        },
                                        onDelete = { pendingDelete = favorite },
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
                    MiuixPageScrollBar(
                        listState = listState,
                        contentPadding = contentPadding,
                        scrollBehavior = chrome.scrollBehavior,
                    )
                }
            }
        }
    }

    pendingDelete?.let { favorite ->
        MiuixConfirmDialog(
            visible = true,
            title = stringResource(R.string.favorites_delete_title),
            message = stringResource(R.string.favorites_delete_message_format, favorite.title),
            confirmText = stringResource(R.string.favorites_delete_action),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                val target = pendingDelete
                pendingDelete = null
                if (target != null) viewModel.removeFavorite(target)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** One favourite row: the thread title plus a trailing trash button that asks for confirmation. */
@Composable
private fun FavoriteRow(
    favorite: ForumFavorite,
    onOpen: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val tid = favorite.tid
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        onClick = { if (tid != null) onOpen(tid) },
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = favorite.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    favorite.forumName?.takeIf { it.isNotBlank() },
                    favorite.timeText?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = meta,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.favorites_delete_action),
                    tint = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Login gate shown instead of the list when the session is absent or has expired. */
@Composable
private fun FavoritesLoginCard(onLogin: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.favorites_login_required_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.favorites_login_required_desc),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onLogin) {
                Text(text = stringResource(R.string.profile_login_action))
            }
        }
    }
}
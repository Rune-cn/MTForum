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
import androidx.compose.foundation.layout.size
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
import io.rune.mt.forum.domain.forum.model.ForumDraft
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixConfirmDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopAppBar
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 草稿: the drafts the **site** stores for the signed-in author.
 *
 * The list is `forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2`, i.e. the same
 * draft store the composer's 保存草稿 writes to. Tapping a row opens it in the composer (which
 * fetches the draft's edit form); the trash icon asks for confirmation before the site's `delete=1`
 * is sent. Chrome is built here instead of through `MiuixPageScaffold` because the rows must live
 * inside `PullToRefresh`, which owns the scroll gesture.
 *
 * The body renders [ForumDraftsPhase], i.e. the **load result**, not a session flag: rows the site
 * returned are always shown, and loading / empty / failed / login-required stay four distinct cards.
 */
@Composable
fun ForumDraftsPage(
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumDraftsViewModel = koinViewModel(),
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
    var pendingDelete by remember { mutableStateOf<ForumDraft?>(null) }

    // Re-runs when the session settles: a cookie restore that finishes after the first composition
    // (or a sign-in from the login card) must re-ask the site instead of leaving the login card up.
    LaunchedEffect(session.isLoggedIn) { viewModel.loadIfNeeded() }

    LaunchedEffect(state.deleteError) {
        val message = state.deleteError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearDeleteError()
    }

    val shouldAppend by remember(state.drafts.size, state.page, state.totalPages) {
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
                title = stringResource(R.string.drafts_title),
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(chrome.scrollBehavior.nestedScrollConnection),
                    contentPadding = contentPadding,
                ) {
                    when (state.phase(signedIn = session.isLoggedIn)) {
                        // Rows the site returned are rendered first, whatever the session flag says:
                        // a restore that has not finished yet must never hide a fetched page.
                        ForumDraftsPhase.Rows -> {
                            items(items = state.drafts, key = { "draft_${it.tid}" }) { draft ->
                                DraftRow(
                                    draft = draft,
                                    isDeleting = state.deletingTid == draft.tid,
                                    onOpen = {
                                        navigator.push(
                                            Route.NewThread(
                                                fid = draft.fid,
                                                name = draft.forumName.orEmpty(),
                                                draftTid = draft.tid,
                                                draftPid = draft.pid,
                                            ),
                                        )
                                    },
                                    onDelete = { pendingDelete = draft },
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

                        ForumDraftsPhase.Loading -> item { ForumLoadingCard() }

                        // The site itself answered with the login form: that is the only case where
                        // the login card may stand in for the list.
                        ForumDraftsPhase.LoginRequired -> item {
                            DraftsLoginCard(onLogin = { navigator.push(Route.Login) })
                        }

                        // A failure keeps the site's wording and a retry, so it can never be
                        // mistaken for an empty drafts box.
                        ForumDraftsPhase.Error -> item {
                            ForumErrorCard(
                                message = state.error.orEmpty(),
                                onRetry = viewModel::refresh,
                            )
                        }

                        ForumDraftsPhase.Empty -> item {
                            ForumEmptyCard(text = stringResource(R.string.drafts_empty))
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { draft ->
        MiuixConfirmDialog(
            visible = true,
            title = stringResource(R.string.drafts_delete_title),
            message = stringResource(R.string.drafts_delete_message_format, draft.title),
            confirmText = stringResource(R.string.drafts_delete_action),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                val target = pendingDelete
                pendingDelete = null
                if (target != null) viewModel.deleteDraft(target)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * One draft row: the title, the body excerpt the site prints, board + relative time, and a trailing
 * trash button that asks for confirmation before anything is deleted.
 */
@Composable
private fun DraftRow(
    draft: ForumDraft,
    isDeleting: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        onClick = onOpen,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val excerpt = draft.excerpt.orEmpty()
                if (excerpt.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = excerpt,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = listOfNotNull(
                    draft.forumName?.takeIf { it.isNotBlank() },
                    draft.timeText?.takeIf { it.isNotBlank() },
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
            if (isDeleting) {
                InfiniteProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.drafts_delete_action),
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Login gate shown instead of the list when the **site** answered with its login form.
 *
 * This is the only honest reason to replace the list: the pages are member-only, so a guest (or a
 * cookie that expired server-side) gets the form instead of rows. A signed-out session flag alone
 * is not enough — the cookie restore is asynchronous, so it may still be settling while the list
 * has already been fetched.
 */
@Composable
private fun DraftsLoginCard(onLogin: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.drafts_login_required_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.drafts_login_required_desc),
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
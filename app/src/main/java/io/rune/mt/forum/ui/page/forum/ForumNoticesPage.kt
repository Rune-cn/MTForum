// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Comment
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumNotice
import io.rune.mt.forum.domain.forum.model.ForumNoticeKind
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScrollBar
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopAppBar
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * The site's 提醒 (notice) inbox: 系统提醒 / 回复我的 / 我的帖子.
 *
 * The site splits the inbox into one page per tab (`view=system|interactive|mypost`), so this
 * screen keeps an independently paged list per tab — see [ForumNoticesState]. Chrome is built
 * here instead of through the shared page scaffold because the rows must sit inside
 * [PullToRefresh], which owns its own LazyColumn.
 *
 * Tapping a row that points at a post opens [Route.ForumThread]; rows that only announce
 * something (no `ptid=` target) are not clickable.
 */
@Composable
fun ForumNoticesPage(
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumNoticesViewModel = koinViewModel(),
) {
    val chrome = rememberMiuixPageChrome(enableBlur)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val current = state.current
    val tabs = ForumNoticeTab.entries
    // OS4 frames content with a wider gutter; the shared chrome resolves the rest of the style.
    val contentGutter = if (AppTheme.useMiuixOs4) 16.dp else 12.dp
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()

    // Loads as soon as the session is known to be signed in; a user who signs in from the card
    // and comes back triggers the same effect again.
    LaunchedEffect(session.isLoggedIn) { viewModel.loadIfNeeded() }
    // A tab switch starts that tab at its own first row instead of inheriting the scroll offset.
    LaunchedEffect(state.selected) { listState.scrollToItem(0) }
    // Notice pages append on scroll like the board listing; the footer stays the manual path.
    ForumAppendEffect(
        listState = listState,
        enabled = session.isLoggedIn && current.canLoadMore,
        isAppending = current.isAppending,
        hasError = current.error != null,
        onLoadMore = viewModel::loadMore,
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MiuixTopAppBar(
                title = stringResource(R.string.forum_notices_title),
                backdrop = chrome.backdrop,
                scrollBehavior = chrome.scrollBehavior,
                navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
            )
        },
    ) { innerPadding ->
        val contentPadding = forumInsets(
            inner = innerPadding,
            horizontal = horizontalInsets,
            outer = outerPadding,
            direction = layoutDirection,
            gutter = contentGutter,
        )
        Box(modifier = Modifier.fillMaxSize().then(chrome.contentBackdropModifier)) {
            PullToRefresh(
                isRefreshing = current.isRefreshing,
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
                        item(key = "notice_tabs") {
                            // Miuix TabRow scrolls horizontally and paints the selected tab with the
                            // theme's container in both OS3 and OS4, so no style branch is needed.
                            TabRow(
                                tabs = tabs.map { stringResource(it.labelRes) },
                                selectedTabIndex = state.selected.ordinal,
                                onTabSelected = { index -> viewModel.selectTab(tabs[index]) },
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }

                        when {
                            !session.isLoggedIn -> item {
                                MessagesInfoCard(
                                    title = stringResource(R.string.forum_notices_login_required),
                                    subtitle = stringResource(R.string.forum_notices_login_hint),
                                )
                            }

                            current.isLoading -> item { ForumLoadingCard() }

                            current.error != null && current.notices.isEmpty() -> item {
                                ForumErrorCard(
                                    message = current.error.orEmpty(),
                                    onRetry = viewModel::refresh,
                                )
                            }

                            current.notices.isEmpty() -> item {
                                ForumEmptyCard(text = stringResource(R.string.forum_notices_empty))
                            }

                            else -> {
                                items(
                                    items = current.notices,
                                    key = { notice -> "notice_${state.selected.value}_${notice.id ?: notice.hashCode()}" },
                                ) { notice ->
                                    NoticeRow(
                                        notice = notice,
                                        onOpenThread = { tid, title ->
                                            navigator.push(Route.ForumThread(tid, title))
                                        },
                                    )
                                }
                                item {
                                    ForumPagingFooter(
                                        page = current.page,
                                        totalPages = current.totalPages,
                                        isAppending = current.isAppending,
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
}

/** One inbox row: avatar (or a kind icon for authorless site messages), author, preview, time. */
@Composable
internal fun NoticeRow(
    notice: ForumNotice,
    onOpenThread: (tid: Int, title: String) -> Unit,
) {
    val tid = notice.tid
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        onClick = tid?.let {
            {
                onOpenThread(it, notice.threadTitle.ifBlank { notice.authorName })
            }
        },
        insideMargin = PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (notice.authorAvatarUrl.isNullOrBlank() && notice.authorName.isBlank()) {
                // 系统提醒 rows carry no author; a tinted kind icon keeps the row aligned with
                // the avatar rows instead of collapsing the leading column.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = notice.kind.icon(),
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                ForumAvatar(url = notice.authorAvatarUrl, name = notice.authorName, size = 44.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (notice.unread) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.error),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    if (notice.authorName.isNotBlank()) {
                        Text(
                            text = notice.authorName,
                            fontSize = 15.sp,
                            fontWeight = if (notice.unread) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    notice.timeText?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (notice.contentHtml.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    ForumHtmlPreview(html = notice.contentHtml, maxLines = 3)
                }
            }
        }
    }
}

/** Icon used for the authorless (system) rows, mirroring the action the notice describes. */
private fun ForumNoticeKind.icon(): ImageVector = when (this) {
    ForumNoticeKind.Reply -> Icons.Rounded.Reply
    ForumNoticeKind.Comment -> Icons.Rounded.Comment
    ForumNoticeKind.Mention -> Icons.Rounded.AlternateEmail
    ForumNoticeKind.Like -> Icons.Rounded.ThumbUp
    ForumNoticeKind.System -> Icons.Rounded.Notifications
    ForumNoticeKind.Other -> Icons.Rounded.Notifications
}
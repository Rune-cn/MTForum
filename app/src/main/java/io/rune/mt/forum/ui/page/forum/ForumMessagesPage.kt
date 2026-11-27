// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScrollBar
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopAppBar
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.page.miuix.widgets.rememberTabRowLabelWidth
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The 消息 tab: 私信 **and** the site's notice inbox, split by taps.
 *
 * The home top bar used to carry the 消息提醒 bell; on the owner's request that entry is gone and the
 * three notice categories live here instead, as tabs next to the private-message list
 * ([ForumNoticeTab.entries] → 系统提醒 / 坛友互动 / 我的帖子). Both halves keep their own view model
 * and paging state, so switching tabs neither refetches the other side nor loses its scroll.
 *
 * Tapping a conversation pushes [Route.PrivateConversation]; tapping a notice with a thread opens
 * [Route.ForumThread]. `Route.Notices` still exists for deep links and loads the same content.
 */
@Composable
fun ForumMessagesPage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    viewModel: ForumMessagesViewModel = koinViewModel(),
    noticesViewModel: ForumNoticesViewModel = koinViewModel(),
) {
    val chrome = rememberMiuixPageChrome(enableBlur)
    val state by viewModel.listState.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val noticeState by noticesViewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val contentGutter = if (AppTheme.useMiuixOs4) 16.dp else 12.dp
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val tabs = ForumNoticeTab.entries
    // Four labels share this row; the width comes from the widest one (bold, as the selected tab is
    // drawn) so no label is ever ellipsized — the row scrolls instead (see
    // `rememberTabRowLabelWidth`).
    val tabLabels = listOf(stringResource(R.string.forum_messages_tab_private)) +
        tabs.map { stringResource(it.labelRes) }
    val tabWidth = rememberTabRowLabelWidth(tabLabels)
    // -1 = 私信, otherwise the ordinal of the selected notice category.
    var selected by rememberSaveable { mutableIntStateOf(PRIVATE_TAB) }
    val noticeTab = tabs.getOrNull(selected)

    LaunchedEffect(session.isLoggedIn) {
        viewModel.loadConversationsIfNeeded()
    }
    // Only the visible half loads; the other side keeps whatever it already has.
    LaunchedEffect(session.isLoggedIn, noticeTab) {
        if (noticeTab != null) noticesViewModel.loadIfNeeded()
    }
    LaunchedEffect(selected) { listState.scrollToItem(0) }
    ForumAppendEffect(
        listState = listState,
        enabled = session.isLoggedIn && noticeTab != null && noticeState.current.canLoadMore,
        isAppending = noticeState.current.isAppending,
        hasError = noticeState.current.error != null,
        onLoadMore = noticesViewModel::loadMore,
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MiuixTopAppBar(
                title = title,
                backdrop = chrome.backdrop,
                scrollBehavior = chrome.scrollBehavior,
            )
        },
    ) { innerPadding ->
        val contentPadding = forumInsets(
            inner = innerPadding,
            horizontal = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues(),
            outer = outerPadding,
            direction = LocalLayoutDirection.current,
            gutter = contentGutter,
        )
        Box(modifier = Modifier.fillMaxSize().then(chrome.contentBackdropModifier)) {
            PullToRefresh(
                isRefreshing = if (noticeTab == null) state.isRefreshing else noticeState.current.isRefreshing,
                onRefresh = {
                    if (noticeTab == null) viewModel.refreshConversations() else noticesViewModel.refresh()
                },
                pullToRefreshState = pullToRefreshState,
                contentPadding = contentPadding,
            ) {
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
                        item(key = "messages_tabs") {
                            // Labels are content-sized (see `rememberTabRowLabelWidth`): miuix's
                            // default equal-width split clipped 系统提醒 to 「系统···」 on a
                            // 360dp phone. With a content minimum the row scrolls instead.
                            TabRow(
                                tabs = tabLabels,
                                selectedTabIndex = selected + 1,
                                onTabSelected = { index ->
                                    val next = index - 1
                                    selected = next
                                    tabs.getOrNull(next)?.let(noticesViewModel::selectTab)
                                },
                                modifier = Modifier.padding(bottom = 10.dp),
                                minWidth = tabWidth,
                                maxWidth = tabWidth,
                                itemSpacing = MESSAGES_TAB_SPACING,
                            )
                        }

                        if (!session.isLoggedIn) {
                            item {
                                MessagesInfoCard(
                                    title = stringResource(R.string.forum_messages_login_required),
                                    subtitle = stringResource(R.string.forum_messages_login_hint),
                                )
                            }
                            return@LazyColumn
                        }

                        if (noticeTab == null) {
                            when {
                                state.isLoading -> item { ForumLoadingCard() }
                                state.error != null -> item {
                                    ForumErrorCard(
                                        message = state.error.orEmpty(),
                                        onRetry = { viewModel.refreshConversations() },
                                    )
                                }
                                state.conversations.isEmpty() -> item {
                                    MessagesInfoCard(
                                        title = stringResource(R.string.forum_messages_empty),
                                        subtitle = stringResource(R.string.forum_messages_empty_hint),
                                    )
                                }
                                else -> items(items = state.conversations, key = { "pm_${it.uid}" }) { conversation ->
                                    ConversationRow(
                                        conversation = conversation,
                                        onClick = {
                                            navigator.push(Route.PrivateConversation(conversation.uid, conversation.name))
                                        },
                                    )
                                }
                            }
                        } else {
                            val current = noticeState.current
                            when {
                                current.isLoading -> item { ForumLoadingCard() }
                                current.error != null && current.notices.isEmpty() -> item {
                                    ForumErrorCard(
                                        message = current.error.orEmpty(),
                                        onRetry = noticesViewModel::refresh,
                                    )
                                }
                                current.notices.isEmpty() -> item {
                                    ForumEmptyCard(text = stringResource(R.string.forum_notices_empty))
                                }
                                else -> {
                                    items(
                                        items = current.notices,
                                        key = { notice ->
                                            "notice_${noticeTab}_${notice.id ?: notice.hashCode()}"
                                        },
                                    ) { notice ->
                                        NoticeRow(
                                            notice = notice,
                                            onOpenThread = { tid, noticeTitle ->
                                                navigator.push(Route.ForumThread(tid, noticeTitle))
                                            },
                                        )
                                    }
                                    item {
                                        ForumPagingFooter(
                                            page = current.page,
                                            totalPages = current.totalPages,
                                            isAppending = current.isAppending,
                                            onLoadMore = noticesViewModel::loadMore,
                                        )
                                    }
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

/** Tab index of the private-message half; the notice categories follow at 0..n. */
private const val PRIVATE_TAB = -1

/**
 * Gap between the four tabs.
 *
 * Smaller than Miuix's 9dp default so the four content-sized tabs still fit on a 411dp phone in
 * both styles (OS3's 12dp page gutter and OS4's 16dp one); on a 360dp phone the row scrolls, which
 * is the intended fallback instead of an ellipsized label.
 */
private val MESSAGES_TAB_SPACING = 5.dp

@Composable
private fun ConversationRow(
    conversation: ForumPrivateConversation,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        onClick = onClick,
        insideMargin = PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForumAvatar(url = conversation.avatarUrl, name = conversation.name, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.name.ifBlank { "用户 ${conversation.uid}" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    conversation.lastTimeText?.let {
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                conversation.lastPreview?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Simple centred card used for the login gate / empty state. */
@Composable
internal fun MessagesInfoCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

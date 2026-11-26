// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(ExperimentalScrollBarApi::class)

package io.rune.mt.forum.ui.page.forum

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.content.Intent
import android.net.Uri

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopAppBar
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
/**
 * A thread: the original post followed by its replies (楼层).
 *
 * Floors are fetched one page at a time; reaching the tail appends the next page instead of
 * replacing the list, and a pull-down re-reads page 1 from the top. Chrome lives here rather
 * than in the page-scaffold helper because the list must sit inside [PullToRefresh].
 */
@Composable
fun ForumThreadPage(
    tid: Int,
    title: String,
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumViewModel = koinViewModel(),
) {
    val state by viewModel.threadState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val chrome = rememberMiuixPageChrome(enableBlur)
    // OS4 frames content with a wider gutter; the shared chrome resolves the rest of the style.
    val contentGutter = if (AppTheme.useMiuixOs4) 16.dp else 12.dp
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val layoutDirection = LocalLayoutDirection.current
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    val copiedText = stringResource(R.string.thread_action_copied)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeActionMessage()
        }
    }
    val favoriteLabel = stringResource(R.string.thread_action_favorite)
    val likeLabel = stringResource(R.string.thread_action_like)
    val followLabel = stringResource(R.string.thread_action_follow_author)
    val copyLabel = stringResource(R.string.thread_action_copy_link)
    val openBrowserLabel = stringResource(R.string.thread_action_open_browser)
    val threadUrl = "https://bbs.binmt.cc/thread-$tid-1-1.html"
    val shareLabel = stringResource(R.string.thread_action_share)
    val sharePrefix = stringResource(R.string.thread_share_prefix)
    val horizontalInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()

    // One list feeds both the top-bar button and the root-level OS4 menu. Each row carries the
    // icon its action shows under OS4; the OS3 window popup stays text-only by convention.
    val threadActions = listOf(
        ThreadActionItem(favoriteLabel, Icons.Rounded.FavoriteBorder) { viewModel.favoriteCurrentThread() },
        ThreadActionItem(likeLabel, Icons.Rounded.ThumbUp) { viewModel.recommendCurrentThread() },
        ThreadActionItem(followLabel, Icons.Rounded.PersonAdd) { viewModel.followCurrentThreadAuthor() },
        ThreadActionItem(copyLabel, Icons.Rounded.Link) {
            clipboard?.setText(threadUrl)
            scope.launch { snackbarHostState.showSnackbar(copiedText) }
        },
        // Escape hatch to the site itself: some flows (attachments, login quirks) only work in
        // the real browser. A device can have no activity for VIEW http(s) at all, so the launch
        // is guarded — a menu entry must never be able to kill the app.
        ThreadActionItem(openBrowserLabel, Icons.Rounded.OpenInBrowser) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(threadUrl)))
            }.onFailure { error ->
                Timber.tag("ForumThreadPage").w(error, "no activity to open %s", threadUrl)
            }
        },
        // A real system share sheet, not a clipboard copy disguised as sharing.
        ThreadActionItem(shareLabel, Icons.Rounded.Share) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, state.title.ifBlank { title })
                putExtra(Intent.EXTRA_TEXT, "$sharePrefix${state.title.ifBlank { title}}\n$threadUrl")
            }
            context.startActivity(Intent.createChooser(intent, shareLabel))
        },
    )
    val actionsState = rememberThreadActionsState()

    LaunchedEffect(tid) {
        Timber.tag("ForumThreadPage").i("thread shown tid=%d", tid)
        viewModel.openThread(tid, title)
    }

    val shouldAppend by remember(state.posts.size, state.page, state.totalPages) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 2
        }
    }
    LaunchedEffect(shouldAppend, state.canLoadMore) {
        if (shouldAppend && state.canLoadMore && !state.isAppending) {
            viewModel.loadMorePosts()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                MiuixTopAppBar(
                    title = state.title.ifBlank { title },
                    backdrop = chrome.backdrop,
                    scrollBehavior = chrome.scrollBehavior,
                    navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
                    actions = {
                        // Only the button lives in the bar. The OS4 menu is an inline overlay, so
                        // it is composed at the page root below instead of inside this cell.
                        ThreadActions(items = threadActions, state = actionsState)
                    },
                )
            },
        ) { innerPadding ->
            // Reply lives on a floating button at the bottom-right so it is reachable while
            // reading, instead of competing with the title for the top bar.
            Box(modifier = Modifier.fillMaxSize()) {
                val contentPadding = forumInsets(
                    inner = innerPadding,
                    horizontal = horizontalInsets,
                    outer = outerPadding,
                    direction = layoutDirection,
                    gutter = contentGutter,
                )
                Box(modifier = chrome.contentBackdropModifier.fillMaxSize()) {
                    PullToRefresh(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refreshThread,
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
                            if (state.isLoading) {
                                item { ForumLoadingCard() }
                            } else if (state.error != null && state.posts.isEmpty()) {
                                item {
                                    ForumErrorCard(
                                        message = state.error.orEmpty(),
                                        onRetry = viewModel::refreshThread,
                                    )
                                }
                            } else {
                                if (state.posts.isEmpty()) {
                                    item { ForumEmptyCard(stringResource(R.string.forum_empty_posts)) }
                                } else {
                                    items(items = state.posts, key = { "post_${it.pid}" }) { post ->
                                        ForumPostCard(
                                            post = post,
                                            forumName = state.forumName,
                                            onAuthorClick = { author ->
                                                // `authorUid == 0` means the row carried no profile
                                                // link (guest/deleted account); pushing a profile
                                                // for uid 0 would 404, so the card skips it.
                                                if (author.authorUid != 0) {
                                                    navigator.push(
                                                        Route.UserProfile(author.authorUid, author.authorName),
                                                    )
                                                }
                                            },
                                            onImageClick = { viewerUrl = it },
                                        )
                                    }
                                }

                                item {
                                    ForumPagingFooter(
                                        page = state.page,
                                        totalPages = state.totalPages,
                                        isAppending = state.isAppending,
                                        onLoadMore = viewModel::loadMorePosts,
                                    )
                                }
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick = { viewModel.requestReply() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = contentGutter, bottom = contentGutter),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Reply,
                        contentDescription = stringResource(R.string.thread_action_reply),
                    )
                }
            }
        }

        // Root-level overlay: this fillMaxSize page box is the popup host, which is what the
        // glass panel is measured and clamped against.
        ThreadActionsGlassMenu(items = threadActions, state = actionsState)

        // Registered *after* the menu: the navigation-event dispatcher prioritises the most
        // recently composed enabled handler, so while the menu is open this predictive handler
        // previews the dismissal instead of letting the popup swallow the gesture. While the
        // menu is shut it is disabled and NavDisplay's gesture driver keeps the preview.
        ThreadActionsBackHandler(state = actionsState)
    }

    // The composer lives outside the Scaffold so it overlays the thread instead of scrolling
    // with the list.
    val replyState by viewModel.replyState.collectAsStateWithLifecycle()
    ReplyComposer(
        state = replyState,
        onTextChange = viewModel::updateReplyText,
        onSubmit = viewModel::submitReply,
        onDismiss = viewModel::dismissReply,
    )

    ForumImageViewer(url = viewerUrl, onDismiss = { viewerUrl = null })
}
/**
 * One floor of a thread.
 *
 * The site itself does not print floor numbers, so the client derives them: the OP is 「楼主」
 * and each subsequent post is 沙发/板凳/…-style ordinal. The OP keeps the tinted surface and the
 * OP badge so a long thread stays scannable, and the body is wrapped in a [SelectionContainer]
 * (text selection only; image taps still reach [ForumHtmlText]) so a reader can copy a floor.
 * The duplicate per-floor 回复 button was removed: the page's floating reply FAB is the single
 * reply entry point.
 */
@Composable
internal fun ForumPostCard(
    post: ForumPost,
    modifier: Modifier = Modifier,
    forumName: String? = null,
    onImageClick: (String) -> Unit = {},
    onAuthorClick: (ForumPost) -> Unit = {},
) {
    val op = post.isOp
    val level = post.authorLevel?.takeIf { it.isNotBlank() }
    val meta = buildList {
        level?.let(::add)
        post.timeText?.takeIf { it.isNotBlank() }?.let(::add)
        post.location?.takeIf { it.isNotBlank() }?.let(::add)
    }
    val authorClickable = Modifier.clickable(enabled = post.authorUid != 0) { onAuthorClick(post) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (op) MiuixTheme.colorScheme.surfaceContainerHigh
                else MiuixTheme.colorScheme.surfaceContainer,
            ),
    ) {
        // Board label on the OP card only: it answers "where am I?" without repeating the app bar
        // title, and a long thread's later floors do not need it.
        if (op && !forumName.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.forum_thread_board_format, forumName),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = if (op && !forumName.isNullOrBlank()) 8.dp else 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ForumAvatar(
                url = post.authorAvatarUrl,
                name = post.authorName,
                size = 36.dp,
                modifier = authorClickable,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = post.authorName,
                        modifier = authorClickable,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    ForumBadge(
                        text = if (op) stringResource(R.string.forum_op_badge)
                        else stringResource(R.string.forum_reply_badge),
                    )
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = meta.joinToString(" · "),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (post.floor != null && post.floor > 0) {
                Text(
                    text = stringResource(R.string.forum_floor_format, post.floor),
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // A hairline separates the byline from the body; without it a multi-paragraph post ran
        // straight into the header and the two read as one block.
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(1.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.2f)),
        )
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            ForumHtmlText(
                html = post.contentHtml,
                modifier = Modifier.padding(horizontal = 16.dp),
                onImageClick = onImageClick,
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}


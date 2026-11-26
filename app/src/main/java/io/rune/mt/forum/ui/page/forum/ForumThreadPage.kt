// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(ExperimentalScrollBarApi::class)

package io.rune.mt.forum.ui.page.forum

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import android.content.Intent

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.ui.navigation.LocalNavigator
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
import top.yukonga.miuix.kmp.basic.TextButton
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
    val threadUrl = "https://bbs.binmt.cc/thread-$tid-1-1.html"
    val shareLabel = stringResource(R.string.thread_action_share)
    val sharePrefix = stringResource(R.string.thread_share_prefix)
    val horizontalInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()

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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MiuixTopAppBar(
                title = state.title.ifBlank { title },
                backdrop = chrome.backdrop,
                scrollBehavior = chrome.scrollBehavior,
                navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
                actions = {
                    ThreadActions(
                        items = listOf(
                            ThreadActionItem(favoriteLabel) { viewModel.favoriteCurrentThread() },
                            ThreadActionItem(likeLabel) { viewModel.recommendCurrentThread() },
                            ThreadActionItem(followLabel) { viewModel.followCurrentThreadAuthor() },
                            ThreadActionItem(copyLabel) {
                                clipboard?.setText(threadUrl)
                                scope.launch { snackbarHostState.showSnackbar(copiedText) }
                            },
                            // A real system share sheet, not a clipboard copy disguised as sharing.
                            ThreadActionItem(shareLabel) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, state.title.ifBlank { title })
                                    putExtra(Intent.EXTRA_TEXT, "$sharePrefix${state.title.ifBlank { title}}\n$threadUrl")
                                }
                                context.startActivity(Intent.createChooser(intent, shareLabel))
                            },
                        ),
                    )
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
                                    onReply = { viewModel.requestReply(it) },
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
 * and each subsequent post is 沙发/板凳/…-style ordinal. The OP card is visually anchored with a
 * primary rule and a tinted header so a long thread stays readable.
 */
@Composable
internal fun ForumPostCard(
    post: ForumPost,
    onReply: (ForumPost) -> Unit,
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit = {},
) {
    val op = post.isOp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (op) MiuixTheme.colorScheme.surfaceContainerHigh
                else MiuixTheme.colorScheme.surfaceContainer,
            )
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForumAvatar(url = post.authorAvatarUrl, name = post.authorName, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = post.authorName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    ForumBadge(
                        text = if (op) stringResource(R.string.forum_op_badge)
                        else stringResource(R.string.forum_reply_badge),
                    )
                }
                val meta = buildList {
                    post.authorLevel?.takeIf { it.isNotBlank() }?.let(::add)
                    post.timeText?.takeIf { it.isNotBlank() }?.let(::add)
                    post.location?.takeIf { it.isNotBlank() }?.let(::add)
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
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
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // A hairline separates the byline from the body; without it a multi-paragraph post ran
        // straight into the header and the two read as one block.
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.12f)),
        )
        Spacer(Modifier.height(10.dp))
        ForumHtmlText(html = post.contentHtml, onImageClick = onImageClick)

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                text = stringResource(R.string.thread_action_reply),
                onClick = { onReply(post) },
            )
        }
    }
}


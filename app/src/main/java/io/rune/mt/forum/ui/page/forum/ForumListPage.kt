// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(ExperimentalScrollBarApi::class)

package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumCategory
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopAppBar
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
/**
 * One board's thread listing.
 *
 * Chrome is built here instead of through [io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold]
 * because the rows must sit inside [PullToRefresh]: that helper owns its own LazyColumn, and
 * nesting a second scroll container would hand the pull gesture two owners.
 */
@Composable
fun ForumListPage(
    fid: Int,
    forumName: String,
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumViewModel = koinViewModel(),
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val chrome = rememberMiuixPageChrome(enableBlur)
    // OS4 frames content with a wider gutter; the shared chrome resolves the rest of the style.
    val contentGutter = if (AppTheme.useMiuixOs4) 16.dp else 12.dp
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()

    LaunchedEffect(fid) {
        Timber.tag("ForumListPage").i("board shown fid=%d", fid)
        viewModel.openForum(fid, forumName)
    }

    // Switching 主题分类 reloads a shorter list: start it at the tabs again instead of keeping the
    // old offset (which could leave the user in the middle of a page that just shrank).
    LaunchedEffect(state.typeId) { listState.scrollToItem(0) }

    // Append the next page once the tail of the loaded rows becomes visible.
    val shouldAppend by remember(state.threads.size, state.page, state.totalPages) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 2
        }
    }
    LaunchedEffect(shouldAppend, state.canLoadMore) {
        if (shouldAppend && state.canLoadMore && !state.isAppending) {
            viewModel.loadMoreThreads()
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MiuixTopAppBar(
                title = forumName.ifBlank { state.forumName },
                backdrop = chrome.backdrop,
                scrollBehavior = chrome.scrollBehavior,
                navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
                actions = {
                    MiuixTopBarIconButton(
                        onClick = {
                            // Carry the board name as the search field's initial hint; a blank
                            // name (not resolved yet) still opens the page.
                            navigator.push(Route.ForumSearch(forumName.ifBlank { state.forumName }))
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.forum_search),
                        )
                    }
                    MiuixTopBarIconButton(
                        onClick = { navigator.push(Route.NewThread(fid, forumName.ifBlank { state.forumName })) },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.forum_new_thread),
                        )
                    }
                },
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
        Box(modifier = chrome.contentBackdropModifier.fillMaxSize()) {
            PullToRefresh(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refreshForum,
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
                    // 主题分类 strip, directly under the top bar and part of the scroll content so
                    // it behaves like the 导读 tabs. Boards without categories print none here.
                    if (state.categories.isNotEmpty()) {
                        item(key = "forum_categories") {
                            ForumCategoryTabs(
                                categories = state.categories,
                                selectedTypeId = state.typeId,
                                onSelect = viewModel::selectCategory,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }
                    }
                    if (state.isLoading) {
                        item { ForumLoadingCard() }
                    } else if (state.error != null && state.threads.isEmpty()) {
                        item {
                            ForumErrorCard(
                                message = state.error.orEmpty(),
                                onRetry = viewModel::refreshForum,
                            )
                        }
                    } else {
                        ForumListBody(
                            state = state,
                            onOpenThread = { thread ->
                                navigator.push(Route.ForumThread(thread.tid, thread.title))
                            },
                            onLoadMore = viewModel::loadMoreThreads,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 主题分类 tabs of a board, mirroring the 导读 page's Miuix [TabRow].
 *
 * The site prints these as a horizontally scrollable strip (`#forumlist_time_li`), and Miuix
 * [TabRow] is a lazy horizontal row with the same behaviour: a board with more categories than
 * fit scrolls instead of squeezing every tab. The selected tab gets the theme's container in both
 * OS3 and OS4, so no style branch is needed. The `typeId = null` entry is the site's 全部 tab; its
 * label comes from resources so it follows the app locale instead of the site's Chinese one.
 */
@Composable
private fun ForumCategoryTabs(
    categories: List<ForumCategory>,
    selectedTypeId: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = categories.map { category ->
        if (category.typeId == null) stringResource(R.string.forum_categories_all) else category.name
    }
    TabRow(
        tabs = labels,
        selectedTabIndex = categories.indexOfFirst { it.typeId == selectedTypeId }.coerceAtLeast(0),
        onTabSelected = { index -> onSelect(categories.getOrNull(index)?.typeId) },
        modifier = modifier.fillMaxWidth(),
    )
}

/** The paged rows of one board, split out so the page body stays readable. */
private fun androidx.compose.foundation.lazy.LazyListScope.ForumListBody(
    state: ForumListState,
    onOpenThread: (io.rune.mt.forum.domain.forum.model.ForumThread) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (state.notices.isNotEmpty()) {
        items(items = state.notices, key = { "notice_${it.tid}" }) { notice ->
            ForumThreadCard(thread = notice, onClick = { onOpenThread(notice) })
        }
    }

    if (state.threads.isEmpty()) {
        item { ForumEmptyCard(stringResource(R.string.forum_empty_threads)) }
    } else {
        items(items = state.threads, key = { "thread_${it.tid}" }) { thread ->
            ForumThreadCard(thread = thread, onClick = { onOpenThread(thread) })
        }
    }

    item {
        ForumPagingFooter(
            page = state.page,
            totalPages = state.totalPages,
            isAppending = state.isAppending,
            onLoadMore = onLoadMore,
        )
    }
}
/**
 * Tail of a paged list: the "load page N" affordance, an inline spinner while appending, or an
 * end marker once the last page is on screen.
 */
@Composable
internal fun ForumPagingFooter(
    page: Int,
    totalPages: Int,
    isAppending: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isAppending -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.forum_loading_more),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            page < totalPages -> Card(onClick = onLoadMore) {
                Text(
                    text = stringResource(R.string.forum_load_more_format, page + 1),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            else -> Text(
                text = stringResource(R.string.forum_last_page),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * Content insets for the forum sub-pages.
 *
 * Sides and top come from the scaffold plus the landscape cutout; the bottom only needs the
 * enclosing layout's inset because the scaffold already reports the rest.
 */
internal fun forumInsets(
    inner: PaddingValues,
    horizontal: PaddingValues,
    outer: PaddingValues,
    direction: LayoutDirection,
    gutter: Dp,
): PaddingValues = PaddingValues(
    start = inner.calculateStartPadding(direction) +
        horizontal.calculateStartPadding(direction) + gutter,
    top = inner.calculateTopPadding(),
    end = inner.calculateEndPadding(direction) +
        horizontal.calculateEndPadding(direction) + gutter,
    bottom = outer.calculateBottomPadding() + gutter,
)
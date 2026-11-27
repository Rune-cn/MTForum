// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumHotEntry
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.ui.icons.AppMiuixIcons
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.page.miuix.widgets.miuixGroupedItems
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Forum root: the board grid (版块宫格) followed by the site's 热帖排行 block.
 *
 * The grid is a three-column layout of icon tiles, mirroring the mobile site's own board
 * shortcut block. Both sections come from one `forum.php` request, and the ranking is rendered in
 * the server's order with the server's rank numbers — never re-sorted client-side.
 *
 * The top bar carries three actions: the search magnifier, 发帖 and 每日签到 at the far right.
 * The search magnifier opens [`ForumSearchOverlay`] instead of navigating; confirming a keyword
 * lands on the existing `Route.ForumSearch` result page. 发帖 asks for a board with
 * [`ForumBoardPickerSheet`] and then pushes `Route.NewThread`; a guest is sent to the login page
 * first (rule 18). The 消息提醒 bell moved to the bottom bar's 消息 tab, which now splits private
 * messages and the three site notice categories with a `TabRow`.
 *
 * 每日签到 still opens [`ForumSignInSheet`] rather than signing in on the tap, and the settings
 * page does not host that panel (the user asked for the home entry point twice).
 */
@Composable
fun ForumHomePage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    viewModel: ForumViewModel = koinViewModel(),
) {
    val state by viewModel.homeState.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current
    // OS4 frames content with a wider gutter, so the grid matches the rest of the shell.
    val useMiuixOs4 = AppTheme.useMiuixOs4
    val listState = rememberLazyListState()

    var showSignIn by rememberSaveable { mutableStateOf(false) }
    var showBoardPicker by rememberSaveable { mutableStateOf(false) }
    // The search surface is a page-level overlay, not a route: closing it (Back) returns to the
    // exact same home scroll position, and submitting pushes the existing result page.
    var showSearch by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Timber.tag("ForumHomePage").i("home shown")
        viewModel.loadHomeIfNeeded()
        // The 最新主题 feed is a separate request from the index; the owner opened the app for the
        // threads, so it is not gated on the board grid succeeding.
        viewModel.loadHomeLatestIfNeeded()
    }

    // The home list is paged now: the site's 热帖排行 is a top-10, so the 最新主题 feed below it is
    // what carries the thread stream forward. Same trigger as the board/guide lists (and the same
    // explicit footer row), hosted in the bottom-bar pager exactly like those pages.
    ForumAppendEffect(
        listState = listState,
        enabled = state.canLoadMoreLatest,
        isAppending = state.isAppendingLatest,
        hasError = state.latestError != null,
        onLoadMore = viewModel::loadMoreHomeLatest,
    )

    // The page root Box exists for the 签到 sheet: it must be a sibling of the scaffold when it is
    // the OS4 inline overlay, so it is measured against the page and stays outside the content
    // subtree `chrome.backdrop` records (the placement and sampling rules of the thread overflow
    // menu, rules 12 / 23).
    Box(modifier = Modifier.fillMaxSize()) {
        MiuixPageScaffold(
            title = title,
            chrome = chrome,
            outerPadding = outerPadding,
            horizontalPadding = if (useMiuixOs4) 16.dp else 12.dp,
            listState = listState,
            verticalPadding = 12.dp,
            actions = {
                // Search lives at the leading edge of the actions (left of 发帖): a magnifier that
                // opens the overlay surface, following the Miuix example's search interaction
                // (`GlassPage.kt` opens `GlassSearchOverlay` from a top-bar action) instead of the
                // old in-list card.
                MiuixTopBarIconButton(onClick = { showSearch = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.forum_search),
                    )
                }
                // 发帖 needs a board first: the home index has no "current board", so the sheet
                // offers `state.forums` and the picked board travels to `Route.NewThread`. A guest
                // is routed to login before the composer can be reached (rule 18).
                MiuixTopBarIconButton(
                    onClick = {
                        if (session.isLoggedIn) {
                            showBoardPicker = true
                        } else {
                            navigator.push(Route.Login)
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.forum_new_thread),
                    )
                }
                // 每日签到 sits at the screen's top-right edge (last action of the bar). The tap
                // opens the sign-in sheet instead of signing in: the counters and the site's own
                // result wording are what make the action legible, and a silent GET behind an icon
                // would report its outcome in a snackbar the user is not looking at.
                MiuixTopBarIconButton(onClick = { showSignIn = true }) {
                    Icon(
                        imageVector = AppMiuixIcons.Tasks,
                        contentDescription = stringResource(R.string.sign_in_section_title),
                    )
                }
            },
        ) {
            when {
                state.isLoading -> item { ForumLoadingCard() }

                state.error != null -> item {
                    ForumErrorCard(
                        message = state.error.orEmpty(),
                        onRetry = { viewModel.loadHome(refresh = true) },
                    )
                }

                else -> {
                    if (state.forums.isNotEmpty()) {
                        item { SmallTitle(stringResource(R.string.forum_boards)) }
                        items(
                            items = state.forums.chunked(FORUM_GRID_COLUMNS),
                            key = { row -> "board_row_${row.first().fid}" },
                        ) { row ->
                            ForumBoardRow(
                                forums = row,
                                onBoardClick = { forum ->
                                    navigator.push(Route.ForumList(forum.fid, forum.name))
                                },
                            )
                        }
                    }

                    if (state.hotRanking.isNotEmpty()) {
                        item { SmallTitle(stringResource(R.string.forum_hot_ranking)) }
                        // The site prints 热帖排行 as one contiguous block, and the example renders
                        // consecutive rows of a section as a single grouped card
                        // (`component/GroupedCardItems.kt` + `MainPage.kt`). Grouping the ranking the
                        // same way replaces the stack of ten separate cards with one card whose rows
                        // are square between the first and last.
                        miuixGroupedItems(
                            items = state.hotRanking,
                            keyPrefix = "hot",
                            key = { entry -> "hot_${entry.tid}" },
                            outerBottomPadding = 12.dp,
                            insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        ) { entry, _ ->
                            ForumRankRow(
                                entry = entry,
                                onClick = { navigator.push(Route.ForumThread(entry.tid, entry.title)) },
                            )
                        }
                    }

                    if (state.forums.isEmpty() && state.hotRanking.isEmpty()) {
                        item { ForumEmptyCard(stringResource(R.string.forum_empty_threads)) }
                    }

                    // The thread stream the site itself renders under the ranking: paged in the
                    // server's order, with the shared footer as the tap path and the append effect
                    // as the scroll path (rules 16 / 20.3).
                    ForumHomeLatestFeed(
                        state = state,
                        onOpenThread = { thread ->
                            navigator.push(Route.ForumThread(thread.tid, thread.title))
                        },
                        onLoadMore = viewModel::loadMoreHomeLatest,
                        onRetry = viewModel::loadHomeLatestIfNeeded,
                    )

                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        // No proactive fetch and therefore no 未签到 badge on the button: knowing the day's state
        // before the user opens the sheet would cost a `k_misign-sign.html` request on every home
        // entry, and polling is out of the question (rule 27). The first open loads the page once;
        // `ForumSignInViewModel` is activity-scoped, so later opens reuse it. The flag is saved so
        // the sheet comes back after the login page it may have sent the user to.
        ForumSignInSheet(
            show = showSignIn,
            onDismissRequest = { showSignIn = false },
            onLogin = { navigator.push(Route.Login) },
            backdrop = chrome.backdrop,
        )

        // Both overlays are siblings of the scaffold in the page root Box (rules 12/23/28): the
        // OS4 bottom sheet renders inline into the root host's dialog layer, and the search
        // surface covers the page including its bar.
        ForumBoardPickerSheet(
            show = showBoardPicker,
            boards = state.forums,
            onDismissRequest = { showBoardPicker = false },
            onPick = { forum ->
                showBoardPicker = false
                navigator.push(Route.NewThread(forum.fid, forum.name))
            },
            backdrop = chrome.backdrop,
        )

        ForumSearchOverlay(
            expanded = showSearch,
            enableBlur = enableBlur,
            onDismissRequest = { showSearch = false },
            onSubmit = { keyword ->
                if (keyword.isNotBlank()) {
                    showSearch = false
                    navigator.push(Route.ForumSearch(keyword))
                }
            },
        )
    }
}
/** The grid stays three columns wide on phones, matching the site's shortcut block. */
private const val FORUM_GRID_COLUMNS = 3

/**
 * The home page's 最新主题 feed: the thread stream the site renders below 热帖排行.
 *
 * The ranking above is the site's own **top-10**, so it is complete by definition and can never
 * grow; the site's home page continues beneath it with a list that loads further pages (the mobile
 * template does that with an AJAX loader at the end of `forum.php`, `index.html`'s
 * `comiis_loadlistbo_1`). This section is that stream in its paged form: the 导读 `view=newthread`
 * listing, which is the same server-ordered thread list every other page uses, with real `div.pg`
 * paging. Rows are appended, never re-sorted or re-sliced (rule 16).
 *
 * The footer is always the last row once the first page has arrived, so scrolling to the tail and
 * tapping "load page N" are two ways to the same request; a failed page leaves the footer in place
 * as the retry (the automatic trigger stays off while an error is shown).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.ForumHomeLatestFeed(
    state: ForumHomeState,
    onOpenThread: (ForumThread) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state.latestThreads.isNotEmpty()) {
        item(key = "home_latest_title") {
            SmallTitle(stringResource(R.string.forum_home_latest))
        }
        items(items = state.latestThreads, key = { "home_latest_${it.tid}" }) { thread ->
            ForumThreadCard(thread = thread, onClick = { onOpenThread(thread) })
        }
    } else if (state.latestError != null) {
        item(key = "home_latest_error") {
            ForumErrorCard(message = state.latestError.orEmpty(), onRetry = onRetry)
        }
    } else if (state.latestLoaded) {
        item(key = "home_latest_empty") {
            ForumEmptyCard(stringResource(R.string.forum_empty_threads))
        }
    }

    if (state.latestLoaded) {
        item(key = "home_latest_footer") {
            ForumPagingFooter(
                page = state.latestPage,
                totalPages = state.latestTotalPages,
                isAppending = state.isAppendingLatest,
                onLoadMore = onLoadMore,
            )
        }
    }
}

/**
 * Entry card of the in-forum search, placed above the board grid.
 *
 * The site offers search only from a tucked-away tab, so the home screen gives it one visible row;
 * it is rendered before the grid and never depends on the index request finishing.
 */
@Composable
private fun ForumSearchEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        onClick = onClick,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.forum_search),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.forum_search_hint),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Reply count above which a thread is shown with a 热门 chip. */
private const val HOT_REPLY_THRESHOLD = 20

/**
 * One grid row of boards. A short row is padded with weight-less spacers so the tiles keep
 * their width instead of stretching across the leftover space.
 */
@Composable
private fun ForumBoardRow(
    forums: List<Forum>,
    onBoardClick: (Forum) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        forums.forEach { forum ->
            ForumBoardTile(
                forum = forum,
                modifier = Modifier.weight(1f),
                onClick = { onBoardClick(forum) },
            )
        }
        repeat(FORUM_GRID_COLUMNS - forums.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** One board tile: icon (when the site exposes one), name and thread count. */
@Composable
private fun ForumBoardTile(
    forum: Forum,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(modifier = modifier, onClick = onClick, insideMargin = PaddingValues(vertical = 12.dp, horizontal = 8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ForumBoardIcon(forum)
            Spacer(Modifier.height(8.dp))
            Text(
                text = forum.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (forum.threadCount != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.forum_board_threads_format, forum.threadCount),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Board icon with a text fallback.
 *
 * The site lazy-loads these (`comiis_loadimages`), so the URL is frequently absent; rendering
 * the first character instead keeps every tile visually anchored.
 */
@Composable
private fun ForumBoardIcon(forum: Forum) {
    val iconUrl = forum.iconUrl
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (iconUrl.isNullOrBlank()) {
            Text(
                text = forum.name.take(1),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            AsyncImage(
                model = iconUrl,
                contentDescription = forum.name,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * One thread row, used by the hot list and the board listing.
 *
 * Layout follows the site's own card order — byline with the poster's avatar, title, excerpt,
 * thumbnails — and distinguishes a pinned/digest thread by a tinted card plus a badge instead of
 * a section heading, so a pinned row reads as "special" without a separate title above it.
 */
@Composable
internal fun ForumThreadCard(
    thread: ForumThread,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlighted = thread.pinned || thread.digest
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        onClick = onClick,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = if (highlighted) {
            CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh)
        } else {
            CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Byline first: the poster's avatar anchors every card, pinned or not. The site's
            // 置顶 strip prints title-only announcements without any byline; the view model patches
            // the real author in afterwards, and until (or if) that fails the slot shows a pin
            // glyph instead of a meaningless "?" letter.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thread.authorName.isBlank() && thread.authorAvatarUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = stringResource(R.string.forum_pinned_badge),
                            tint = MiuixTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    ForumAvatar(url = thread.authorAvatarUrl, name = thread.authorName, size = 28.dp)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (thread.authorName.isNotBlank()) {
                            Text(
                                text = thread.authorName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        thread.authorLevel?.takeIf { it.isNotBlank() }?.let { level ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = level,
                                fontSize = 10.sp,
                                color = MiuixTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                }
                thread.timeText?.takeIf { it.isNotBlank() }?.let { time ->
                    Text(
                        text = time,
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = thread.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 23.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            thread.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (thread.imageUrls.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ForumThumbnailRow(urls = thread.imageUrls)
            }


            // Status chips: only facts the parser actually has. The list carries 置顶 (em.comiis_xifont)
            // and 精华 (span.bg_c "精") markers; the title anchor also inlines a 热度 icon, which is
            // not a chip of its own. Original/recommendation markers are still not exposed.
            if (thread.pinned || thread.digest || thread.recommendCount > 0 || thread.imageUrls.isNotEmpty() || thread.replyCount >= HOT_REPLY_THRESHOLD) {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (thread.pinned) ForumBadge(text = stringResource(R.string.forum_pinned_badge))
                    if (thread.digest) ForumBadge(text = stringResource(R.string.forum_digest_badge))
                    if (thread.recommendCount > 0) ForumBadge(text = stringResource(R.string.forum_tag_featured))
                    if (thread.imageUrls.isNotEmpty()) ForumBadge(text = stringResource(R.string.forum_tag_with_image))
                    if (thread.replyCount >= HOT_REPLY_THRESHOLD) ForumBadge(text = stringResource(R.string.forum_tag_hot))
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val meta = buildList {
                    if (thread.replyCount > 0) add(stringResource(R.string.forum_thread_replies_format, thread.replyCount))
                    if (thread.viewCount > 0) add(stringResource(R.string.forum_thread_views_format, thread.viewCount))
                    if (thread.recommendCount > 0) add(stringResource(R.string.forum_thread_likes_format, thread.recommendCount))
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" · "),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/** Up to three square thumbnails, sized so one and three pictures both look deliberate. */
@Composable
private fun ForumThumbnailRow(urls: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        urls.take(3).forEach { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .then(if (urls.size >= 3) Modifier.size(78.dp) else Modifier.weight(1f).height(78.dp))
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}


/** Author · level · replies · views · time, skipping the parts the parser could not fill. */
@Composable
internal fun ForumThreadMeta(thread: ForumThread, modifier: Modifier = Modifier) {
    val meta = buildList {
        if (thread.authorName.isNotBlank()) add(thread.authorName)
        if (thread.replyCount > 0) add(stringResource(R.string.forum_thread_replies_format, thread.replyCount))
        if (thread.viewCount > 0) add(stringResource(R.string.forum_thread_views_format, thread.viewCount))
        thread.timeText?.takeIf { it.isNotBlank() }?.let(::add)
    }
    if (meta.isEmpty()) return
    Text(
        text = meta.joinToString(" · "),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        fontSize = 11.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Small pill used for 置顶 / 楼主 style markers. */
@Composable
internal fun ForumBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onPrimaryContainer,
    )
}

/**
 * One row of the site's 热帖排行 block.
 *
 * The site supplies the rank, the title and the link — nothing else — so the row shows exactly
 * that instead of inventing reply/view metadata. Ranking is never recomputed: the list is
 * rendered in server order with the server's own badge number, and the top three keep the
 * tinted badge they have on the site.
 *
 * The row itself is padding-free: it is drawn inside one grouped card
 * (`miuixGroupedItems`), which owns the gutters and corners.
 */
@Composable
private fun ForumRankRow(
    entry: ForumHotEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.rank.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (entry.rank <= 3) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = entry.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 21.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

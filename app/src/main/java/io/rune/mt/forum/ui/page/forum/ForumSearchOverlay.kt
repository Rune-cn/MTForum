// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassSearchOverlay
import top.yukonga.miuix.kmp.glass.GlassSearchOverlayDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The app's **only** search entry, opened by the 论坛 home bar's magnifier.
 *
 * Shape borrowed from the Miuix example app:
 *  - OS4: `GlassSearchOverlay` (example `GlassPage.kt` sets `searchExpanded` from its top-bar
 *    action and places the overlay above the page scaffold). The overlay owns the full-screen
 *    surface, the glass search pill and its reveal animation; the action does not open a route.
 *  - OS3: the closest non-glass equivalent — full-page Miuix surface that fades/scales in from the
 *    top-end corner (the action's corner), an automatically focused `TextField`, and an explicit
 *    cancel button. It is built here rather than with `SearchBar` because `SearchBar`'s expanded
 *    mode is driven by tapping its own always-present capsule; an external top-bar icon has no
 *    capsule to morph from.
 *
 * Submitting hands the keyword to the existing result page (`Route.ForumSearch(keyword)`), which
 * is where the site's POST search runs. Every submit path — the confirm row, a recent-search row
 * and (on OS3, where this file owns the field) the IME search key — goes through [submitKeyword],
 * which trims the text, records it in [ForumSearchHistoryViewModel] and only then navigates. The
 * keyword therefore always reaches the results page; an empty query is the only thing rejected.
 *
 * `GlassSearchOverlay` exposes no submit callback: its field uses `ImeAction.Done` and hides the
 * keyboard, so the OS4 branch renders the confirm row as the submit affordance and the same row is
 * kept on OS3 as the explicit alternative to the IME search key.
 *
 * Back: OS4's overlay calls `onDismissRequest` on Back itself. OS3 registers a BackHandler that
 * exists only while the surface is open — not a resident entry-level handler, so the predictive
 * back gesture driver for the navigation stack is untouched (rule 15).
 *
 * Recent searches come from [ForumSearchHistoryViewModel] (DataStore, capped at
 * [io.rune.mt.forum.domain.forum.repository.FORUM_SEARCH_HISTORY_LIMIT]); the 清除 action empties
 * the list in place. Nothing here samples `pageBackdrop` (rules 23/28) — the overlay is an inline
 * surface and only draws its own background.
 */
@Composable
internal fun ForumSearchOverlay(
    expanded: Boolean,
    enableBlur: Boolean,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    historyViewModel: ForumSearchHistoryViewModel = koinViewModel(),
) {
    val recent by historyViewModel.recent.collectAsStateWithLifecycle()
    // The store keeps more than the surface shows: a search panel is a shortcut list, not a
    // history browser, and capping it keeps the confirm row reachable while the IME is open.
    val visibleRecent = recent.take(VISIBLE_RECENT_LIMIT)

    // Single submit path for both styles: trim, remember, then navigate. The results page repeats
    // the same guard, so a blank query can never open a search route.
    val submitKeyword: (String) -> Unit = { raw ->
        val keyword = raw.trim()
        if (keyword.isNotEmpty()) {
            historyViewModel.record(keyword)
            onSubmit(keyword)
        }
    }

    if (AppTheme.useMiuixOs4) {
        GlassSearchOverlay(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            label = stringResource(R.string.forum_search_hint),
            topInsets = WindowInsets.statusBars,
            blurEnabled = enableBlur,
            searchIcon = Icons.Rounded.Search,
            closeIcon = Icons.Rounded.Close,
        ) { query ->
            SearchSuggestions(
                query = query,
                recent = visibleRecent,
                topPadding = searchResultTopPadding() + GlassSearchOverlayDefaults.VerticalPadding * 2,
                onSubmit = submitKeyword,
                onClearHistory = historyViewModel::clear,
            )
        }
    } else {
        Os3SearchSurface(
            expanded = expanded,
            recent = visibleRecent,
            onDismissRequest = onDismissRequest,
            onSubmit = submitKeyword,
            onClearHistory = historyViewModel::clear,
        )
    }
}

/**
 * OS3 search surface: fills the page pane, grows from the top-end corner, auto-focuses the field
 * and closes on Back / cancel. The translucent surface keeps the page faintly visible, which is
 * the non-glass counterpart of the example's dimmed backdrop.
 */
@Composable
private fun Os3SearchSurface(
    expanded: Boolean,
    recent: List<String>,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = expanded) {
        keyboard?.hide()
        onDismissRequest()
    }

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(160)) +
            scaleIn(
                animationSpec = tween(220),
                initialScale = 0.9f,
                transformOrigin = TransformOrigin(1f, 0f),
            ),
        exit = fadeOut(tween(120)) +
            scaleOut(
                animationSpec = tween(160),
                targetScale = 0.95f,
                transformOrigin = TransformOrigin(1f, 0f),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.94f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 8.dp)
                    // With the IME open the page pane shrinks; the surface scrolls so the confirm
                    // row and the recent rows stay reachable on a short window.
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
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
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                // IME search = submit: record the keyword and push the results.
                                keyboard?.hide()
                                onSubmit(query)
                            },
                        ),
                    )
                    TextButton(
                        text = stringResource(R.string.forum_search_cancel),
                        onClick = {
                            keyboard?.hide()
                            onDismissRequest()
                        },
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }

                SearchSuggestions(
                    query = query,
                    recent = recent,
                    topPadding = 12.dp,
                    onSubmit = { keyword ->
                        keyboard?.hide()
                        onSubmit(keyword)
                    },
                    onClearHistory = onClearHistory,
                )
            }
        }
    }

    // The field cannot be focused while it is not composed; keyed on the open transition so every
    // opening focuses the field and shows the IME (the example's overlay does the same internally).
    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            // A dismissed surface starts clean on the next open instead of restoring the
            // previous keyword; the result page keeps that keyword once submitted.
            query = ""
        }
    }
}

/**
 * Below-the-field content shared by both styles: the confirm row while a keyword is typed, and the
 * recent-search shortcuts the rest of the time.
 *
 * The confirm row is what actually carries the keyword into `Route.ForumSearch` — it is rendered
 * from the live query, not from a remembered string, so the text on screen and the text submitted
 * cannot drift apart.
 */
@Composable
private fun SearchSuggestions(
    query: String,
    recent: List<String>,
    topPadding: Dp,
    onSubmit: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val keyword = query.trim()
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(topPadding))
        if (keyword.isNotEmpty()) {
            ConfirmSearchRow(keyword = keyword, onSubmit = onSubmit)
        }
        if (recent.isNotEmpty()) {
            RecentSearchHeader(onClearHistory = onClearHistory)
            recent.forEach { entry ->
                RecentSearchRow(entry = entry, onClick = { onSubmit(entry) })
            }
        }
    }
}

/** One tappable result row that confirms the live query. */
@Composable
private fun ConfirmSearchRow(keyword: String, onSubmit: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onSubmit(keyword) },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                Text(
                    text = stringResource(R.string.forum_search_submit_format, keyword),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** "最近搜索" heading plus the 清除 action; only rendered while the list is non-empty. */
@Composable
private fun RecentSearchHeader(onClearHistory: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.forum_search_recent),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = stringResource(R.string.forum_search_clear_history),
            onClick = onClearHistory,
        )
    }
}

/** One one-tap repeat of a stored keyword; tapping it searches immediately. */
@Composable
private fun RecentSearchRow(entry: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = entry,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Height reserved under the OS4 search pill so the suggestions sit below it, not beneath it. */
@Composable
private fun searchResultTopPadding() = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
    GlassSearchOverlayDefaults.BarHeight

/**
 * How many stored keywords the surface lists.
 *
 * The repository keeps [io.rune.mt.forum.domain.forum.repository.FORUM_SEARCH_HISTORY_LIMIT]; the
 * overlay shows only the newest few so the panel stays a shortcut list and the confirm row never
 * ends up below the keyboard.
 */
private const val VISIBLE_RECENT_LIMIT = 6
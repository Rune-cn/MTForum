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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassSearchOverlay
import top.yukonga.miuix.kmp.glass.GlassSearchOverlayDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The home top bar's search surface, opened by the magnifier action.
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
 * is what the old in-list search card did. `GlassSearchOverlay` has no submit callback (its content
 * receives the live query), so the OS4 branch renders one "Search for …" row as the confirm
 * action; the OS3 branch also wires the IME search key and the same row.
 *
 * Back: OS4's overlay calls `onDismissRequest` on Back itself. OS3 registers a BackHandler that
 * exists only while the surface is open — not a resident entry-level handler, so the predictive
 * back gesture driver for the navigation stack is untouched (rule 15).
 */
@Composable
internal fun ForumSearchOverlay(
    expanded: Boolean,
    enableBlur: Boolean,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
) {
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
            SearchConfirmRow(
                query = query,
                topPadding = searchResultTopPadding() + GlassSearchOverlayDefaults.VerticalPadding * 2,
                onSubmit = onSubmit,
            )
        }
    } else {
        Os3SearchSurface(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            onSubmit = onSubmit,
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
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
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
                    .padding(top = 8.dp),
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
                                keyboard?.hide()
                                onSubmit(query.trim())
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

                SearchConfirmRow(
                    query = query,
                    topPadding = 12.dp,
                    onSubmit = { keyword ->
                        keyboard?.hide()
                        onSubmit(keyword)
                    },
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
 * One tappable result row that confirms the query. Both styles show it: the OS4 glass overlay only
 * exposes the live query, and on OS3 it is a second, explicit path besides the IME search key.
 */
@Composable
private fun SearchConfirmRow(
    query: String,
    topPadding: Dp,
    onSubmit: (String) -> Unit,
) {
    val keyword = query.trim()
    if (keyword.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column {
            Spacer(Modifier.height(topPadding))
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
}

/** Height reserved under the OS4 search pill so the confirm row sits below it, not beneath it. */
@Composable
private fun searchResultTopPadding() = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
    GlassSearchOverlayDefaults.BarHeight
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumThreadHistoryEntry
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixConfirmDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浏览历史: the threads this device has opened, most recent first.
 *
 * The list is **device-local** ([io.rune.mt.forum.domain.forum.repository.ForumThreadHistoryRepository]),
 * written by the thread page once its first page of floors has loaded, so the screen works signed
 * in or out and never sends a request. Tapping a row reopens the thread; the row's trash icon asks
 * for confirmation before the entry is dropped, and the top bar's 清空 action asks for confirmation
 * before the whole store is cleared (both use the shared [MiuixConfirmDialog]).
 *
 * The body renders [ForumThreadHistoryPhase], i.e. loading / empty ("还没有浏览记录") / failure as
 * three distinct cards — a failed read must never look like an empty history.
 */
@Composable
fun ForumThreadHistoryPage(
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumThreadHistoryViewModel = koinViewModel(),
) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The dialogs hold the row being deleted so the confirmation text can name it.
    var pendingDelete by remember { mutableStateOf<ForumThreadHistoryEntry?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    MiuixPageScaffold(
        title = stringResource(R.string.thread_history_title),
        chrome = rememberMiuixPageChrome(enableBlur),
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        actions = {
            // 清空 is only offered when there is something to clear; the confirmation below is
            // mandatory because a cleared history cannot be rebuilt.
            if (state.entries.isNotEmpty()) {
                MiuixTopBarIconButton(onClick = { showClearConfirm = true }) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.thread_history_clear_action),
                    )
                }
            }
        },
        horizontalPadding = if (AppTheme.useMiuixOs4) 16.dp else 12.dp,
        verticalPadding = 12.dp,
        outerPadding = outerPadding,
    ) {
        when (state.phase) {
            ForumThreadHistoryPhase.Loading -> item { ForumLoadingCard() }

            // A failed store read keeps its reason and a retry, so it cannot be mistaken for
            // "no browsing history yet".
            ForumThreadHistoryPhase.Error -> item {
                ForumErrorCard(message = state.error.orEmpty(), onRetry = viewModel::retry)
            }

            ForumThreadHistoryPhase.Empty -> item {
                ForumEmptyCard(text = stringResource(R.string.thread_history_empty))
            }

            ForumThreadHistoryPhase.Rows -> items(
                items = state.entries,
                key = { "thread_history_${it.tid}" },
            ) { entry ->
                ThreadHistoryRow(
                    entry = entry,
                    onOpen = { navigator.push(Route.ForumThread(entry.tid, entry.title)) },
                    onDelete = { pendingDelete = entry },
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        val label = entry.title.ifBlank { "#${entry.tid}" }
        MiuixConfirmDialog(
            visible = true,
            title = stringResource(R.string.thread_history_delete_title),
            message = stringResource(R.string.thread_history_delete_message_format, label),
            confirmText = stringResource(R.string.thread_history_delete_action),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                val target = pendingDelete
                pendingDelete = null
                if (target != null) viewModel.remove(target.tid)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showClearConfirm) {
        MiuixConfirmDialog(
            visible = true,
            title = stringResource(R.string.thread_history_clear_title),
            message = stringResource(R.string.thread_history_clear_message),
            confirmText = stringResource(R.string.thread_history_clear_action),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                showClearConfirm = false
                viewModel.clear()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/** One visit row: the thread title plus board + "last opened" time, and a trailing trash button. */
@Composable
private fun ThreadHistoryRow(
    entry: ForumThreadHistoryEntry,
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
                    text = entry.title.ifBlank { "#${entry.tid}" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    entry.forumName?.takeIf { it.isNotBlank() },
                    visitedLabel(entry.visitedAt),
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
                    contentDescription = stringResource(R.string.thread_history_delete_action),
                    tint = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The visit time written the device's own way ("3 minutes ago", "3 分钟前", …).
 *
 * The platform formatter is used instead of a hand-rolled label set: it already covers every
 * locale the app can run in (including the two Chinese translations), so no extra strings are
 * needed and the wording tracks the system language.
 */
private fun visitedLabel(visitedAt: Long): String =
    DateUtils.getRelativeTimeSpanString(
        visitedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

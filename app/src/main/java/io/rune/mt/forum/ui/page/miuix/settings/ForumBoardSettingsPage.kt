// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.forum.ForumBoardSettingsViewModel
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The 板块管理 settings screen: every board from the site index
 * ([io.rune.mt.forum.domain.forum.repository.ForumRepository.getHome], never hard-coded) with
 * one row per board: a long-press drag handle reorders it live (the row follows the finger and the
 * list re-slots as it crosses a neighbour), the row prints its current position, and the explicit
 * 上移/下移 buttons stay for accessibility.
 *
 * The same persisted `orderedFids` + `hiddenFids` drive the home grid and the composer's board
 * picker; hidden boards are only removed from those surfaces and remain reachable by deep link.
 */
@Composable
fun ForumBoardSettingsPage(
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumBoardSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    // Drag state lives on the page, not on a row: the row that is being dragged can be re-slotted
    // (the list is keyed by fid) and must keep its offset while it moves.
    var draggingFid by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    MiuixPageScaffold(
        title = stringResource(R.string.forum_board_settings_title),
        chrome = rememberMiuixPageChrome(enableBlur),
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        outerPadding = outerPadding,
    ) {
        item { Spacer(Modifier.size(12.dp)) }
        when {
            state.isLoading && state.boards.isEmpty() -> item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.forum_board_settings_empty),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            state.error != null && state.boards.isEmpty() -> item {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.forum_board_settings_error, state.error.orEmpty()),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.error,
                    )
                    TextButton(
                        text = stringResource(R.string.forum_board_settings_retry),
                        onClick = { viewModel.load(force = true) },
                    )
                }
            }

            state.boards.isEmpty() -> item {
                Text(
                    text = stringResource(R.string.forum_board_settings_empty),
                    modifier = Modifier.padding(20.dp),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            else -> itemsIndexed(items = state.boards, key = { _, board -> board.fid }) { index, board ->
                BoardSettingsRow(
                    board = board,
                    hidden = board.fid in state.hiddenFids,
                    position = index + 1,
                    total = state.boards.size,
                    dragging = draggingFid == board.fid,
                    dragOffset = if (draggingFid == board.fid) dragOffset else 0f,
                    onDragStart = {
                        draggingFid = board.fid
                        dragOffset = 0f
                    },
                    onDragBy = { dy ->
                        dragOffset += dy
                        // Cross 60% of a row height -> swap with the neighbour and carry the
                        // remainder, so a long drag keeps stepping instead of getting stuck.
                        val step = rowHeightPx * 0.6f
                        if (dragOffset > step) {
                            viewModel.moveDown(board.fid)
                            dragOffset -= rowHeightPx
                        } else if (dragOffset < -step) {
                            viewModel.moveUp(board.fid)
                            dragOffset += rowHeightPx
                        }
                    },
                    onDragEnd = {
                        draggingFid = null
                        dragOffset = 0f
                    },
                    onRowHeight = { height -> if (height > 1f) rowHeightPx = height },
                    onMoveUp = { viewModel.moveUp(board.fid) },
                    onMoveDown = { viewModel.moveDown(board.fid) },
                    onHiddenChange = { hidden -> viewModel.setHidden(board.fid, hidden) },
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.forum_board_settings_hidden_hint),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun BoardSettingsRow(
    board: Forum,
    hidden: Boolean,
    position: Int,
    total: Int,
    dragging: Boolean,
    dragOffset: Float,
    onDragStart: () -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onRowHeight: (Float) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHiddenChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                if (dragging) {
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { onRowHeight(it.size.height.toFloat()) }
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle: long-press anywhere on it and the row follows the finger; crossing a
            // neighbour re-slots the list, so the target position is visible while dragging.
            Text(
                text = "≡",
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .pointerInput(board.fid) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, amount ->
                                change.consume()
                                onDragBy(amount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        )
                    },
                fontSize = 20.sp,
                color = if (dragging) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = board.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.forum_board_settings_position, position, total) + " · " +
                        stringResource(
                            if (hidden) R.string.forum_board_settings_hide else R.string.forum_board_settings_shown,
                        ),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            TextButton(text = stringResource(R.string.forum_board_settings_move_up), onClick = onMoveUp)
            TextButton(text = stringResource(R.string.forum_board_settings_move_down), onClick = onMoveDown)
            Switch(checked = !hidden, onCheckedChange = { onHiddenChange(!it) })
        }
    }
}

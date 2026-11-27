// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixCardSegment
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * Board picker behind the home top bar's 发帖 action.
 *
 * The composer needs a board (`fid` + name), and the home screen only knows the board index
 * (`state.forums`), not a "current board". This sheet offers that index and opens
 * `Route.NewThread(fid, name)` with the picked board; the board listing keeps its own composer
 * entry, and both land on the same route.
 *
 * Style split (rule 8): OS4 gets the inline `GlassBottomSheet` (composed as a sibling of the page
 * scaffold in the page root `Box`), OS3 the classic `WindowBottomSheet`. [backdrop] is the page
 * chrome **content** layer; the OS4 sheet sits outside the subtree it records, so it cannot form
 * the RenderNode cycle of rules 23/28, and `null` (no render effect / SafeMode) falls back to the
 * opaque fill. It never receives the host `pageBackdrop`.
 *
 * Signed-out handling is the caller's job (rule 18): the home page routes to `Route.Login` before
 * opening this sheet, so a guest never picks a board and then hits a dead composer.
 */
@Composable
internal fun ForumBoardPickerSheet(
    show: Boolean,
    boards: List<Forum>,
    onDismissRequest: () -> Unit,
    onPick: (Forum) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    val title = stringResource(R.string.forum_new_thread_board_title)
    if (AppTheme.useMiuixOs4) {
        GlassBottomSheet(
            onDismissRequest = onDismissRequest,
            show = show,
            backdrop = backdrop,
            title = title,
            insideMargin = DpSize(16.dp, 0.dp),
            modifier = modifier,
        ) {
            BoardPickerBody(boards = boards, onPick = onPick)
        }
    } else {
        WindowBottomSheet(
            show = show,
            onDismissRequest = onDismissRequest,
            title = title,
            insideMargin = DpSize(16.dp, 0.dp),
            backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier,
        ) {
            BoardPickerBody(boards = boards, onPick = onPick)
        }
    }
}

/** The sheet's body: one grouped card of board rows, scrollable when the index is long. */
@Composable
private fun BoardPickerBody(
    boards: List<Forum>,
    onPick: (Forum) -> Unit,
) {
    if (boards.isEmpty()) {
        ForumLoadingCard()
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
    ) {
        boards.forEachIndexed { index, board ->
            MiuixCardSegment(
                isFirst = index == 0,
                isLast = index == boards.lastIndex,
                color = MiuixTheme.colorScheme.surfaceContainer,
                outerHorizontalPadding = 0.dp,
                insidePadding = PaddingValues(0.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(board) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = board.name.take(1),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = board.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (board.threadCount != null) {
                            Text(
                                text = stringResource(R.string.forum_board_threads_format, board.threadCount),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
}


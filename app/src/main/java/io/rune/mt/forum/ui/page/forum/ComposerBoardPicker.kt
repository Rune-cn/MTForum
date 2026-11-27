// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.ui.icons.AppMiuixIcons
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** Test tag of the board row in the composer card. */
internal const val COMPOSER_BOARD_ROW_TAG = "composer_board_row"

/** Test tag of the board list inside the picker sheet. */
internal const val COMPOSER_BOARD_LIST_TAG = "composer_board_list"

/**
 * The composer's 发布到 picker: a sheet listing the site's boards.
 *
 * The board a thread is posted into used to be fixed by the route that opened the composer
 * (`Route.NewThread(fid, forumName)`), so changing it meant backing out and finding the board
 * again. The list here is the **site's own index** — the same `getHome()` data the home grid uses,
 * never a hard-coded board list — and choosing one re-fetches that board's form (see
 * [NewThreadViewModel.selectBoard]): `formhash`/`posttime` are per form, and the submit URL is
 * built from the selected `fid`.
 *
 * Style split (rule 8): OS4 gets `GlassBottomSheet`, OS3 `WindowBottomSheet`. The OS4 sheet is an
 * inline overlay rendered into the root scaffold's dialog layer, so the host
 * `MiuixComponentHost` must be mounted (it always is in the app shell) and [backdrop] must be the
 * page-chrome content layer — never the app-wide page backdrop (rules 23/28).
 *
 * A **draft** keeps its board: its edit form carries `fid`/`tid`/`pid`, so re-pointing it at
 * another board is not something the site offers. The composer disables the row for drafts and
 * explains that instead of letting the tap fail silently (see [ComposerBoardRow]).
 */
@Composable
internal fun ComposerBoardPickerSheet(
    show: Boolean,
    boards: List<Forum>,
    selectedFid: Int,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (Forum) -> Unit,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.composer_board_title)
    if (AppTheme.useMiuixOs4) {
        GlassBottomSheet(
            onDismissRequest = onDismissRequest,
            show = show,
            backdrop = backdrop,
            title = title,
            insideMargin = DpSize(16.dp, 0.dp),
            modifier = modifier,
        ) {
            ComposerBoardList(boards, selectedFid, isLoading, error, onRetry, onSelect)
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
            ComposerBoardList(boards, selectedFid, isLoading, error, onRetry, onSelect)
        }
    }
}

/**
 * The sheet body: the boards in the site's order, the current one marked, plus the loading and
 * failure states. The list is capped so a long board index scrolls inside the sheet instead of
 * pushing the sheet over the screen.
 */
@Composable
private fun ComposerBoardList(
    boards: List<Forum>,
    selectedFid: Int,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (Forum) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            isLoading && boards.isEmpty() -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.composer_board_loading),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            boards.isEmpty() && error != null -> Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.composer_board_error, error),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.error,
                )
                TextButton(text = stringResource(R.string.composer_board_retry), onClick = onRetry)
            }

            boards.isEmpty() -> Text(
                text = stringResource(R.string.composer_board_loading),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .testTag(COMPOSER_BOARD_LIST_TAG),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items = boards, key = { it.fid }) { board ->
                    ComposerBoardListRow(
                        board = board,
                        selected = board.fid == selectedFid,
                        onClick = { onSelect(board) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** One board row: name, 今日 count when the index printed one, and a check for the current board. */
@Composable
private fun ComposerBoardListRow(
    board: Forum,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            // 48dp platform touch target for every row.
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = board.name,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        board.todayCount?.let { count ->
            Text(
                text = stringResource(R.string.composer_board_today_format, count),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = AppMiuixIcons.Ok,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The composer card's first row: **发布到：<板块名>** with a chevron, tappable to open the picker.
 *
 * For a site draft the row is not tappable and says why — a draft is bound to the board it was
 * created in, and pretending otherwise would mean posting a draft into another board, which the
 * edit form cannot express.
 */
@Composable
internal fun ComposerBoardRow(
    forumName: String,
    fid: Int,
    isLoadingForm: Boolean,
    isDraft: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !isDraft, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 2.dp, vertical = 8.dp)
            .testTag(COMPOSER_BOARD_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.composer_board_label,
                forumName.ifBlank { stringResource(R.string.composer_board_unknown_format, fid) },
            ),
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isDraft) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.primary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isLoadingForm) {
            InfiniteProgressIndicator(modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (!isDraft) {
            Icon(
                imageVector = AppMiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    if (isDraft) {
        Text(
            text = stringResource(R.string.composer_board_draft_locked),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.ui.icons.AppIcons
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.forum.ForumBoardSettingsViewModel
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import kotlin.math.roundToInt
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Tiles per grid row; the home board grid uses the same three columns. */
private const val BOARD_GRID_COLUMNS = 3

/** Gap between tiles, matching the home grid's spacing. */
private val BoardGridGap = 8.dp

/**
 * Fixed tile height: the drag re-slotting math needs a stable cell step, and a fixed height also
 * keeps short and long board names on the same baseline grid.
 */
private val BoardTileHeight = 108.dp

/** A hidden board keeps its tile (management must show every board) but grays out its name. */
private const val HIDDEN_TILE_ALPHA = 0.45f

/**
 * The 板块管理 settings screen: the site's own board index
 * ([io.rune.mt.forum.domain.forum.repository.ForumRepository.getHome], never hard-coded) rendered as
 * the same three-column grid as the home page, so what the user arranges here is what they see there.
 *
 * Interaction:
 *  - **Drag to reorder** — long-press a tile and it follows the finger (scale + elevation); crossing
 *    a neighbouring cell re-slots the grid live and persists the new order.
 *  - **Tap the eye** in a tile's top-right corner to hide/show the board. Hidden tiles stay in the
 *    grid, grayed out, so they can always be brought back; the home grid and the composer's board
 *    picker are the only surfaces they leave.
 *  - **▲ / ▼** in each tile's bottom row keep the old accessible move-up/move-down path (they are
 *    plain buttons with content descriptions, not a gesture).
 *
 * The persisted `orderedFids` + `hiddenFids` still come from
 * [io.rune.mt.forum.domain.forum.repository.ForumBoardPreferencesRepository]; the storage format is
 * untouched. Hidden boards remain reachable by deep link / direct URL.
 */
@Composable
fun ForumBoardSettingsPage(
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumBoardSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    // OS4 frames content with a wider gutter, matching the home grid.
    val useMiuixOs4 = AppTheme.useMiuixOs4

    // The DataStore write is asynchronous, but a drag has to re-slot the grid on the very next
    // frame (the finger and the tile must not diverge), so the page keeps an optimistic mirror of
    // the order. The mirror is dropped as soon as the persisted order catches up — unless a new
    // board arrived meanwhile, in which case the store's list wins because the user never saw it.
    var boards by remember { mutableStateOf(state.boards) }
    var orderPending by remember { mutableStateOf(false) }

    LaunchedEffect(state.boards) {
        if (!orderPending) {
            boards = state.boards
        } else if (boards.map { it.fid } == state.boards.map { it.fid }) {
            orderPending = false
            boards = state.boards
        }
    }

    // Drag state lives on the page: the tile that is being dragged is re-slotted (keyed by fid), so
    // its own composable cannot own the accumulated offset.
    var draggingFid by remember { mutableStateOf<Int?>(null) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var tileWidthPx by remember { mutableFloatStateOf(0f) }
    var tileHeightPx by remember { mutableFloatStateOf(0f) }
    val gapPx = with(LocalDensity.current) { BoardGridGap.toPx() }

    fun startDrag(fid: Int, index: Int) {
        draggingFid = fid
        draggingIndex = index
        dragOffsetX = 0f
        dragOffsetY = 0f
    }

    fun dragBy(dx: Float, dy: Float) {
        dragOffsetX += dx
        dragOffsetY += dy
        val current = draggingIndex
        val stepX = tileWidthPx + gapPx
        val stepY = tileHeightPx + gapPx
        if (current !in boards.indices || stepX <= 0f || stepY <= 0f) return
        val currentRow = current / BOARD_GRID_COLUMNS
        val currentColumn = current % BOARD_GRID_COLUMNS
        // Round to the nearest cell (half a step of travel = "crossed into the neighbour").
        val targetColumn = ((currentColumn * stepX + dragOffsetX) / stepX).roundToInt()
        val targetRow = ((currentRow * stepY + dragOffsetY) / stepY).roundToInt()
        val target = (targetRow * BOARD_GRID_COLUMNS + targetColumn).coerceIn(0, boards.lastIndex)
        if (target == current) return

        val next = boards.toMutableList()
        next.add(target, next.removeAt(current))
        boards = next
        draggingIndex = target
        // The tile now starts from the target cell instead of the old one, so subtract that cell
        // distance from the accumulated offset: the tile stays exactly under the finger.
        dragOffsetX -= (target % BOARD_GRID_COLUMNS - currentColumn) * stepX
        dragOffsetY -= (target / BOARD_GRID_COLUMNS - currentRow) * stepY
        orderPending = true
        viewModel.move(current, target)
    }

    fun endDrag() {
        draggingFid = null
        draggingIndex = -1
        dragOffsetX = 0f
        dragOffsetY = 0f
    }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    MiuixPageScaffold(
        title = stringResource(R.string.forum_board_settings_title),
        chrome = rememberMiuixPageChrome(enableBlur),
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        outerPadding = outerPadding,
        horizontalPadding = if (useMiuixOs4) 16.dp else 12.dp,
        verticalPadding = 12.dp,
    ) {
        when {
            state.isLoading && boards.isEmpty() -> item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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

            state.error != null && boards.isEmpty() -> item {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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

            boards.isEmpty() -> item {
                Text(
                    text = stringResource(R.string.forum_board_settings_empty),
                    modifier = Modifier.padding(vertical = 8.dp),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            else -> {
                item(key = "board_grid_title") {
                    SmallTitle(stringResource(R.string.forum_boards))
                }
                item(key = "board_grid") {
                    BoardSettingsGrid(
                        boards = boards,
                        hiddenFids = state.hiddenFids,
                        draggingFid = draggingFid,
                        dragOffsetX = dragOffsetX,
                        dragOffsetY = dragOffsetY,
                        onMeasured = { width, height ->
                            if (width > 0f && height > 0f &&
                                (width != tileWidthPx || height != tileHeightPx)
                            ) {
                                tileWidthPx = width
                                tileHeightPx = height
                            }
                        },
                        onDragStart = { fid, index -> startDrag(fid, index) },
                        onDragBy = { dx, dy -> dragBy(dx, dy) },
                        onDragEnd = { endDrag() },
                        onMoveUp = { index -> viewModel.moveUp(boards[index].fid) },
                        onMoveDown = { index -> viewModel.moveDown(boards[index].fid) },
                        onToggleHidden = { board ->
                            viewModel.setHidden(board.fid, board.fid !in state.hiddenFids)
                        },
                    )
                }
            }
        }
        item(key = "board_grid_hint") {
            Text(
                text = stringResource(R.string.forum_board_settings_hidden_hint),
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * The three-column management grid.
 *
 * It is a **single custom [Layout]**, not nested `Row`s (and not a `LazyVerticalGrid`): every tile is
 * a direct child, keyed by fid. That matters for dragging — when a tile crosses into another row it
 * only changes the slot it is placed in, so its composable (and therefore the in-flight long-press
 * drag gesture) survives. With one `Row` per grid row the tile would be disposed and re-created in
 * the next row's `Row`, and the drag would cancel at the first row crossing.
 */
@Composable
private fun BoardSettingsGrid(
    boards: List<Forum>,
    hiddenFids: Set<Int>,
    draggingFid: Int?,
    dragOffsetX: Float,
    dragOffsetY: Float,
    onMeasured: (Float, Float) -> Unit,
    onDragStart: (fid: Int, index: Int) -> Unit,
    onDragBy: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onMoveUp: (index: Int) -> Unit,
    onMoveDown: (index: Int) -> Unit,
    onToggleHidden: (Forum) -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            boards.forEachIndexed { index, board ->
                key(board.fid) {
                    val isDragged = board.fid == draggingFid
                    BoardSettingsTile(
                        board = board,
                        hidden = board.fid in hiddenFids,
                        position = index + 1,
                        total = boards.size,
                        dragging = isDragged,
                        dragOffsetX = if (isDragged) dragOffsetX else 0f,
                        dragOffsetY = if (isDragged) dragOffsetY else 0f,
                        onMeasured = onMeasured,
                        onDragStart = { onDragStart(board.fid, index) },
                        onDragBy = onDragBy,
                        onDragEnd = onDragEnd,
                        onMoveUp = { onMoveUp(index) },
                        onMoveDown = { onMoveDown(index) },
                        onToggleHidden = { onToggleHidden(board) },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val gap = BoardGridGap.roundToPx()
        val gridWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val tileWidth = ((gridWidth - gap * (BOARD_GRID_COLUMNS - 1)) / BOARD_GRID_COLUMNS)
            .coerceAtLeast(1)
        val tileHeight = BoardTileHeight.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(tileWidth, tileHeight)) }
        val rows = (placeables.size + BOARD_GRID_COLUMNS - 1) / BOARD_GRID_COLUMNS
        val gridHeight = if (rows == 0) 0 else rows * tileHeight + (rows - 1) * gap
        layout(gridWidth, gridHeight) {
            placeables.forEachIndexed { index, placeable ->
                val column = index % BOARD_GRID_COLUMNS
                val row = index / BOARD_GRID_COLUMNS
                placeable.place(
                    x = column * (tileWidth + gap),
                    y = row * (tileHeight + gap),
                )
            }
        }
    }
}

/**
 * One board tile: position, name and the three explicit controls (eye to hide/show, up/down to
 * move). Long-press anywhere on the tile starts the drag; the handle glyph in the middle of the
 * button row is the visual affordance for it.
 */
@Composable
private fun BoardSettingsTile(
    board: Forum,
    hidden: Boolean,
    position: Int,
    total: Int,
    dragging: Boolean,
    dragOffsetX: Float,
    dragOffsetY: Float,
    modifier: Modifier = Modifier,
    onMeasured: (Float, Float) -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    // The gesture detector survives recomposition; the callbacks are re-read so the drag always
    // starts from the tile's *current* index (the grid re-slots while the finger is down).
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    val positionLabel = stringResource(
        R.string.forum_board_settings_position,
        position,
        total,
    )
    val eyeIcon = if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility
    val eyeLabel = stringResource(
        if (hidden) R.string.forum_board_settings_shown else R.string.forum_board_settings_hide,
    )
    val eyeTint = if (hidden) {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        MiuixTheme.colorScheme.primary
    }

    Card(
        modifier = modifier
            .onGloballyPositioned { onMeasured(it.size.width.toFloat(), it.size.height.toFloat()) }
            .zIndex(if (dragging) 2f else 0f)
            .pointerInput(board.fid) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        currentOnDragBy(amount.x, amount.y)
                    },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                )
            }
            .graphicsLayer {
                translationX = dragOffsetX
                translationY = dragOffsetY
                if (dragging) {
                    scaleX = 1.04f
                    scaleY = 1.04f
                }
            },
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = positionLabel,
                    modifier = Modifier.weight(1f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TileIconButton(
                    icon = eyeIcon,
                    contentDescription = eyeLabel,
                    tint = eyeTint,
                    onClick = onToggleHidden,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = board.name,
                    modifier = Modifier.alpha(if (hidden) HIDDEN_TILE_ALPHA else 1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TileIconButton(
                    icon = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.forum_board_settings_move_up),
                    enabled = position > 1,
                    onClick = onMoveUp,
                )
                Icon(
                    imageVector = ImageVector.vectorResource(AppIcons.DragHandle),
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
                TileIconButton(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.forum_board_settings_move_down),
                    enabled = position < total,
                    onClick = onMoveDown,
                )
            }
        }
    }
}

/** A small icon-only tap target; the icon keeps its own content description for TalkBack. */
@Composable
private fun TileIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}

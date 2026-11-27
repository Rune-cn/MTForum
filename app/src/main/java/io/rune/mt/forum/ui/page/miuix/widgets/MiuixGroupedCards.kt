// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * A group of consecutive rows drawn as one continuous Miuix card.
 *
 * This is a port of the Miuix example's `component/GroupedCardItems.kt` (`CardSegment` +
 * `LazyListScope.groupedCardItems`), which is how the example builds every multi-row section
 * (`MainPage.kt`'s demo list, `SettingsPage.kt`'s preference groups): the first row keeps the
 * top corners, the last row the bottom corners, and the rows in between are square — one card,
 * not a stack of cards with gutters between them.
 *
 * `Card` itself only takes a single `cornerRadius`, so per-corner radii cannot be expressed with
 * it; the example solves that with `squircleSurface`, and this port keeps that call so the corner
 * geometry, squircle smoothing and theme surface color all still come from the library. The
 * opaque-seam bleed below is the example's own fix for the dark hairline that otherwise appears
 * between downscaled segments.
 */

/**
 * One row of a grouped card.
 *
 * @param isFirst Draw the top corners at the card radius.
 * @param isLast Draw the bottom corners at the card radius.
 * @param color Surface fill; the outer 12dp gutter is applied here so rows line up with the
 *   example's page margins.
 * @param cornerRadius Defaults to `CardDefaults.CornerRadius`, the library's own value.
 * @param outerTopPadding Space above the first row of the group.
 * @param outerBottomPadding Space below the last row of the group.
 */
@Composable
internal fun MiuixCardSegment(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surfaceContainer,
    contentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    topCornerRadius: Dp = if (isFirst) cornerRadius else 0.dp,
    bottomCornerRadius: Dp = if (isLast) cornerRadius else 0.dp,
    outerHorizontalPadding: Dp = 12.dp,
    outerTopPadding: Dp = 0.dp,
    outerBottomPadding: Dp = 0.dp,
    insidePadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val overlapBottom = !isLast && bottomCornerRadius == 0.dp && color.alpha == 1f
    val seamBleed = if (overlapBottom) {
        Modifier.drawBehind {
            // Extend the opaque bottom edge by 2px so 0.5x downscaled RenderNode rasterization
            // cannot anti-alias a black seam between two touching segments.
            val bleedTop = (size.height - 2f).coerceAtLeast(0f)
            drawRect(
                color = color,
                topLeft = Offset(0f, bleedTop),
                size = Size(size.width, size.height - bleedTop + 2f),
            )
        }
    } else {
        Modifier
    }
    val background = if (topCornerRadius == 0.dp && bottomCornerRadius == 0.dp) {
        // Square middle row: a plain fill, no shape work needed.
        Modifier.squircleSurface(color, 0.dp, 0.dp, 0.dp, 0.dp)
    } else {
        Modifier.squircleSurface(
            color,
            topCornerRadius,
            topCornerRadius,
            bottomCornerRadius,
            bottomCornerRadius,
        )
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    start = outerHorizontalPadding,
                    end = outerHorizontalPadding,
                    top = outerTopPadding,
                    bottom = outerBottomPadding,
                )
                .then(seamBleed)
                .then(background)
                .padding(insidePadding),
            content = content,
        )
    }
}

/**
 * Emit [items] as one grouped card: the first and last rows round the card, the rest are square.
 *
 * The caller still supplies the row content, so a page keeps its own row composable while the
 * group's corners and gutters come from here.
 *
 * @param keyPrefix Stable key prefix for the lazy items (one group per prefix).
 * @param outerTopPadding Space above the first segment.
 * @param outerBottomPadding Space below the last segment; the example's sections use 12dp.
 */
internal fun <T> LazyListScope.miuixGroupedItems(
    items: List<T>,
    keyPrefix: String,
    outerTopPadding: Dp = 0.dp,
    outerBottomPadding: Dp = 12.dp,
    outerHorizontalPadding: Dp = 12.dp,
    insidePadding: PaddingValues = PaddingValues(0.dp),
    contentType: Any = "card_segment",
    key: ((T) -> Any)? = null,
    segmentModifier: ((index: Int) -> Modifier)? = null,
    color: @Composable (Int) -> Color = { MiuixTheme.colorScheme.surfaceContainer },
    row: @Composable ColumnScope.(item: T, index: Int) -> Unit,
) {
    if (items.isEmpty()) return
    val lastIndex = items.lastIndex
    itemsIndexed(
        items = items,
        key = { index, item -> key?.invoke(item) ?: "$keyPrefix:$index" },
        contentType = { _, _ -> contentType },
    ) { index, item ->
        MiuixCardSegment(
            isFirst = index == 0,
            isLast = index == lastIndex,
            modifier = segmentModifier?.invoke(index) ?: Modifier,
            outerHorizontalPadding = outerHorizontalPadding,
            outerTopPadding = if (index == 0) outerTopPadding else 0.dp,
            outerBottomPadding = if (index == lastIndex) outerBottomPadding else 0.dp,
            insidePadding = insidePadding,
            color = color(index),
        ) {
            row(item, index)
        }
    }
}
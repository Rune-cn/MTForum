// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Minimum/maximum tab width that keeps every label of a Miuix `TabRow` intact.
 *
 * Miuix's `TabRow` lays the tabs out in a `LazyRow` but gives them **one** width:
 * `availableWidth / count` clamped to `minWidth..maxWidth` (`TabRow.kt`'s `calculateTabWidth`), and
 * `TabItem` draws its label with `maxLines = 1`, `softWrap = false` and `TextOverflow.Ellipsis`
 * inside 12dp horizontal padding. On a 360dp phone the 消息 page's four tabs therefore get ~77dp
 * each, i.e. ~53dp for the text, and "系统提醒" (4 CJK characters at `body1`'s 16sp ≈ 64dp) is
 * printed as "系统…" — the truncation the owner reported.
 *
 * Passing the widest label's measured width (plus the tab's own padding) as both `minWidth` and
 * `maxWidth` makes the tabs content-sized. When the four of them no longer fit, the row scrolls
 * sideways (it already is a `LazyRow`) instead of truncating a label; on a 411dp phone all four fit.
 *
 * The measurement mirrors `TabItem` exactly: `body1`'s font size, bold (the selected tab's weight,
 * i.e. the widest case), single line, zero letter spacing. [LABEL_SLACK] absorbs sub-pixel rounding
 * so a measurement difference can never turn into an ellipsis.
 */
@Composable
internal fun rememberTabRowLabelWidth(labels: List<String>): Dp {
    val textMeasurer = rememberTextMeasurer()
    val body1 = MiuixTheme.textStyles.body1
    val density = LocalDensity.current
    val style = remember(body1) {
        body1.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    }
    val widestPx = remember(labels, style, density) {
        labels.maxOfOrNull { label ->
            textMeasurer.measure(
                text = AnnotatedString(label),
                style = style,
                maxLines = 1,
                softWrap = false,
            ).size.width
        } ?: 0
    }
    return with(density) { widestPx.toDp() } + TAB_LABEL_HORIZONTAL_PADDING * 2 + LABEL_SLACK
}

/** `TabItem`'s own label padding: 12dp on each side (`TabRow.kt`). */
private val TAB_LABEL_HORIZONTAL_PADDING = 12.dp

/** Sub-pixel safety margin between our measured label and the tab's rendered label. */
private val LABEL_SLACK = 2.dp
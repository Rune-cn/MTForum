// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings.preferred.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixSwitchWidget

/**
 * The light/dark switch, presented the way the Miuix example app presents it.
 *
 * Source shape: `example/.../ThemeSettingsPage.kt` — a two-tile `selectableGroup()` of light/dark
 * previews (单选卡片：缩略图 + 标题，选中态用主色描边), and "Follow System" as a separate switch
 * row. The old control here was a `SpinnerPreference` row with a dropdown; the tiles make the
 * result of a tap visible without opening a menu, which is what the owner asked for.
 *
 * Only the presentation changed: the three modes are unchanged ([ThemeMode.LIGHT],
 * [ThemeMode.DARK], [ThemeMode.SYSTEM]) and both callbacks hand the mode back to the caller, which
 * persists it through the same `UpdateSettingUseCase` as before. Turning 跟随系统 off keeps the
 * current look (the example does the same): it resolves to whatever the app is rendering now.
 *
 * Tiles are `Card`s with `Role.RadioButton` + `selected` semantics so a tap reads as a choice, and
 * the illustration is drawn with the same gradient/line geometry as the example's
 * `ThemeModePreview`.
 */
@Composable
internal fun ThemeModePreviewRow(
    currentMode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeModePreview(
            title = stringResource(R.string.theme_settings_theme_mode_light),
            dark = false,
            isSelected = currentMode == ThemeMode.LIGHT,
            onClick = { onModeChange(ThemeMode.LIGHT) },
            modifier = Modifier.weight(1f),
        )
        ThemeModePreview(
            title = stringResource(R.string.theme_settings_theme_mode_dark),
            dark = true,
            isSelected = currentMode == ThemeMode.DARK,
            onClick = { onModeChange(ThemeMode.DARK) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** The 跟随系统 row; it is the third mode, so it is a switch rather than a third tile. */
@Composable
internal fun ThemeModeFollowSystemRow(
    currentMode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
) {
    val isFollowSystem = currentMode == ThemeMode.SYSTEM
    // Unchecking must not flip the user's palette: keep the look the app is rendering right now.
    val keepCurrentLook = if (AppTheme.isDark) ThemeMode.DARK else ThemeMode.LIGHT
    MiuixSwitchWidget(
        title = stringResource(R.string.theme_settings_theme_mode_system),
        checked = isFollowSystem,
        onCheckedChange = { followSystem ->
            onModeChange(if (followSystem) ThemeMode.SYSTEM else keepCurrentLook)
        },
    )
}

/**
 * One light/dark tile: a rounded preview card with a 26dp radius, a 3dp primary outline when
 * selected, and the label under it (the example's `ThemeModePreview`).
 */
@Composable
private fun ThemeModePreview(
    title: String,
    dark: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        cornerRadius = 26.dp,
        insideMargin = PaddingValues(vertical = 6.dp),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        onClick = onClick,
    ) {
        // The semantics sit on the content column rather than the card: `Card`'s own clickable
        // surface forms a separate merged node, and putting role/selected here keeps the label and
        // the selection state on one node (so a tap reads as "深色主题, selected, radio button").
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    role = Role.RadioButton
                    selected = isSelected
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleBorder(
                        width = 3.dp,
                        color = if (isSelected) MiuixTheme.colorScheme.primary else Color.Transparent,
                        cornerRadius = 26.dp,
                    )
                    .padding(6.dp),
            ) {
                ThemeModeIllustration(dark = dark)
            }
            Text(
                text = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The example's preview illustration: a blue vertical gradient with two rounded "screenshot" rows
 * of text lines, drawn proportionally so the tile keeps the same look at any width.
 */
@Composable
private fun ThemeModeIllustration(
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .squircleClip(20.dp)
            .drawWithCache {
                val bgBrush = Brush.verticalGradient(ThemeIllustrationGradientColors)
                val panelColor = if (dark) Color.Black.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.2f)
                val lineColor = if (dark) Color.White.copy(alpha = 0.35f) else Color(0xFF285ABC).copy(alpha = 0.6f)
                onDrawBehind {
                    drawRect(brush = bgBrush)
                    val unitX = size.width / 680f
                    val unitY = size.height / 680f
                    val cornerUnit = minOf(unitX, unitY)
                    val panelSize = Size(584f * unitX, 176f * unitY)
                    val panelCorner = CornerRadius(44f * cornerUnit)
                    val lineCorner = CornerRadius(12f * cornerUnit)
                    val lineX = 112f * unitX
                    val lineH = 24f * unitY
                    for (top in ThemeIllustrationRowTops) {
                        drawRoundRect(
                            color = panelColor,
                            topLeft = Offset(48f * unitX, top * unitY),
                            size = panelSize,
                            cornerRadius = panelCorner,
                        )
                        for (lineIndex in ThemeIllustrationLineOffsets.indices) {
                            drawRoundRect(
                                color = lineColor,
                                topLeft = Offset(lineX, (top + ThemeIllustrationLineOffsets[lineIndex]) * unitY),
                                size = Size(ThemeIllustrationLineWidths[lineIndex] * unitX, lineH),
                                cornerRadius = lineCorner,
                            )
                        }
                    }
                }
            },
    )
}

private val ThemeIllustrationGradientColors = listOf(Color(0xFF406EFF), Color(0xFF55B9FA))
private val ThemeIllustrationRowTops = floatArrayOf(148f, 356f)
private val ThemeIllustrationLineOffsets = floatArrayOf(52f, 100f)
private val ThemeIllustrationLineWidths = floatArrayOf(460f, 312f)
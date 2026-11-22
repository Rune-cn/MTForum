// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred.theme

import android.os.Build
import androidx.compose.ui.graphics.Color
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackAnimation
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackExitDirection
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.theme.material.PresetColors
import io.rune.mt.forum.ui.theme.material.RawColor

data class ThemeSettingsState(
    val showMiuixUI: Boolean = false,
    val useBlur: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val colorSpec: ThemeColorSpec = ThemeColorSpec.SPEC_2025,
    val useDynamicColor: Boolean = true,
    val useMiuixOs4: Boolean = false,
    val useMiuixMonet: Boolean = false,
    val useAppleFloatingBar: Boolean = false,
    val seedColor: Color = PresetColors[0].color,
    val availableColors: List<RawColor> = PresetColors,
    val showLiveActivity: Boolean = false,
    val predictiveBackAnimation: PredictiveBackAnimation = PredictiveBackAnimation.None,
    val predictiveBackExitDirection: PredictiveBackExitDirection = PredictiveBackExitDirection.FOLLOW_GESTURE,
)

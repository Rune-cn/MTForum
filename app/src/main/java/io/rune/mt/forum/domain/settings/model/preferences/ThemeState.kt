package io.rune.mt.forum.domain.settings.model.preferences

import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode

/**
 * A shared data class to hold the theme-related UI state.
 */
data class ThemeState(
    val isLoaded: Boolean = false,
    val useMiuix: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val colorSpec: ThemeColorSpec = ThemeColorSpec.SPEC_2025,
    // Dynamic colour would replace the brand seed with the wallpaper palette on day one, so
    // the MT green is the out-of-the-box look and dynamic remains opt-in in theme settings.
    val useDynamicColor: Boolean = false,
    val useMiuixOs4: Boolean = false,
    val useMiuixMonet: Boolean = false,
    val useAppleFloatingBar: Boolean = false,
    // Default seed matches the launcher icon's terminal green (#4ADE80), darkened to
    // #2E9E5B so white foreground text keeps contrast on the primary container.
    val seedColor: Int = 0xFF2E9E5B.toInt(),
    // Off by default: the blur path records a LayerBackdrop and samples it through a
    // RenderEffect, and on Android 16 that combination crashed the render thread with a
    // stack overflow inside hwui (computeTransformImpl recursing through the DirtyStack).
    // The visual effect is opt-in in theme settings until the miuix glass path is proven
    // stable on that platform.
    val useBlur: Boolean = false,
    val predictiveBackAnimation: PredictiveBackAnimation = PredictiveBackAnimation.MIUIX,
    val predictiveBackExitDirection: PredictiveBackExitDirection = PredictiveBackExitDirection.ALWAYS_RIGHT,
)

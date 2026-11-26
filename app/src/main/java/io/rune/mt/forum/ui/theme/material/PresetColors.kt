// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.theme.material

import androidx.compose.ui.graphics.Color

data class RawColor(val key: String, val color: Color)

/**
 * Manual seed swatches, in picker order.
 *
 * `default` is the app's brand green, not a neutral olive: it is the launcher icon's
 * speech-bubble highlight (#4ADE80) darkened to #2E9E5B so white foreground text keeps its
 * contrast. That is the same ARGB as `ThemeState.seedColor`, so a fresh install (whose stored
 * seed is the legacy olive, no longer listed here) falls back to this first entry and the
 * swatch the settings page marks selected is the icon-like green rather than the olive.
 */
val PresetColors = listOf(
    RawColor("default", Color(0xFF2E9E5B)),
    RawColor("pink", Color(0xFFB94073)),
    RawColor("red", Color(0xFFBA1A1A)),
    RawColor("orange", Color(0xFF944A00)),
    RawColor("amber", Color(0xFF8C5300)),
    RawColor("yellow", Color(0xFF795900)),
    RawColor("lime", Color(0xFF5E6400)),
    RawColor("green", Color(0xFF006D39)),
    RawColor("cyan", Color(0xFF006A64)),
    RawColor("teal", Color(0xFF006874)),
    RawColor("light_blue", Color(0xFF00639B)),
    RawColor("blue", Color(0xFF335BBC)),
    RawColor("indigo", Color(0xFF5355A9)),
    RawColor("purple", Color(0xFF6750A4)),
    RawColor("deep_purple", Color(0xFF7E42A4)),
    RawColor("blue_grey", Color(0xFF575D7E)),
    RawColor("brown", Color(0xFF7D524A)),
    RawColor("grey", Color(0xFF5F6162)),
)

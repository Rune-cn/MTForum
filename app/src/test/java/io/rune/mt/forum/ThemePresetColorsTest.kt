// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.compose.ui.graphics.toArgb
import io.rune.mt.forum.domain.settings.model.preferences.ThemeState
import io.rune.mt.forum.ui.theme.material.PresetColors
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The theme picker's `default` swatch must be the app's brand green.
 *
 * [PresetColors] drives both the settings swatch list and the seed resolution in
 * `ThemeSettingsViewModel` / `ThemeStateProviderImpl`: a stored seed that is not in this list
 * falls back to `PresetColors[0]`, so the first entry has to be the same colour a fresh install
 * resolves to. It previously was an olive (#4A672D), which is the legacy stored default and not
 * what the launcher icon looks like.
 */
class ThemePresetColorsTest {

    @Test
    fun `first swatch is the default brand green`() {
        val first = PresetColors.first()
        assertEquals("default", first.key, "the first swatch must stay the 'default' label key")
        assertEquals(0xFF2E9E5B.toInt(), first.color.toArgb())
        assertEquals(
            ThemeState().seedColor,
            first.color.toArgb(),
            "PresetColors[0] must match the ThemeState default seed byte for byte",
        )
    }

    @Test
    fun `a stored default seed selects the first swatch`() {
        val stored = ThemeState().seedColor
        val selected = PresetColors.firstOrNull { it.color.toArgb() == stored }
        assertEquals(PresetColors[0], selected, "the default seed must resolve to the first swatch")
    }

    @Test
    fun `legacy olive seed falls back to the brand green default`() {
        val legacyOlive = 0xFF4A672D.toInt()
        assertTrue(
            PresetColors.none { it.color.toArgb() == legacyOlive },
            "the legacy olive must be gone from the picker",
        )
        // Mirrors the resolution in ThemeSettingsViewModel / ThemeStateProviderImpl: an unknown
        // stored seed takes PresetColors[0], so an upgrade from the olive default lands on green.
        val selected = PresetColors.firstOrNull { it.color.toArgb() == legacyOlive } ?: PresetColors[0]
        assertEquals(0xFF2E9E5B.toInt(), selected.color.toArgb())
    }

    @Test
    fun `preset swatches are unique`() {
        val argbs = PresetColors.map { it.color.toArgb() }
        assertEquals(
            argbs.size,
            argbs.distinct().size,
            "duplicate swatch colours would make the picker's `==` selection ambiguous",
        )
    }
}
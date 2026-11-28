// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.settings.model.preferences

import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode

/**
 * The aggregated state of the shell application preferences.
 *
 * Only the settings the shell app actually exposes are kept (theme,
 * update check, logging, predictive back, UI family).
 */
data class AppPreferences(
    // Updater / Network
    val allowInternetAccess: Boolean,
    val githubUpdateChannel: GithubUpdateChannel,
    val customGithubProxyUrl: String,
    val autoCheckUpdateOnStart: Boolean,
    // Debug
    val enableFileLogging: Boolean,
    // Theme Settings
    val themeMode: ThemeMode,
    val paletteStyle: PaletteStyle,
    val colorSpec: ThemeColorSpec,
    val useDynamicColor: Boolean,
    val useMiuixOs4: Boolean = false,
    val useMiuixMonet: Boolean,
    val useAppleFloatingBar: Boolean,
    val seedColorInt: Int, // Stored as raw Int from DataStore
    val wallpaperSeedColorInt: Int,
    val useBlur: Boolean,
    val showMiuixUI: Boolean,
    // Predictive Back Settings
    val predictiveBackAnimation: PredictiveBackAnimation,
    val predictiveBackExitDirection: PredictiveBackExitDirection,
    // Local MCP server (设置 → MCP 服务). Off by default; the loopback socket only exists while
    // the switch is on, and the port is validated before binding.
    val mcpServerEnabled: Boolean = false,
    val mcpServerPort: Int = io.rune.mt.forum.domain.mcp.McpEndpoint.DEFAULT_PORT,
)

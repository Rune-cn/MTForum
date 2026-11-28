// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.settings.repository

import android.os.Build
import androidx.datastore.preferences.core.Preferences
import io.rune.mt.forum.data.settings.local.datastore.AppDataStore
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.mcp.McpEndpoint
import io.rune.mt.forum.domain.settings.model.preferences.AppPreferences
import io.rune.mt.forum.domain.settings.model.preferences.GithubUpdateChannel
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackAnimation
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackExitDirection
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.settings.repository.BooleanSetting
import io.rune.mt.forum.domain.settings.repository.IntSetting
import io.rune.mt.forum.domain.settings.repository.StringSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

class AppSettingsRepositoryImpl(
    private val appDataStore: AppDataStore,
    appScope: CoroutineScope,
) : AppSettingsRepository {

    // Replaced brittle combine(listOf(...)) with a safe mapping from the raw Preferences flow.
    override val preferencesFlow: Flow<AppPreferences> = appDataStore.data.map { prefs ->
        // Pre-compute values for Github update channel validation
        val rawGithubUpdateChannel = GithubUpdateChannel.fromValueOrDefault(
            prefs[AppDataStore.GITHUB_UPDATE_CHANNEL] ?: GithubUpdateChannel.OFFICIAL.name,
        )
        val customGithubProxyUrl = prefs[AppDataStore.CUSTOM_GITHUB_PROXY_URL] ?: ""
        val githubUpdateChannel = if (rawGithubUpdateChannel == GithubUpdateChannel.CUSTOM && customGithubProxyUrl.isBlank()) {
            GithubUpdateChannel.OFFICIAL
        } else {
            rawGithubUpdateChannel
        }

        // Map all preferences explicitly by key. Order no longer matters.
        AppPreferences(
            // Updater / Network
            allowInternetAccess = prefs[AppDataStore.ALLOW_INTERNET_ACCESS] ?: true,
            githubUpdateChannel = githubUpdateChannel,
            customGithubProxyUrl = customGithubProxyUrl,
            autoCheckUpdateOnStart = prefs[AppDataStore.AUTO_CHECK_UPDATE_ON_START] ?: false,
            // Debug
            enableFileLogging = prefs[AppDataStore.ENABLE_FILE_LOGGING] ?: true,
            // UI State
            showMiuixUI = prefs[AppDataStore.UI_USE_MIUIX] ?: true,
            themeMode = ThemeMode.fromValueOrDefault(prefs[AppDataStore.THEME_MODE] ?: ThemeMode.SYSTEM.name),
            paletteStyle = PaletteStyle.fromValueOrDefault(
                prefs[AppDataStore.THEME_PALETTE_STYLE] ?: PaletteStyle.TonalSpot.name,
            ),
            colorSpec = ThemeColorSpec.fromValueOrDefault(prefs[AppDataStore.THEME_COLOR_SPEC] ?: ThemeColorSpec.SPEC_2025.name),
            useDynamicColor = prefs[AppDataStore.THEME_USE_DYNAMIC_COLOR] ?: true,
            useMiuixOs4 = prefs[AppDataStore.UI_USE_MIUIX_OS4] ?: false,
            useMiuixMonet = prefs[AppDataStore.UI_USE_MIUIX_MONET] ?: false,
            useAppleFloatingBar = prefs[AppDataStore.UI_USE_APPLE_FLOATING_BAR] ?: false,
            seedColorInt = prefs[AppDataStore.THEME_SEED_COLOR] ?: DEFAULT_SEED_COLOR,
            wallpaperSeedColorInt = prefs[AppDataStore.THEME_WALLPAPER_SEED_COLOR] ?: 0,
            // Blur defaults to OFF. The blur path records a LayerBackdrop and samples it
            // through a RenderEffect; on Android 16 that crashed the render thread with a
            // stack overflow in hwui (computeTransformImpl recursing through the
            // DirtyStack). Users can opt in from theme settings once miuix glass is stable
            // there. CoreSettings.SafeMode can also force it off after a crash.
            useBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (prefs[AppDataStore.UI_USE_MIUIX_OS4] == true && prefs[AppDataStore.UI_USE_MIUIX] != false) ||
                    (prefs[AppDataStore.UI_USE_BLUR] ?: false)
            } else {
                false
            },
            predictiveBackAnimation = PredictiveBackAnimation.fromValueOrDefault(
                prefs[AppDataStore.PREDICTIVE_BACK_ANIMATION] ?: PredictiveBackAnimation.MIUIX.value,
            ),
            predictiveBackExitDirection = PredictiveBackExitDirection.fromValueOrDefault(
                prefs[AppDataStore.PREDICTIVE_BACK_EXIT_DIRECTION] ?: PredictiveBackExitDirection.ALWAYS_RIGHT.value,
            ),
            mcpServerEnabled = prefs[AppDataStore.MCP_SERVER_ENABLED] ?: false,
            // A stale/hand-edited port never reaches a bind: fall back to the default.
            mcpServerPort = McpEndpoint.normalizePort(
                prefs[AppDataStore.MCP_SERVER_PORT] ?: McpEndpoint.DEFAULT_PORT,
            ),
        )
    }.shareIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        replay = 1,
    )

    override suspend fun putString(setting: StringSetting, value: String) = appDataStore.putString(stringKey(setting), value)

    override fun getString(setting: StringSetting, default: String): Flow<String> = appDataStore.getString(stringKey(setting), default)

    override suspend fun putInt(setting: IntSetting, value: Int) = appDataStore.putInt(intKey(setting), value)

    override fun getInt(setting: IntSetting, default: Int): Flow<Int> = appDataStore.getInt(intKey(setting), default)

    override suspend fun putBoolean(setting: BooleanSetting, value: Boolean) = appDataStore.putBoolean(booleanKey(setting), value)

    override fun getBoolean(setting: BooleanSetting, default: Boolean): Flow<Boolean> = appDataStore.getBoolean(booleanKey(setting), default)

    private fun stringKey(setting: StringSetting): Preferences.Key<String> = when (setting) {
        StringSetting.ThemeMode -> AppDataStore.THEME_MODE
        StringSetting.ThemePaletteStyle -> AppDataStore.THEME_PALETTE_STYLE
        StringSetting.ThemeColorSpec -> AppDataStore.THEME_COLOR_SPEC
        StringSetting.PredictiveBackAnimation -> AppDataStore.PREDICTIVE_BACK_ANIMATION
        StringSetting.PredictiveBackExitDirection -> AppDataStore.PREDICTIVE_BACK_EXIT_DIRECTION
        StringSetting.GithubUpdateChannel -> AppDataStore.GITHUB_UPDATE_CHANNEL
        StringSetting.CustomGithubProxyUrl -> AppDataStore.CUSTOM_GITHUB_PROXY_URL
    }

    private fun intKey(setting: IntSetting): Preferences.Key<Int> = when (setting) {
        IntSetting.ThemeSeedColor -> AppDataStore.THEME_SEED_COLOR
        IntSetting.ThemeWallpaperSeedColor -> AppDataStore.THEME_WALLPAPER_SEED_COLOR
        IntSetting.McpServerPort -> AppDataStore.MCP_SERVER_PORT
    }

    private fun booleanKey(setting: BooleanSetting): Preferences.Key<Boolean> = when (setting) {
        BooleanSetting.AllowInternetAccess -> AppDataStore.ALLOW_INTERNET_ACCESS
        BooleanSetting.UiUseBlur -> AppDataStore.UI_USE_BLUR
        BooleanSetting.AutoCheckUpdateOnStart -> AppDataStore.AUTO_CHECK_UPDATE_ON_START
        BooleanSetting.ThemeUseDynamicColor -> AppDataStore.THEME_USE_DYNAMIC_COLOR
        BooleanSetting.UiUseMiuix -> AppDataStore.UI_USE_MIUIX
        BooleanSetting.UiUseMiuixOs4 -> AppDataStore.UI_USE_MIUIX_OS4
        BooleanSetting.UiUseMiuixMonet -> AppDataStore.UI_USE_MIUIX_MONET
        BooleanSetting.UiUseAppleFloatingBar -> AppDataStore.UI_USE_APPLE_FLOATING_BAR
        BooleanSetting.EnableFileLogging -> AppDataStore.ENABLE_FILE_LOGGING
        BooleanSetting.McpServerEnabled -> AppDataStore.MCP_SERVER_ENABLED
    }

    private companion object {
        private const val DEFAULT_SEED_COLOR = 0xFF4A672D.toInt()
    }
}

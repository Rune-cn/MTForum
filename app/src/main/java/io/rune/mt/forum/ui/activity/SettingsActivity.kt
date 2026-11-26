// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.activity

import io.rune.mt.forum.core.i18n.LocaleManager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.settings.model.preferences.ThemeState
import io.rune.mt.forum.domain.settings.provider.ThemeStateProvider
import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.updater.repository.UpdateRepository
import kotlinx.coroutines.flow.first
import io.rune.mt.forum.ui.navigation.AppNavContainer
import io.rune.mt.forum.ui.navigation.ForumDeepLinkState
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixComponentHost
import io.rune.mt.forum.ui.theme.AppTheme
import io.rune.mt.forum.ui.theme.LocalWindowLayoutInfo
import io.rune.mt.forum.ui.theme.SystemBarAppearance
import io.rune.mt.forum.ui.theme.applyWindowAppearance
import io.rune.mt.forum.ui.theme.rememberWindowLayoutInfo
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import io.rune.mt.forum.ui.theme.isOs4PlatformSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsActivity :
    ComponentActivity(),
    KoinComponent {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    private val themeStateProvider by inject<ThemeStateProvider>()

    private val updateRepo by inject<UpdateRepository>()
    private val appSettingsRepo by inject<AppSettingsRepository>()

    /**
     * Deep links arrive as ACTION_VIEW intents on this, the app's only activity. The state holder
     * decides cold-start vs. recreate and the nav shell consumes it exactly once.
     */
    internal val deepLinkState = ForumDeepLinkState()

    // legado-style: the startup update check runs at most once per process.
    private var startupUpdateChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1) 只有冷启动才装 splash；recreate（切语言）不重装，
        //    避免又用 windowSplashScreenBackground（浅色）遮一遍。
        //    （installSplashScreen 必须在 super.onCreate 之前）
        val splashScreen = if (savedInstanceState == null) installSplashScreen() else null

        var isThemeLoaded = savedInstanceState != null
        splashScreen?.setKeepOnScreenCondition { !isThemeLoaded }

        super.onCreate(savedInstanceState)

        Timber.i("SettingsActivity created (cold start=%s)", savedInstanceState == null)

        // 浏览器/分享来的论坛链接：冷启动时转成一次导航。recreate（切语言/进程恢复）
        // 不重放，否则会把用户从恢复出来的返回栈里拽走。
        deepLinkState.onColdStart(intent, savedInstanceState)

        // 2) 用“上次解析好的”外观把窗口定住：这两步都在 Compose 之前，
        //    recreate() 之后状态栏不会再变白/变浅。
        //    （applyWindowAppearance 内部会调用 enableEdgeToEdge，所以不能再有无参 enableEdgeToEdge()）
        applyWindowAppearance(
            isDark = SystemBarAppearance.isDark(this),
            surfaceArgb = SystemBarAppearance.surfaceArgb(this),
        )

        autoCheckUpdateOnStart()

        // 首帧前就能拿到的兜底底色（同步读，不依赖 DataStore）
        val cachedSurface = Color(SystemBarAppearance.surfaceArgb(this))

        setContent {
            val uiState by themeStateProvider.themeStateFlow.collectAsStateWithLifecycle(initialValue = ThemeState())
            isThemeLoaded = uiState.isLoaded

            if (!uiState.isLoaded) {
                // 状态还没到：绝不能让窗口露出 Theme.Material.Light 的白底
                Box(Modifier.fillMaxSize().background(cachedSurface))
                return@setContent
            }

            val layoutInfo = rememberWindowLayoutInfo()
            // OS4 is a glass-only presentation; without render effects it degrades to OS3 so
            // every layer (theme, host, top bars, navigation bar) agrees on one style.
            val os4Supported = isOs4PlatformSupported

            CompositionLocalProvider(
                LocalWindowLayoutInfo provides layoutInfo,
            ) {
                AppTheme(
                    useMiuix = uiState.useMiuix,
                    themeMode = uiState.themeMode,
                    paletteStyle = uiState.paletteStyle,
                    colorSpec = uiState.colorSpec,
                    useDynamicColor = uiState.useDynamicColor,
                    useMiuixMonet = uiState.useMiuixMonet,
                    seedColor = androidx.compose.ui.graphics.Color(uiState.seedColor),
                    useMiuixOs4 = os4Supported && uiState.useMiuixOs4,
                ) {
                    // The host is always present, even for the OS3 path, because its Scaffold
                    // composes content in a SubcomposeLayout. Making it conditional forces the
                    // navigation tree out of a disposed subcomposition in the same frame and
                    // crashes with "Could not resolve state for movable content".
                    MiuixComponentHost(
                        useOs4 = os4Supported && uiState.useMiuixOs4,
                        useBlur = uiState.useBlur,
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                    ) { scope ->
                        val backgroundColor = MiuixTheme.colorScheme.surface
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor)
                                .then(scope.pageBackdropModifier),
                        ) {
                            AppNavContainer(
                                uiState = uiState,
                                deepLink = deepLinkState.route,
                                onDeepLinkConsumed = deepLinkState::consume,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * legado-style: check for updates only once per process start,
     * and only when the setting is enabled.
     */
    private fun autoCheckUpdateOnStart() {
        if (startupUpdateChecked) return
        startupUpdateChecked = true
        lifecycleScope.launch {
            val enabled = appSettingsRepo.preferencesFlow.first().autoCheckUpdateOnStart
            Timber.i("Startup update check: autoCheckUpdateOnStart=%s", enabled)
            if (enabled) {
                updateRepo.checkUpdate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.i("SettingsActivity resumed")
    }

    /**
     * A forum link tapped while the app is already running. `singleTop` (manifest) keeps this
     * instance instead of stacking a second one, so the link opens on top of the current shell.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() in sync with the newest link, otherwise a later recreate would parse
        // the stale intent.
        setIntent(intent)
        deepLinkState.onNewIntent(intent)
    }
}

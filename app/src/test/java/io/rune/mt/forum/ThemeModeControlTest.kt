// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.rune.mt.forum.domain.settings.usecase.settings.UpdateSettingUseCase
import io.rune.mt.forum.ui.page.main.settings.preferred.theme.ThemeSettingsAction
import io.rune.mt.forum.ui.page.main.settings.preferred.theme.ThemeSettingsViewModel
import io.rune.mt.forum.ui.page.miuix.settings.preferred.theme.ThemeModeFollowSystemRow
import io.rune.mt.forum.ui.page.miuix.settings.preferred.theme.ThemeModePreviewRow
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.compose.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The theme mode control now looks like the Miuix example app's: two light/dark preview tiles plus
 * the 跟随系统 switch row, instead of the old row + dropdown.
 *
 * Three behaviours matter, and all three are asserted here rather than described in prose:
 *  - both tiles and the switch exist (the control's shape);
 *  - a tile tap is a radio selection (semantics `Selected`) and every mode is reachable;
 *  - the choice goes through the same `ThemeSettingsViewModel` → `UpdateSettingUseCase` → repository
 *    path as before, so persistence is unchanged (fake repository records the write).
 *
 * The locale qualifier is `zh-rCN`, matching the strings the owner sees in the app; the screenshot
 * is therefore directly comparable with the device UI. Card corners use miuix squircle, which
 * Robolectric's Skia cannot compile, so the harness opts out for the capture only (same as
 * `MiuixGroupedCardsTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeModeControlTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private val repository = FakeThemeSettingsRepository()

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                module {
                    single<AppSettingsRepository> { repository }
                    single { UpdateSettingUseCase(get()) }
                    viewModelOf(::ThemeSettingsViewModel)
                },
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `control exposes both tiles and the follow-system switch`() {
        composeControl()

        compose.onNodeWithText("浅色主题").assertExists()
        compose.onNodeWithText("深色主题").assertExists()
        compose.onNodeWithText("跟随系统主题").assertExists()
    }

    @Test
    fun `dark tile is a radio selection and persists DARK`() {
        composeControl()

        compose.onNodeWithText("深色主题").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("深色主题")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("浅色主题")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        assertEquals(
            "the tile must persist through the same repository write as the old dropdown",
            "DARK",
            repository.storedStrings[StringSetting.ThemeMode],
        )
    }

    @Test
    fun `light tile persists LIGHT and deselects dark`() {
        composeControl()
        compose.onNodeWithText("深色主题").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("浅色主题").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("浅色主题")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        assertEquals("LIGHT", repository.storedStrings[StringSetting.ThemeMode])
    }

    @Test
    fun `follow-system switch covers the third mode and keeps the current look when turned off`() {
        // Start from the system mode so the switch starts checked; turning it off resolves to the
        // look the app is rendering (LIGHT here), like the example app.
        repository.preferences.value = themePreferences(ThemeMode.SYSTEM)
        composeControl()

        compose.onNodeWithText("跟随系统主题").performClick()
        compose.waitForIdle()
        assertEquals("LIGHT", repository.storedStrings[StringSetting.ThemeMode])

        compose.onNodeWithText("跟随系统主题").performClick()
        compose.waitForIdle()
        assertEquals("SYSTEM", repository.storedStrings[StringSetting.ThemeMode])
    }

    /**
     * The settings page must use the new control, not keep the old dropdown around.
     *
     * The page's rows live in a `LazyListScope` that needs the whole Koin graph plus the Miuix
     * chrome, so this is the same static contract check `SettingsAccountEntryTest` uses.
     */
    @Test
    fun `settings page uses the example-style control`() {
        val page = File(
            "src/main/java/io/rune/mt/forum/ui/page/miuix/settings/preferred/theme/" +
                "MiuixThemeSettingsPage.kt",
        )
            .also { assertTrue("theme settings page source not found at ${it.absolutePath}", it.isFile) }
            .readText()

        assertTrue(
            "the theme page must render the preview tiles",
            page.contains("ThemeModePreviewRow("),
        )
        assertTrue(
            "the theme page must render the follow-system switch",
            page.contains("ThemeModeFollowSystemRow("),
        )
        assertFalse(
            "the old row + dropdown control must be gone",
            page.contains("MiuixThemeModeWidget"),
        )
    }

    @Test
    fun `capture the theme mode control`() {
        composeControl()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, "theme-mode-control.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SCREENSHOT: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure {
            println("SCREENSHOT skipped: ${it::class.java.name}: ${it.message}")
        }
    }

    private fun composeControl() {
        compose.setContent {
            CompositionLocalProvider(LocalSquircleEnabled provides false) {
                AppTheme(
                    useMiuix = true,
                    themeMode = ThemeMode.LIGHT,
                    paletteStyle = PaletteStyle.TonalSpot,
                    colorSpec = ThemeColorSpec.SPEC_2021,
                    useDynamicColor = false,
                    useMiuixMonet = false,
                    seedColor = Color(0xFF2E9E5B),
                    useMiuixOs4 = false,
                ) {
                    val viewModel: ThemeSettingsViewModel = koinViewModel()
                    val uiState by viewModel.state.collectAsStateWithLifecycle()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.surface),
                    ) {
                        ThemeModePreviewRow(
                            currentMode = uiState.themeMode,
                            onModeChange = {
                                viewModel.dispatch(ThemeSettingsAction.SetThemeMode(it))
                            },
                        )
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            ThemeModeFollowSystemRow(
                                currentMode = uiState.themeMode,
                                onModeChange = {
                                    viewModel.dispatch(ThemeSettingsAction.SetThemeMode(it))
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}

/** App preferences with the fields the theme VM reads; everything else is a harmless default. */
private fun themePreferences(mode: ThemeMode) = AppPreferences(
    allowInternetAccess = true,
    githubUpdateChannel = GithubUpdateChannel.OFFICIAL,
    customGithubProxyUrl = "",
    autoCheckUpdateOnStart = false,
    enableFileLogging = false,
    themeMode = mode,
    paletteStyle = PaletteStyle.TonalSpot,
    colorSpec = ThemeColorSpec.SPEC_2021,
    useDynamicColor = false,
    useMiuixOs4 = false,
    useMiuixMonet = false,
    useAppleFloatingBar = false,
    seedColorInt = 0xFF2E9E5B.toInt(),
    wallpaperSeedColorInt = 0,
    useBlur = false,
    showMiuixUI = true,
    predictiveBackAnimation = PredictiveBackAnimation.None,
    predictiveBackExitDirection = PredictiveBackExitDirection.FOLLOW_GESTURE,
)

/** Records every write and reflects theme-mode writes back through `preferencesFlow`. */
private class FakeThemeSettingsRepository : AppSettingsRepository {

    val preferences = MutableStateFlow(themePreferences(ThemeMode.LIGHT))
    val storedStrings = mutableMapOf<StringSetting, String>()

    override val preferencesFlow: Flow<AppPreferences> = preferences

    override suspend fun putString(setting: StringSetting, value: String) {
        storedStrings[setting] = value
        if (setting == StringSetting.ThemeMode) {
            preferences.value = preferences.value.copy(themeMode = ThemeMode.fromValueOrDefault(value))
        }
    }

    override fun getString(setting: StringSetting, default: String): Flow<String> =
        flowOf(storedStrings[setting] ?: default)

    override suspend fun putInt(setting: IntSetting, value: Int) = Unit

    override fun getInt(setting: IntSetting, default: Int): Flow<Int> = flowOf(default)

    override suspend fun putBoolean(setting: BooleanSetting, value: Boolean) = Unit

    override fun getBoolean(setting: BooleanSetting, default: Boolean): Flow<Boolean> = flowOf(default)
}
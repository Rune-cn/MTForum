// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.rune.mt.forum.domain.mcp.McpEndpoint
import io.rune.mt.forum.domain.mcp.McpServerController
import io.rune.mt.forum.domain.mcp.McpServerState
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
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Navigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.main.settings.preferred.mcp.McpSettingsViewModel
import io.rune.mt.forum.ui.page.miuix.settings.preferred.mcp.MiuixMcpSettingsPage
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
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
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * The 设置 → MCP 服务 page must show the loopback endpoint, the switch and the read-only tool list.
 *
 * Locale is the default (English) because Robolectric resolves English strings, matching the
 * rest of the settings UI tests. The fake repository reflects writes back through
 * `preferencesFlow`, so the toggle/port assertions prove the real ViewModel → use case → store
 * path, not just a local `remember`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class McpSettingsPageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = FakeMcpSettingsRepository()
    private val controller = FakeMcpServerController()

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                module {
                    single<AppSettingsRepository> { repository }
                    single<McpServerController> { controller }
                    single { UpdateSettingUseCase(get()) }
                    viewModelOf(::McpSettingsViewModel)
                },
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `page shows the endpoint, the switch and the exposed tools`() {
        render()

        compose.onNodeWithText("Enable MCP server").assertExists()
        compose.onNodeWithText("http://127.0.0.1:${McpEndpoint.DEFAULT_PORT}/mcp").assertExists()
        compose.onNodeWithText("get_thread").assertExists()
        compose.onNodeWithText("list_boards").assertExists()
        // Security posture is visible, not just documented.
        compose.onNodeWithText("Reachable from this device only (127.0.0.1)").assertExists()
        compose.onNodeWithText("Read-only: no posting, private messages, login or setting changes").assertExists()
    }

    @Test
    fun `turning the switch on persists the toggle`() {
        render()

        compose.onNodeWithText("Enable MCP server").performClick()
        compose.waitForIdle()

        assertTrue(repository.preferences.value.mcpServerEnabled)
    }

    @Test
    fun `editing the port persists the new port`() {
        render()

        compose.onNode(hasSetTextAction()).performTextReplacement("9123")
        compose.waitForIdle()

        assertEquals(9123, repository.preferences.value.mcpServerPort)
    }

    @Test
    fun `a live server renders its bound port and the stopped state is distinct`() {
        controller.state.value = McpServerState(running = false)
        render()
        compose.onNodeWithText("Stopped").assertExists()

        controller.state.value = McpServerState(running = true, port = McpEndpoint.DEFAULT_PORT)
        compose.waitForIdle()
        compose.onNodeWithText("Running on 127.0.0.1:${McpEndpoint.DEFAULT_PORT}").assertExists()
    }

    /**
     * The entry row itself lives in the settings page's `LazyListScope` (which needs the whole
     * Koin graph); like `SettingsAccountEntryTest`, a source contract keeps the 设置 entry from
     * silently disappearing while the sub-page tests still cover what it opens.
     */
    @Test
    fun `settings page keeps the MCP entry row`() {
        val page = File(
            "src/main/java/io/rune/mt/forum/ui/page/miuix/settings/preferred/MiuixPreferredPage.kt",
        ).also { assertTrue("settings page source not found at ${it.absolutePath}", it.isFile) }
            .readText()

        assertTrue("the settings entry must navigate to Route.Mcp", page.contains("Route.Mcp"))
        assertTrue(
            "the settings entry must use the MCP title string",
            page.contains("R.string.mcp_server_title"),
        )
    }

    private fun render() {
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
                    val backStack = rememberNavBackStack<Route>(Route.Main)
                    val navigator = Navigator(backStack)
                    CompositionLocalProvider(LocalNavigator provides navigator) {
                        val viewModel: McpSettingsViewModel = koinViewModel()
                        MiuixMcpSettingsPage(enableBlur = false, viewModel = viewModel)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}

/** App preferences focused on the MCP fields; everything else is a harmless default. */
private fun mcpPreferences() = AppPreferences(
    allowInternetAccess = true,
    githubUpdateChannel = GithubUpdateChannel.OFFICIAL,
    customGithubProxyUrl = "",
    autoCheckUpdateOnStart = false,
    enableFileLogging = false,
    themeMode = ThemeMode.SYSTEM,
    paletteStyle = PaletteStyle.TonalSpot,
    colorSpec = ThemeColorSpec.SPEC_2025,
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
    mcpServerEnabled = false,
    mcpServerPort = McpEndpoint.DEFAULT_PORT,
)

/** Reflects switch/port writes back through `preferencesFlow` like the real DataStore does. */
private class FakeMcpSettingsRepository : AppSettingsRepository {

    val preferences = MutableStateFlow(mcpPreferences())

    override val preferencesFlow: Flow<AppPreferences> = preferences

    override suspend fun putString(setting: StringSetting, value: String) = Unit

    override fun getString(setting: StringSetting, default: String): Flow<String> = flowOf(default)

    override suspend fun putInt(setting: IntSetting, value: Int) {
        if (setting == IntSetting.McpServerPort) {
            preferences.value = preferences.value.copy(mcpServerPort = McpEndpoint.normalizePort(value))
        }
    }

    override fun getInt(setting: IntSetting, default: Int): Flow<Int> = flowOf(default)

    override suspend fun putBoolean(setting: BooleanSetting, value: Boolean) {
        if (setting == BooleanSetting.McpServerEnabled) {
            preferences.value = preferences.value.copy(mcpServerEnabled = value)
        }
    }

    override fun getBoolean(setting: BooleanSetting, default: Boolean): Flow<Boolean> = flowOf(default)
}

/** The page must not bind a socket; the controller is a state holder in this test. */
private class FakeMcpServerController : McpServerController {

    override val state = MutableStateFlow(McpServerState())

    override fun start() = Unit
}
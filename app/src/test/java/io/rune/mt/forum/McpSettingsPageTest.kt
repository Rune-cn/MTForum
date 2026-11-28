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
     * The MCP page is now reached from 设置 → 插件 → MCP 服务.
     *
     * The 其他 row was removed on request together with 浏览历史 / 评论屏蔽 / 板块管理: they are plugin
     * cards now. The contract moves with the entry instead of disappearing — the settings page
     * points at the plugin centre, the built-in catalogue still lists the MCP card, and the
     * page-id table still resolves it to [Route.Mcp]. A source scan (not a compose) for the same
     * reason as before: `MiuixPreferredPage` is an inline `LazyListScope` that needs the whole
     * Koin graph.
     */
    @Test
    fun `mcp entry lives in the plugin centre`() {
        fun source(relative: String): String =
            File("src/main/java/io/rune/mt/forum/$relative")
                .also { assertTrue("source not found at ${it.absolutePath}", it.isFile) }
                .readText()

        val settings = source("ui/page/miuix/settings/preferred/MiuixPreferredPage.kt")
        assertTrue(
            "the settings page must link to the plugin centre",
            settings.contains("Route.Plugins"),
        )
        assertTrue(
            "the MCP row must be gone from the settings page (it is a plugin card now)",
            !settings.contains("Route.Mcp"),
        )

        val builtins = source("data/plugin/BuiltinPlugins.kt")
        assertTrue(
            "the built-in catalogue must keep the MCP card",
            builtins.contains("const val MCP"),
        )
        assertTrue(
            "the MCP card must name its built-in page id",
            builtins.contains("builtin = MCP"),
        )

        val routes = source("ui/navigation/Routes.kt")
        assertTrue(
            "the mcp page id must still resolve to Route.Mcp",
            routes.contains("\"mcp\" -> Route.Mcp"),
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
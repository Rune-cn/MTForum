// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import io.rune.mt.forum.domain.forum.repository.ForumSearchHistoryRepository
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.forum.ForumSearchHistoryViewModel
import io.rune.mt.forum.ui.page.forum.ForumSearchOverlay
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * The home magnifier's search surface (OS3 path): it carries the typed keyword into
 * `Route.ForumSearch`, submits the IME search key, shows the persisted recent searches as one-tap
 * repeats, clears them, and dismisses on system Back.
 *
 * Only the OS3 presentation is composed: the OS4 branch is `GlassSearchOverlay`, whose AGSL shader
 * cannot be compiled by Robolectric's Skia (same limitation recorded in `ThreadActionsPopupLayoutTest`
 * and HANDOVER §15.9). The OS4 file still gets a screenshot here — the surface falls back to the
 * classic path because `AppTheme.useMiuixOs4 = false` is what the OS3 capture asks for.
 *
 * Robolectric resolves `stringResource` against `values/` (English), so the labels asserted below
 * are the English ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumSearchOverlayTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private val history = FakeSearchHistoryRepository()

    /** Set by the surface's callbacks so the assertions talk about real wiring. */
    private var submitted: String? = null
    private var dismissed = false

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                module {
                    single<ForumSearchHistoryRepository> { history }
                    viewModelOf(::ForumSearchHistoryViewModel)
                },
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `recent searches render and one tap submits them`() {
        history.entries.value = listOf("MT管理器", "逆向工坊")
        composeOverlay()
        compose.waitForIdle()

        compose.onNodeWithText("Recent searches").assertExists()
        compose.onNodeWithText("MT管理器").assertExists()

        compose.onNodeWithText("逆向工坊").performClick()
        compose.waitForIdle()

        assertEquals("a recent row must search its own keyword", "逆向工坊", submitted)
        assertTrue(
            "the tapped keyword becomes the newest history entry",
            runBlocking { history.recent.first() }.first() == "逆向工坊",
        )
    }

    @Test
    fun `typed keyword is carried to the results page`() {
        composeOverlay()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("mt管理器")
        compose.waitForIdle()

        // The confirm row is rendered from the live query, so its label proves the keyword the
        // route will carry: the overlay must never submit an empty string.
        compose.onNodeWithText("Search for “mt管理器”").performClick()
        compose.waitForIdle()

        assertEquals("mt管理器", submitted)
        assertTrue(
            "the submitted keyword is recorded for the next visit",
            runBlocking { history.recent.first() }.contains("mt管理器"),
        )
    }

    @Test
    fun `IME search action submits the typed keyword`() {
        composeOverlay()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("签到")
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        assertEquals("签到", submitted)
    }

    @Test
    fun `system back dismisses the surface`() {
        composeOverlay()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("不会被提交")
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()

        assertTrue("Back closes the search surface", dismissed)
        assertEquals("Back must not run a search", null, submitted)
    }

    @Test
    fun `clearing the history hides the recent section`() {
        history.entries.value = listOf("MT管理器", "逆向工坊")
        composeOverlay()
        compose.waitForIdle()

        compose.onNodeWithText("Clear").performClick()
        compose.waitForIdle()

        assertTrue(runBlocking { history.recent.first() }.isEmpty())
        compose.onNodeWithText("Recent searches").assertDoesNotExist()
        compose.onNodeWithText("MT管理器").assertDoesNotExist()
    }

    @Test
    fun `capture the os3 search surface`() {
        history.entries.value = listOf("MT管理器", "逆向工坊", "深链")
        composeOverlay()
        compose.waitForIdle()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, "search-overlay-os3.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SCREENSHOT: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure {
            println("SCREENSHOT skipped: ${it::class.java.name}: ${it.message}")
        }
    }

    private fun composeOverlay() {
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        ForumSearchOverlay(
                            expanded = true,
                            enableBlur = false,
                            onDismissRequest = { dismissed = true },
                            onSubmit = { submitted = it },
                        )
                    }
                }
            }
        }
        // The OS3 surface fades/scales in; let the enter transition settle before asserting.
        compose.mainClock.advanceTimeBy(500)
    }
}

/** In-memory [ForumSearchHistoryRepository]: the surface never talks to DataStore directly. */
private class FakeSearchHistoryRepository : ForumSearchHistoryRepository {
    val entries = MutableStateFlow<List<String>>(emptyList())

    override val recent: Flow<List<String>> = entries

    override suspend fun record(keyword: String) {
        val entry = keyword.trim()
        if (entry.isEmpty()) return
        entries.value = (listOf(entry) + entries.value.filterNot { it.equals(entry, ignoreCase = true) })
            .take(io.rune.mt.forum.domain.forum.repository.FORUM_SEARCH_HISTORY_LIMIT)
    }

    override suspend fun clear() {
        entries.value = emptyList()
    }
}
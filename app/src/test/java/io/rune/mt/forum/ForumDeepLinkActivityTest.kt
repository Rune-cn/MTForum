// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.rune.mt.forum.domain.settings.model.preferences.AppPreferences
import io.rune.mt.forum.domain.settings.model.preferences.ThemeState
import io.rune.mt.forum.domain.settings.provider.ThemeStateProvider
import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.settings.repository.BooleanSetting
import io.rune.mt.forum.domain.settings.repository.IntSetting
import io.rune.mt.forum.domain.settings.repository.StringSetting
import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.UpdateCheckState
import io.rune.mt.forum.domain.updater.model.UpdateInfo
import io.rune.mt.forum.domain.updater.repository.UpdateRepository
import io.rune.mt.forum.ui.activity.SettingsActivity
import io.rune.mt.forum.ui.navigation.Route
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Intent -> routing at the real [SettingsActivity] boundary.
 *
 * Robolectric cannot run `App.onCreate`, so Koin is started here with the three dependencies the
 * activity touches before the first frame (theme provider, settings and updater). The activity is
 * only *created* — never resumed/visible — so Compose does not start the navigation tree, no
 * network request is issued, and the assertion is exactly the hand-off contract: the pending
 * deep-link route the nav shell will consume on its first composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class ForumDeepLinkActivityTest {

    private val themeState = MutableStateFlow(ThemeState(isLoaded = true))

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                module {
                    single<ThemeStateProvider> {
                        object : ThemeStateProvider {
                            override val themeStateFlow: StateFlow<ThemeState> = themeState
                        }
                    }
                    single<AppSettingsRepository> { FakeAppSettingsRepository() }
                    single<UpdateRepository> { FakeUpdateRepository() }
                },
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `cold start thread link reaches the thread route`() {
        val controller = launch("https://bbs.binmt.cc/thread-171354-1-1.html")

        assertEquals(Route.ForumThread(171354, ""), controller.get().deepLinkState.route.value)
        controller.destroy()
    }

    @Test
    fun `cold start board link reaches the board route`() {
        val controller = launch("https://bbs.binmt.cc/forum-41-1.html")

        assertEquals(Route.ForumList(41, ""), controller.get().deepLinkState.route.value)
        controller.destroy()
    }

    @Test
    fun `cold start query form link reaches the thread route`() {
        val controller = launch("https://bbs.binmt.cc/forum.php?mod=viewthread&tid=171354")

        assertEquals(Route.ForumThread(171354, ""), controller.get().deepLinkState.route.value)
        controller.destroy()
    }

    @Test
    fun `recreated activity does not replay the launch intent`() {
        val controller = Robolectric
            .buildActivity(SettingsActivity::class.java, viewIntent("https://bbs.binmt.cc/thread-171354-1-1.html"))
            .create(Bundle())

        assertNull(controller.get().deepLinkState.route.value)
        controller.destroy()
    }

    @Test
    fun `link received while running becomes a pending route`() {
        val controller = launch("https://bbs.binmt.cc/thread-1-1-1.html")
        assertEquals(Route.ForumThread(1, ""), controller.get().deepLinkState.route.value)

        controller.newIntent(viewIntent("mtforum://forum/7"))

        assertEquals(Route.ForumList(7, ""), controller.get().deepLinkState.route.value)
        controller.destroy()
    }

    @Test
    fun `non forum link leaves no pending route`() {
        val controller = launch("https://example.com/thread-1-1-1.html")

        assertNull(controller.get().deepLinkState.route.value)
        controller.destroy()
    }

    private fun launch(url: String) =
        Robolectric.buildActivity(SettingsActivity::class.java, viewIntent(url)).create()

    private fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .setClass(RuntimeEnvironment.getApplication(), SettingsActivity::class.java)

    private class FakeAppSettingsRepository : AppSettingsRepository {
        // Never emits: a suspended preference read keeps the startup update check from firing in
        // a test that deliberately does not run the app's Koin graph.
        override val preferencesFlow: Flow<AppPreferences> = MutableSharedFlow()

        override suspend fun putString(setting: StringSetting, value: String) = Unit

        override fun getString(setting: StringSetting, default: String): Flow<String> = flowOf(default)

        override suspend fun putInt(setting: IntSetting, value: Int) = Unit

        override fun getInt(setting: IntSetting, default: Int): Flow<Int> = flowOf(default)

        override suspend fun putBoolean(setting: BooleanSetting, value: Boolean) = Unit

        override fun getBoolean(setting: BooleanSetting, default: Boolean): Flow<Boolean> = flowOf(default)
    }

    private class FakeUpdateRepository : UpdateRepository {
        override val updateInfoFlow: StateFlow<UpdateInfo?> = MutableStateFlow(null)

        override val checkState: StateFlow<UpdateCheckState> = MutableStateFlow(UpdateCheckState.Idle)

        override suspend fun checkUpdate(force: Boolean): UpdateCheckResult =
            UpdateCheckResult.Skipped(UpdateCheckResult.SkipReason.DEBUG_BUILD)

        override suspend fun downloadUpdate(url: String): Pair<InputStream, Long>? = null
    }
}
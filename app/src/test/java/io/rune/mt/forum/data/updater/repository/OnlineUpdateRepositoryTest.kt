// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.updater.repository

import com.sun.net.httpserver.HttpServer
import io.rune.mt.forum.core.device.model.Level
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
import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.UpdateCheckState
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `checkUpdate` end to end against a **local** copy of the real GitHub feed.
 *
 * `github_latest_release.json` is the captured `gh api repos/Rune-cn/MTForum/releases/latest`
 * payload (tag 26.11.9, asset `mtforum-stable-26.11.9.apk`); a throwaway HTTP server on
 * 127.0.0.1 serves it, so the whole repository path — HTTP hop, JSON parse, comparison, cache and
 * the **state machine** — runs exactly as in production, with no external network.
 *
 * The assertions that matter for 「一直显示检查更新」 are the `checkState` ones: after every branch
 * (success, skip, HTTP error, timeout, malformed payload, missing asset) the state must be
 * [UpdateCheckState.Settled], never [UpdateCheckState.Checking].
 */
class OnlineUpdateRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): String {
        val path = "src/test/resources/updater/github_latest_release.json"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    private fun repository(
        server: FixtureServer,
        preferences: AppPreferences = preferences(),
        deadlineMillis: Long = 10_000,
        currentVersion: String = "26.11.2",
    ) = OnlineUpdateRepositoryImpl(
        client = OkHttpClient(),
        json = json,
        appSettingsRepository = FakeSettingsRepository(preferences),
        environment = UpdateEnvironment(
            isDebug = false,
            level = Level.STABLE,
            currentVersion = currentVersion,
            apiBaseUrl = "http://127.0.0.1:${server.port}",
            deadlineMillis = deadlineMillis,
        ),
    )

    @Test
    fun `the captured feed settles as an update and stops the spinner`() = runBlocking {
        val server = FixtureServer(body = fixture()).start()
        try {
            val repo = repository(server)

            val result = repo.checkUpdate(force = true)

            val checked = result as? UpdateCheckResult.Checked ?: error("expected a comparison: $result")
            assertTrue(checked.info.hasUpdate)
            assertEquals("26.11.9", checked.info.remoteVersion)
            assertTrue("the check must leave the checking state", repo.checkState.value is UpdateCheckState.Settled)
            assertEquals(UpdateCheckState.Settled(checked), repo.checkState.value)
            assertEquals(checked.info, repo.updateInfoFlow.value)
            assertEquals("one feed request", 1, server.requests.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an up-to-date comparison also settles with its version`() = runBlocking {
        val server = FixtureServer(body = fixture()).start()
        try {
            val repo = repository(server, currentVersion = "26.11.9")

            val result = repo.checkUpdate(force = true)

            val checked = result as? UpdateCheckResult.Checked ?: error("expected a comparison: $result")
            assertFalse(checked.info.hasUpdate)
            assertEquals("26.11.9", checked.info.remoteVersion)
            assertTrue(repo.checkState.value is UpdateCheckState.Settled)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the cached answer is reused and force re-reads the feed`() = runBlocking {
        val server = FixtureServer(body = fixture()).start()
        try {
            val repo = repository(server)

            repo.checkUpdate(force = true)
            repo.checkUpdate(force = false)
            assertEquals("a non-forced check reads the cache", 1, server.requests.get())

            repo.checkUpdate(force = true)
            assertEquals("force asks the feed again", 2, server.requests.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `internet access off settles as a skip without any request`() = runBlocking {
        val server = FixtureServer(body = fixture()).start()
        try {
            val repo = repository(server, preferences = preferences(allowInternetAccess = false))

            val result = repo.checkUpdate(force = true)

            val skipped = result as? UpdateCheckResult.Skipped ?: error("expected a skip: $result")
            assertEquals(UpdateCheckResult.SkipReason.INTERNET_DISABLED, skipped.reason)
            assertEquals("no request was made", 0, server.requests.get())
            assertTrue(repo.checkState.value is UpdateCheckState.Settled)
            assertNull("a skip drops the stale update info", repo.updateInfoFlow.value)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an http error settles as a failure carrying the status`() = runBlocking {
        val server = FixtureServer(body = "rate limited", code = 403).start()
        try {
            val repo = repository(server)

            val result = repo.checkUpdate(force = true)

            val failed = result as? UpdateCheckResult.Failed ?: error("expected a failure: $result")
            assertTrue("the status is named: ${failed.reason}", failed.reason.contains("403"))
            assertTrue(repo.checkState.value is UpdateCheckState.Settled)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a hanging server is bounded by the call timeout and settles as a failure`() = runBlocking {
        val server = FixtureServer(body = fixture(), delayMillis = 2_000).start()
        try {
            val repo = repository(server, deadlineMillis = 400)

            val startedAt = System.currentTimeMillis()
            val result = repo.checkUpdate(force = true)
            val elapsed = System.currentTimeMillis() - startedAt

            assertTrue("expected a failure, got $result", result is UpdateCheckResult.Failed)
            assertTrue("the check gave up in ${elapsed}ms", elapsed < 1_900)
            assertTrue(
                "the loading state is cleared: ${repo.checkState.value}",
                repo.checkState.value !is UpdateCheckState.Checking,
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a malformed payload settles as a failure`() = runBlocking {
        val server = FixtureServer(body = "<html>not json</html>").start()
        try {
            val repo = repository(server)

            val result = repo.checkUpdate(force = true)

            val failed = result as? UpdateCheckResult.Failed ?: error("expected a failure: $result")
            assertFalse(failed.reason.isBlank())
            assertTrue(repo.checkState.value is UpdateCheckState.Settled)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a release without an apk asset still settles as a comparison`() = runBlocking {
        // "No matching asset" was one of the silent branches in the old implementation: it logged
        // and returned an UpdateInfo with an empty download URL. It must still settle the state.
        val payload = """
            {"tag_name":"26.11.9","html_url":"https://example/tag","assets":[{"name":"source.zip","browser_download_url":"https://example/src.zip"}]}
        """.trimIndent()
        val server = FixtureServer(body = payload).start()
        try {
            val repo = repository(server)

            val result = repo.checkUpdate(force = true)

            val checked = result as? UpdateCheckResult.Checked ?: error("expected a comparison: $result")
            assertEquals("26.11.9", checked.info.remoteVersion)
            assertEquals("", checked.info.downloadUrl)
            assertTrue(repo.checkState.value is UpdateCheckState.Settled)
        } finally {
            server.stop()
        }
    }

    private fun preferences(allowInternetAccess: Boolean = true) = AppPreferences(
        allowInternetAccess = allowInternetAccess,
        githubUpdateChannel = GithubUpdateChannel.OFFICIAL,
        customGithubProxyUrl = "",
        autoCheckUpdateOnStart = false,
        enableFileLogging = true,
        themeMode = ThemeMode.SYSTEM,
        paletteStyle = PaletteStyle.TonalSpot,
        colorSpec = ThemeColorSpec.SPEC_2025,
        useDynamicColor = true,
        useMiuixMonet = false,
        useAppleFloatingBar = false,
        seedColorInt = 0,
        wallpaperSeedColorInt = 0,
        useBlur = false,
        showMiuixUI = true,
        predictiveBackAnimation = PredictiveBackAnimation.MIUIX,
        predictiveBackExitDirection = PredictiveBackExitDirection.ALWAYS_RIGHT,
    )
}

/** A throwaway HTTP server that answers every path with one canned response. */
private class FixtureServer(
    private val body: String,
    private val code: Int = 200,
    private val delayMillis: Long = 0,
) {
    val requests = AtomicInteger()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val port: Int get() = server.address.port

    fun start(): FixtureServer = apply {
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun stop() = server.stop(0)
}

/** [AppSettingsRepository] fake: only [preferencesFlow] is read by the update checker. */
private class FakeSettingsRepository(preferences: AppPreferences) : AppSettingsRepository {
    private val _preferences = MutableStateFlow(preferences)
    override val preferencesFlow: Flow<AppPreferences> = _preferences.asStateFlow()

    override suspend fun putString(setting: StringSetting, value: String): Unit = error(UNUSED)
    override fun getString(setting: StringSetting, default: String): Flow<String> = error(UNUSED)
    override suspend fun putInt(setting: IntSetting, value: Int): Unit = error(UNUSED)
    override fun getInt(setting: IntSetting, default: Int): Flow<Int> = error(UNUSED)
    override suspend fun putBoolean(setting: BooleanSetting, value: Boolean): Unit = error(UNUSED)
    override fun getBoolean(setting: BooleanSetting, default: Boolean): Flow<Boolean> = error(UNUSED)

    private companion object {
        const val UNUSED = "the update checker only reads preferencesFlow"
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.updater.repository

import io.rune.mt.forum.core.device.model.Level
import io.rune.mt.forum.domain.settings.model.preferences.AppPreferences
import io.rune.mt.forum.domain.settings.model.preferences.GithubUpdateChannel
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackAnimation
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackExitDirection
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.checkedVersion
import io.rune.mt.forum.domain.updater.model.hasUpdate
import java.io.File
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update-check decision pipeline over the **real** GitHub payload.
 *
 * `app/src/test/resources/updater/github_latest_release.json` is the captured
 * `gh api repos/Rune-cn/MTForum/releases/latest` answer (tag `26.11.9`, one asset
 * `mtforum-stable-26.11.9.apk`). Every branch of `checkUpdate` is exercised against it or against a
 * deliberately broken feed, and every branch has to end in a definite result — the settings screen
 * derives its loading state from exactly this result.
 */
class UpdateCheckEngineTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): String {
        val path = "src/test/resources/updater/github_latest_release.json"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    private fun engine(
        currentVersion: String = "26.11.2",
        level: Level = Level.STABLE,
        isDebug: Boolean = false,
        deadlineMillis: Long = 5_000,
        now: () -> Long = { 1_790_000_000_000 },
    ) = UpdateCheckEngine(
        json = json,
        environment = UpdateEnvironment(
            isDebug = isDebug,
            level = level,
            currentVersion = currentVersion,
            deadlineMillis = deadlineMillis,
        ),
        now = now,
    )

    private fun preferences(
        allowInternetAccess: Boolean = true,
        channel: GithubUpdateChannel = GithubUpdateChannel.OFFICIAL,
        customProxyUrl: String = "",
    ) = AppPreferences(
        allowInternetAccess = allowInternetAccess,
        githubUpdateChannel = channel,
        customGithubProxyUrl = customProxyUrl,
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

    @Test
    fun `the captured release feed is an update for an older installed version`() = runTest {
        val result = engine(currentVersion = "26.11.2").check(preferences()) { fixture() }

        val checked = result as? UpdateCheckResult.Checked ?: error("expected a compared result: $result")
        assertTrue("26.11.9 > 26.11.2", checked.info.hasUpdate)
        assertEquals("26.11.9", checked.info.remoteVersion)
        assertEquals("26.11.9", result.checkedVersion)
        assertEquals("https://github.com/Rune-cn/MTForum/releases/tag/26.11.9", checked.info.releaseUrl)
        assertEquals(
            "https://github.com/Rune-cn/MTForum/releases/download/26.11.9/mtforum-stable-26.11.9.apk",
            checked.info.downloadUrl,
        )
        assertNull("the fixture release carries no notes", checked.info.updateLog)
        assertEquals(1_790_000_000_000, checked.checkedAt)
    }

    @Test
    fun `the captured release feed is up to date for the installed version`() = runTest {
        val result = engine(currentVersion = "26.11.9").check(preferences()) { fixture() }

        val checked = result as? UpdateCheckResult.Checked ?: error("expected a compared result: $result")
        assertFalse(checked.info.hasUpdate)
        assertEquals("26.11.9", checked.info.remoteVersion)
        assertFalse("up to date is not an update", result.hasUpdate)
    }

    @Test
    fun `version suffixes compare numerically, not lexicographically`() = runTest {
        // 26.11.9 vs 26.11.10: a string comparison would call 26.11.10 the older one and offer a
        // downgrade. Only the numeric comparison is correct.
        val newerInstalled = engine(currentVersion = "26.11.10").check(preferences()) { fixture() }
        assertFalse(newerInstalled.hasUpdate)

        val sameInstalled = engine(currentVersion = "26.11.9").check(preferences()) { fixture() }
        assertFalse(sameInstalled.hasUpdate)

        assertTrue(OnlineUpdatePolicy.compareVersions("26.11.10", "26.11.9") > 0)
        assertTrue(OnlineUpdatePolicy.compareVersions("26.11.9", "26.11.10") < 0)
        assertEquals(0, OnlineUpdatePolicy.compareVersions("v26.11.9", "26.11.9"))
    }

    @Test
    fun `a proxy channel rewrites the captured download url`() = runTest {
        val result = engine().check(
            preferences(channel = GithubUpdateChannel.PROXY_7ED),
        ) { fixture() }

        val checked = result as? UpdateCheckResult.Checked ?: error("expected a compared result: $result")
        assertEquals(
            "https://gh.sevencdn.com/https://github.com/Rune-cn/MTForum/releases/download/26.11.9/mtforum-stable-26.11.9.apk",
            checked.info.downloadUrl,
        )
    }

    @Test
    fun `the feed url follows the channel`() {
        assertEquals(
            "https://api.github.com/repos/Rune-cn/MTForum/releases/latest",
            engine(level = Level.STABLE).releasesUrl(),
        )
        assertEquals(
            "https://api.github.com/repos/Rune-cn/MTForum/releases",
            engine(level = Level.PREVIEW).releasesUrl(),
        )
    }

    @Test
    fun `every policy refusal is a skip and makes no request`() = runTest {
        var fetched = false
        val noInternet = engine().check(preferences(allowInternetAccess = false)) {
            fetched = true
            fixture()
        }
        assertEquals(
            UpdateCheckResult.SkipReason.INTERNET_DISABLED,
            (noInternet as UpdateCheckResult.Skipped).reason,
        )
        assertEquals("nothing was attempted", 0L, noInternet.checkedAt)

        val debug = engine(isDebug = true).check(preferences()) {
            fetched = true
            fixture()
        }
        assertEquals(
            UpdateCheckResult.SkipReason.DEBUG_BUILD,
            (debug as UpdateCheckResult.Skipped).reason,
        )

        val unstable = engine(level = Level.UNSTABLE).check(preferences()) {
            fetched = true
            fixture()
        }
        assertEquals(
            UpdateCheckResult.SkipReason.UNSTABLE_CHANNEL,
            (unstable as UpdateCheckResult.Skipped).reason,
        )

        assertFalse("a skipped check must not touch the network", fetched)
    }

    @Test
    fun `a transport error is a failure carrying the reason`() = runTest {
        val result = engine().check(preferences()) {
            throw IOException("GitHub HTTP 502 for https://api.github.com/repos/Rune-cn/MTForum/releases/latest")
        }

        val failed = result as? UpdateCheckResult.Failed ?: error("expected a failure: $result")
        assertTrue("the HTTP status is preserved: ${failed.reason}", failed.reason.contains("502"))
        assertTrue(failed.checkedAt > 0)
    }

    @Test
    fun `a hanging fetch times out into a failure instead of spinning`() = runTest {
        val result = engine(deadlineMillis = 1_000).check(preferences()) {
            awaitCancellation()
        }

        val failed = result as? UpdateCheckResult.Failed ?: error("expected a timeout failure: $result")
        assertTrue("the reason names the deadline: ${failed.reason}", failed.reason.contains("timeout"))
    }

    @Test
    fun `an unreadable payload is a failure`() = runTest {
        val result = engine().check(preferences()) { "<html>not json</html>" }

        val failed = result as? UpdateCheckResult.Failed ?: error("expected a failure: $result")
        assertTrue(
            "the parse error is named: ${failed.reason}",
            failed.reason.contains("unreadable GitHub payload"),
        )
    }

    @Test
    fun `the preview channel takes the first prerelease`() = runTest {
        val payload = """
            [
              {"tag_name":"26.11.9","prerelease":false,"assets":[{"name":"mtforum-stable-26.11.9.apk","browser_download_url":"https://example/stable.apk"}]},
              {"tag_name":"26.12.abcdef0","prerelease":true,"html_url":"https://example/tag","assets":[{"name":"mtforum-online-26.12.abcdef0.apk","browser_download_url":"https://example/preview.apk"}]}
            ]
        """.trimIndent()

        val result = engine(level = Level.PREVIEW, currentVersion = "26.11.9").check(preferences()) { payload }

        val checked = result as? UpdateCheckResult.Checked ?: error("expected a compared result: $result")
        assertTrue(checked.info.hasUpdate)
        assertEquals("26.12.abcdef0", checked.info.remoteVersion)
        assertEquals("https://example/preview.apk", checked.info.downloadUrl)
    }

    @Test
    fun `a preview feed without a prerelease is a failure`() = runTest {
        val payload = """
            [{"tag_name":"26.11.9","prerelease":false,"assets":[]}]
        """.trimIndent()

        val result = engine(level = Level.PREVIEW).check(preferences()) { payload }

        val failed = result as? UpdateCheckResult.Failed ?: error("expected a failure: $result")
        assertTrue(failed.reason.contains("no preview release"))
    }

    @Test
    fun `a release without an apk asset still compares and points at the release page`() = runTest {
        val payload = """
            {"tag_name":"26.11.9","html_url":"https://example/tag","assets":[{"name":"source.zip","browser_download_url":"https://example/src.zip"}]}
        """.trimIndent()

        val result = engine(currentVersion = "26.11.2").check(preferences()) { payload }

        val checked = result as? UpdateCheckResult.Checked ?: error("expected a compared result: $result")
        assertTrue("the tag still carries the version", checked.info.hasUpdate)
        assertEquals("26.11.9", checked.info.remoteVersion)
        assertEquals("no asset means no proxied URL", "", checked.info.downloadUrl)
        assertEquals("the release page is the fallback target", "https://example/tag", checked.info.releaseUrl)
    }
}

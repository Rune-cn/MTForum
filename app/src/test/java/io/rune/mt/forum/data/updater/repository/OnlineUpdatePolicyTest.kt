// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 MT论坛 contributors
package io.rune.mt.forum.data.updater.repository

import io.rune.mt.forum.core.device.model.Level
import io.rune.mt.forum.data.updater.model.GithubAsset
import io.rune.mt.forum.data.updater.model.GithubRelease
import io.rune.mt.forum.domain.settings.model.preferences.GithubUpdateChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class OnlineUpdatePolicyTest {
    @Test
    fun `update checks require network and a release-capable channel`() {
        assertTrue(canCheck(level = Level.PREVIEW))
        assertTrue(canCheck(level = Level.STABLE))
        assertFalse(canCheck(level = Level.UNSTABLE))
        assertFalse(canCheck(level = Level.PREVIEW, allowInternetAccess = false))
        assertFalse(canCheck(level = Level.PREVIEW, isDebug = true))
    }

    @Test
    fun `preview selection uses the first prerelease returned by GitHub`() {
        val stable = release(tag = "26.08", prerelease = false)
        val newestPreview = release(tag = "26.08.abcdef0", prerelease = true)
        val olderPreview = release(tag = "26.07.1234567", prerelease = true)

        assertSame(
            newestPreview,
            OnlineUpdatePolicy.selectPreviewRelease(listOf(stable, newestPreview, olderPreview)),
        )
        assertNull(OnlineUpdatePolicy.selectPreviewRelease(listOf(stable)))
    }

    @Test
    fun `GitHub release payload exposes the stable APK`() {
        val payload = """
            [
              {
                "tag_name": "26.11.3",
                "prerelease": false,
                "html_url": "https://github.com/Rune-cn/MTForum/releases/tag/26.11.3",
                "assets": [
                  {
                    "name": "mtforum-stable-26.11.3.apk",
                    "browser_download_url": "https://github.com/Rune-cn/MTForum/releases/download/26.11.3/mtforum-stable-26.11.3.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val releases = Json.decodeFromString<List<GithubRelease>>(payload)
        val release = releases.first()
        val apkAsset = OnlineUpdatePolicy.selectOnlineApkAsset(release)

        assertEquals("26.11.3", release.tagName)
        assertEquals("mtforum-stable-26.11.3.apk", apkAsset?.name)
        assertEquals(
            "https://github.com/Rune-cn/MTForum/releases/download/26.11.3/mtforum-stable-26.11.3.apk",
            OnlineUpdatePolicy.resolveDownloadUrl(apkAsset, GithubUpdateChannel.OFFICIAL, ""),
        )
        assertEquals("26.11.3", OnlineUpdatePolicy.resolveRemoteVersion(release, apkAsset))
    }

    @Test
    fun `stable APK wins over other apk assets and checksums are ignored`() {
        val checksum = asset("mtforum-stable-26.11.3.apk.sha256")
        val otherApk = asset("app-release.apk")
        val stableApk = asset("mtforum-stable-26.11.3.apk")
        val release = release(assets = listOf(checksum, otherApk, stableApk))

        assertSame(stableApk, OnlineUpdatePolicy.selectOnlineApkAsset(release))
        assertEquals("26.11.3", OnlineUpdatePolicy.resolveRemoteVersion(release, stableApk))
    }

    @Test
    fun `any apk asset is a fallback when no stable name exists`() {
        val plain = asset("app-release.apk")
        val release = release(assets = listOf(asset("source.zip"), plain))

        assertSame(plain, OnlineUpdatePolicy.selectOnlineApkAsset(release))
    }

    @Test
    fun `remote version is parsed from the asset and falls back to the tag`() {
        val release = release(tag = "v26.11.3")
        val stableAsset = asset("mtforum-stable-26.11.3.apk")
        val legacyAsset = asset("mtforum-online-26.08.abcdef0.apk")

        assertEquals("26.11.3", OnlineUpdatePolicy.resolveRemoteVersion(release, stableAsset))
        assertEquals("26.11.3", OnlineUpdatePolicy.resolveRemoteVersion(release, null))
        assertEquals("26.08.abcdef0", OnlineUpdatePolicy.resolveRemoteVersion(release, legacyAsset))
    }

    @Test
    fun `download URL supports official and proxy channels`() {
        val asset = asset(
            name = "mtforum-online-26.08.abcdef0.apk",
            url = "https://github.com/example/release.apk",
        )

        assertEquals(
            asset.browserDownloadUrl,
            OnlineUpdatePolicy.resolveDownloadUrl(asset, GithubUpdateChannel.OFFICIAL, ""),
        )
        assertEquals(
            "https://gh.sevencdn.com/${asset.browserDownloadUrl}",
            OnlineUpdatePolicy.resolveDownloadUrl(asset, GithubUpdateChannel.PROXY_7ED, ""),
        )
        assertEquals(
            "https://proxy.example/${asset.browserDownloadUrl}",
            OnlineUpdatePolicy.resolveDownloadUrl(
                asset,
                GithubUpdateChannel.CUSTOM,
                "https://proxy.example/",
            ),
        )
        assertEquals(
            "",
            OnlineUpdatePolicy.resolveDownloadUrl(null, GithubUpdateChannel.OFFICIAL, ""),
        )
    }

    @Test
    fun `version comparison preserves release ordering rules`() {
        assertTrue(OnlineUpdatePolicy.compareVersions("26.09.abcdef0", "26.08.1234567") > 0)
        assertTrue(OnlineUpdatePolicy.compareVersions("26.08.01", "26.08") > 0)
        assertTrue(OnlineUpdatePolicy.compareVersions("26.08.abcdef0", "26.08") > 0)
        assertTrue(OnlineUpdatePolicy.compareVersions("26.08", "26.08.abcdef0") < 0)
        assertTrue(OnlineUpdatePolicy.compareVersions("26.08.abcdef0", "26.08.1234567") > 0)
        assertEquals(0, OnlineUpdatePolicy.compareVersions("26.08.abcdef0", "26.08.abcdef0"))
        // Real stable tags: the About page compares the release tag with the installed version.
        assertTrue(OnlineUpdatePolicy.compareVersions("26.11.3", "26.11.2") > 0)
        assertEquals(0, OnlineUpdatePolicy.compareVersions("26.11.3", "26.11.3"))
        assertEquals(0, OnlineUpdatePolicy.compareVersions("v26.11.3", "26.11.3"))
        assertTrue(OnlineUpdatePolicy.compareVersions("26.11.2", "26.11.3") < 0)
    }

    private fun canCheck(
        level: Level,
        allowInternetAccess: Boolean = true,
        isDebug: Boolean = false,
    ): Boolean = OnlineUpdatePolicy.canCheckUpdates(
        allowInternetAccess = allowInternetAccess,
        isDebug = isDebug,
        level = level,
    )

    private fun release(tag: String = "26.08.abcdef0", prerelease: Boolean = true, assets: List<GithubAsset> = emptyList()) = GithubRelease(
        tagName = tag,
        isPrerelease = prerelease,
        assets = assets,
    )

    private fun asset(name: String, url: String = "https://github.com/example/$name") = GithubAsset(name = name, browserDownloadUrl = url)
}

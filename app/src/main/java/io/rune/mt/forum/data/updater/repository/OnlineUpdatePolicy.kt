// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.updater.repository

import io.rune.mt.forum.core.device.model.Level
import io.rune.mt.forum.data.updater.model.GithubAsset
import io.rune.mt.forum.data.updater.model.GithubRelease
import io.rune.mt.forum.domain.settings.model.preferences.GithubUpdateChannel

internal object OnlineUpdatePolicy {
    private const val PROXY_7ED = "https://gh.sevencdn.com/"
    private const val STABLE_APK_PREFIX = "mtforum-stable-"

    /** Release assets that are not the APK itself (checksum sidecars, signatures, manifests). */
    private val CHECKSUM_MARKERS = listOf("sha256", "sha512", ".sha", ".md5", "checksum", ".sig")

    /** Trailing `-<version>` segment of an asset name, hash-suffixed preview versions included. */
    private val VERSION_SUFFIX = Regex("""-(\d+(?:\.\d+)*(?:\.[a-fA-F0-9]{7,})?)$""")

    /**
     * Whether this build may query GitHub for updates.
     *
     * There is deliberately no package-name comparison: `applicationId` is fixed at build time,
     * so the old `packageName == packageName` argument compared the running package to itself
     * and could never fail. Hard-coding the official package instead would silently disable
     * update checks for renamed/forked builds, which is the opposite of what is wanted.
     */
    fun canCheckUpdates(
        allowInternetAccess: Boolean,
        isDebug: Boolean,
        level: Level,
    ): Boolean = allowInternetAccess &&
        !isDebug &&
        level != Level.UNSTABLE

    fun selectPreviewRelease(releases: List<GithubRelease>): GithubRelease? = releases.firstOrNull(GithubRelease::isPrerelease)

    /**
     * Picks the release APK that the update sheet should offer.
     *
     * Stable releases publish `mtforum-stable-<version>.apk`, while older preview releases used
     * `mtforum-online-<version>.apk`. The stable name wins so a release that bundles several
     * APKs still resolves to the stable one; otherwise any `.apk` asset is accepted so a plain
     * `app-release.apk` still yields a usable download URL. Checksum sidecars are skipped.
     */
    fun selectOnlineApkAsset(release: GithubRelease): GithubAsset? {
        val apkAssets = release.assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                CHECKSUM_MARKERS.none { marker -> asset.name.contains(marker, ignoreCase = true) }
        }

        return apkAssets.firstOrNull { asset ->
            asset.name.startsWith(STABLE_APK_PREFIX, ignoreCase = true)
        } ?: apkAssets.firstOrNull()
    }

    fun resolveDownloadUrl(asset: GithubAsset?, channel: GithubUpdateChannel, customProxyUrl: String): String {
        val browserDownloadUrl = asset?.browserDownloadUrl.orEmpty()
        if (browserDownloadUrl.isEmpty()) return ""

        val proxyUrl = when (channel) {
            GithubUpdateChannel.OFFICIAL -> ""
            GithubUpdateChannel.PROXY_7ED -> PROXY_7ED
            GithubUpdateChannel.CUSTOM -> customProxyUrl
        }
        if (proxyUrl.isEmpty()) return browserDownloadUrl

        return "${proxyUrl.trimEnd('/')}/$browserDownloadUrl"
    }

    /**
     * Resolves the version advertised by a release.
     *
     * Asset names repeat the built version and win when present; preview tags can carry a
     * different hash suffix than the APK they point at. The tag is the fallback. A leading `v`
     * is stripped from either form so `v26.11.3` and `26.11.3` resolve identically.
     */
    fun resolveRemoteVersion(release: GithubRelease, asset: GithubAsset?): String =
        asset?.name?.let(::versionFromAssetName)
            ?: normalizeVersion(release.tagName)

    fun compareVersions(first: String, second: String): Int {
        val (numericFirst, hashFirst) = splitVersion(normalizeVersion(first))
        val (numericSecond, hashSecond) = splitVersion(normalizeVersion(second))

        val numericComparison = compareNumericVersion(numericFirst, numericSecond)
        if (numericComparison != 0) return numericComparison

        return when {
            hashFirst != null && hashSecond != null -> if (hashFirst == hashSecond) 0 else 1
            hashFirst != null -> 1
            hashSecond != null -> -1
            else -> 0
        }
    }

    /**
     * Extracts the version embedded in an asset name (`mtforum-stable-26.11.3.apk`,
     * legacy `mtforum-online-26.08.abcdef0.apk`, plain `app-26.11.3-release.apk`).
     *
     * The version is the trailing `-<number>[.<number>]*[.<hash>]` segment, so a name that also
     * contains a build flavour or ABI still resolves. Returns null when no version segment
     * exists, which lets the caller fall back to the release tag.
     */
    private fun versionFromAssetName(name: String): String? {
        val base = name.substringBeforeLast('.', name)
        val match = VERSION_SUFFIX.find(base) ?: return null
        return normalizeVersion(match.groupValues[1])
    }

    /** Accepts both `v26.11.3` and `26.11.3` so tag- and asset-derived versions compare equal. */
    private fun normalizeVersion(version: String): String =
        version.trim().removePrefix("v").removePrefix("V")

    private fun splitVersion(version: String): Pair<String, String?> {
        val parts = version.split('.')
        val lastPart = parts.lastOrNull().orEmpty()
        val isHash = lastPart.length >= 7 && lastPart.matches(Regex("^[a-fA-F0-9]+$"))

        return if (isHash) {
            parts.dropLast(1).joinToString(".") to lastPart
        } else {
            version to null
        }
    }

    private fun compareNumericVersion(first: String, second: String): Int {
        val firstParts = first.split('.')
        val secondParts = second.split('.')

        for (index in 0 until maxOf(firstParts.size, secondParts.size)) {
            val firstPart = firstParts.getOrNull(index)?.toIntOrNull() ?: 0
            val secondPart = secondParts.getOrNull(index)?.toIntOrNull() ?: 0
            firstPart.compareTo(secondPart).takeIf { it != 0 }?.let { return it }
        }
        return 0
    }
}

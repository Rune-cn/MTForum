// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.updater.repository

import io.rune.mt.forum.core.device.model.Level
import io.rune.mt.forum.data.updater.model.GithubRelease
import io.rune.mt.forum.domain.settings.model.preferences.AppPreferences
import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * The update-check decision pipeline: policy → feed → release selection → version comparison.
 *
 * Kept free of HTTP and of the Android build constants so every branch can be pinned by a test
 * (including the real captured GitHub payload in `app/src/test/resources/updater/`). The
 * repository only supplies the bytes ([fetch] returns the JSON body, or throws with a technical
 * reason) and publishes the result.
 *
 * Every path returns a definite [UpdateCheckResult]; nothing here can leave a caller without an
 * answer, and the network hop is bounded by [UpdateEnvironment.deadlineMillis].
 */
internal class UpdateCheckEngine(
    private val json: Json,
    private val environment: UpdateEnvironment,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Runs one check.
     *
     * @param fetch GETs [url] and returns its body; it must throw (with a technical reason) on a
     *   non-2xx answer or a transport error. It is given a deadline, so a fixture/server that
     *   never answers still ends the check with [UpdateCheckResult.Failed] instead of hanging.
     */
    suspend fun check(
        prefs: AppPreferences,
        fetch: suspend (url: String) -> String,
    ): UpdateCheckResult {
        skipReason(prefs)?.let { reason ->
            Timber.i("Update check skipped: %s", reason)
            return UpdateCheckResult.Skipped(reason)
        }

        val url = releasesUrl()
        val body = try {
            withTimeout(environment.deadlineMillis) { fetch(url) }
        } catch (timeout: TimeoutCancellationException) {
            Timber.w("Update check timed out after %d ms (%s)", environment.deadlineMillis, url)
            return UpdateCheckResult.Failed(
                reason = "timeout after ${environment.deadlineMillis} ms ($url)",
                checkedAt = now(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "Update check fetch failed (%s)", url)
            return UpdateCheckResult.Failed(
                reason = error.message ?: error.javaClass.simpleName,
                checkedAt = now(),
            )
        }

        val release = try {
            parseRelease(body)
        } catch (error: Exception) {
            Timber.w(error, "Update check could not parse the GitHub payload")
            return UpdateCheckResult.Failed(
                reason = "unreadable GitHub payload: ${error.message ?: error.javaClass.simpleName}",
                checkedAt = now(),
            )
        }
        if (release == null) {
            Timber.w("Update check: the GitHub feed carried no usable release")
            return UpdateCheckResult.Failed(
                reason = if (environment.level == Level.STABLE) {
                    "no release in the GitHub response"
                } else {
                    "no preview release in the GitHub response"
                },
                checkedAt = now(),
            )
        }

        val apkAsset = OnlineUpdatePolicy.selectOnlineApkAsset(release)
        if (apkAsset == null) {
            // Not a failure: the tag still carries the version and the release page is the
            // fallback download target (the update sheet prefers downloadUrl, then releaseUrl).
            Timber.w("Update check: release %s has no APK asset", release.tagName)
        }
        val remoteVersion = OnlineUpdatePolicy.resolveRemoteVersion(release, apkAsset)
        val downloadUrl = OnlineUpdatePolicy.resolveDownloadUrl(
            asset = apkAsset,
            channel = prefs.githubUpdateChannel,
            customProxyUrl = prefs.customGithubProxyUrl,
        )
        val hasUpdate = OnlineUpdatePolicy.compareVersions(remoteVersion, environment.currentVersion) > 0

        Timber.i(
            "Update check: local=%s remote=%s hasUpdate=%s asset=%s",
            environment.currentVersion,
            remoteVersion,
            hasUpdate,
            apkAsset?.name,
        )
        return UpdateCheckResult.Checked(
            info = UpdateInfo(
                hasUpdate = hasUpdate,
                remoteVersion = remoteVersion,
                releaseUrl = release.htmlUrl.orEmpty(),
                downloadUrl = downloadUrl,
                updateLog = release.body,
            ),
            checkedAt = now(),
        )
    }

    /** The feed URL for the current channel: stable reads `releases/latest`, preview reads all. */
    fun releasesUrl(): String = buildString {
        append(environment.apiBaseUrl.trimEnd('/'))
        append("/repos/").append(REPO_OWNER).append('/').append(REPO_NAME)
        append(if (environment.level == Level.STABLE) "/releases/latest" else "/releases")
    }

    private fun parseRelease(body: String): GithubRelease? =
        if (environment.level == Level.STABLE) {
            json.decodeFromString<GithubRelease>(body)
        } else {
            OnlineUpdatePolicy.selectPreviewRelease(json.decodeFromString<List<GithubRelease>>(body))
        }

    private fun skipReason(prefs: AppPreferences): UpdateCheckResult.SkipReason? {
        val allowed = OnlineUpdatePolicy.canCheckUpdates(
            allowInternetAccess = prefs.allowInternetAccess,
            isDebug = environment.isDebug,
            level = environment.level,
        )
        if (allowed) return null
        return when {
            !prefs.allowInternetAccess -> UpdateCheckResult.SkipReason.INTERNET_DISABLED
            environment.isDebug -> UpdateCheckResult.SkipReason.DEBUG_BUILD
            else -> UpdateCheckResult.SkipReason.UNSTABLE_CHANNEL
        }
    }

    private companion object {
        const val REPO_OWNER = "Rune-cn"
        const val REPO_NAME = "MTForum"
    }
}
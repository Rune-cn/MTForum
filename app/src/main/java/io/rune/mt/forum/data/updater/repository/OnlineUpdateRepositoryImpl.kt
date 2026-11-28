// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.updater.repository

import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.UpdateCheckState
import io.rune.mt.forum.domain.updater.model.UpdateInfo
import io.rune.mt.forum.domain.updater.repository.UpdateRepository
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * The GitHub-backed update checker.
 *
 * The check itself lives in [UpdateCheckEngine] (pure decision pipeline); this class owns the
 * HTTP hop, the cache, the serialisation of concurrent checks and — most importantly — the
 * lifecycle of [checkState]. The loading UI is derived from that state, so it cannot outlive the
 * check: every exit path (cache hit, policy skip, transport error, timeout, cancellation, missing
 * asset, parse error) ends in [UpdateCheckState.Settled] or [UpdateCheckState.Idle].
 *
 * Two hang guards, because the device report was 「一直显示检查更新」:
 *  - `callTimeout` on the check client bounds DNS + connect + read + redirects. `connectTimeout`
 *    alone does not cover DNS, and a blocking `execute()` cannot be cancelled by a coroutine.
 *  - `withTimeout` around the mutex + pipeline bounds the *suspending* parts (waiting for a
 *    previous check's lock, reading preferences), so a manual check does not wait forever behind
 *    a startup check that got stuck.
 */
class OnlineUpdateRepositoryImpl internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val appSettingsRepository: AppSettingsRepository,
    private val environment: UpdateEnvironment = UpdateEnvironment.fromBuild(),
) : UpdateRepository {
    private companion object {
        /** Extra slack on top of the engine deadline, for the mutex wait and the preference read. */
        const val DEADLINE_GRACE_MILLIS = 2_000L

        /** Same coordinates as [UpdateCheckEngine]'s feed; the Atom fallback needs the web host. */
        const val REPO_OWNER = "Rune-cn"
        const val REPO_NAME = "MTForum"
        const val TAG = "OnlineUpdateRepository"
    }

    private val engine = UpdateCheckEngine(json, environment)

    /** The JSON hop needs a whole-call bound; the download stream must not share it. */
    private val checkClient: OkHttpClient = client.newBuilder()
        .callTimeout(environment.deadlineMillis, TimeUnit.MILLISECONDS)
        .build()

    private val _updateInfoFlow = MutableStateFlow<UpdateInfo?>(null)
    override val updateInfoFlow: StateFlow<UpdateInfo?> = _updateInfoFlow.asStateFlow()

    private val _checkState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    override val checkState: StateFlow<UpdateCheckState> = _checkState.asStateFlow()

    private val updateMutex = Mutex()

    override suspend fun checkUpdate(force: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        // A cached answer only exists for a real comparison; failures/skips are retried next time.
        if (!force) cachedResult()?.let { return@withContext it }
        Timber.d("checkUpdate: force=%s", force)

        val result: UpdateCheckResult = try {
            withTimeout(environment.deadlineMillis + DEADLINE_GRACE_MILLIS) {
                updateMutex.withLock { runCheck(force) }
            }
        } catch (timeout: TimeoutCancellationException) {
            // Either the lock wait or a cooperative suspend outlived the deadline. The blocking
            // socket is bounded separately by callTimeout.
            UpdateCheckResult.Failed(
                reason = "timeout after ${environment.deadlineMillis + DEADLINE_GRACE_MILLIS} ms",
                checkedAt = System.currentTimeMillis(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.e(error, "checkUpdate: unexpected failure")
            UpdateCheckResult.Failed(
                reason = error.message ?: error.javaClass.simpleName,
                checkedAt = System.currentTimeMillis(),
            )
        }
        publish(result)
        result
    }

    /**
     * One check, with [UpdateCheckState.Checking] published while it runs.
     *
     * The `finally` clears [UpdateCheckState.Checking] when the body was cancelled or threw: a
     * cancelled check must not leave the spinner up, even though the caller rethrows.
     */
    private suspend fun runCheck(force: Boolean): UpdateCheckResult {
        if (!force) cachedResult()?.let { return it }
        _checkState.value = UpdateCheckState.Checking
        var settled = false
        try {
            val prefs = appSettingsRepository.preferencesFlow.first()
            val result = engine.check(prefs) { url -> fetchBody(url) }
            settled = true
            return result
        } finally {
            if (!settled && _checkState.value is UpdateCheckState.Checking) {
                _checkState.value = UpdateCheckState.Idle
            }
        }
    }

    /** Stores [result] as the cached answer and as the settled state the UI renders. */
    private fun publish(result: UpdateCheckResult) {
        when (result) {
            is UpdateCheckResult.Checked -> _updateInfoFlow.value = result.info
            // Internet access was switched off: the previous answer is no longer meaningful.
            is UpdateCheckResult.Skipped -> _updateInfoFlow.value = null
            // A transient failure keeps the last known answer (an older "update available" is
            // still true); the failure itself is reported through checkState.
            is UpdateCheckResult.Failed -> Unit
        }
        _checkState.value = UpdateCheckState.Settled(result)
    }

    /**
     * The last comparison, or null.
     *
     * Only [UpdateCheckResult.Checked] is cacheable: a skip or a failure must be retried on the
     * next (non-forced) check instead of being replayed forever.
     */
    private fun cachedResult(): UpdateCheckResult? =
        (checkState.value as? UpdateCheckState.Settled)?.result
            ?.takeIf { it is UpdateCheckResult.Checked }

    /**
     * GETs [url] and returns the JSON body, throwing a technical reason otherwise.
     *
     * The body is never logged (it is a release payload, not credentials, but the rule is "no
     * response dumps in logs"), and the error names the status code only.
     */
    private fun fetchBody(url: String): String = try {
        httpGet(url)
    } catch (apiError: IOException) {
        // api.github.com answers 403/429 once the anonymous quota (60/h per IP) is spent. The
        // public Atom feed carries the same releases and has no quota, so only that specific
        // refusal degrades to it: timeouts, 5xx and fixture servers must still fail as before.
        val quotaExhausted = apiError.message.let {
            it != null && (it.contains("HTTP 403") || it.contains("HTTP 429"))
        }
        if (!quotaExhausted || !url.startsWith("https://api.github.com")) throw apiError
        Timber.tag(TAG).w("update REST quota refused (%s); falling back to the Atom feed", apiError.message)
        atomFeedAsReleaseJson(httpGet("https://github.com/$REPO_OWNER/$REPO_NAME/releases.atom", "application/atom+xml"))
    }

    private fun httpGet(url: String, accept: String = "application/vnd.github+json"): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "MTForum-updater")
            .build()
        checkClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("GitHub HTTP ${response.code} for $url")
            }
            return body
        }
    }

    /**
     * Turns the newest `releases.atom` entry into the same JSON shape the REST hop returns, so the
     * engine and the asset policy stay unchanged. The APK name is the one the release workflow
     * publishes (`mtforum-stable-<tag>.apk`); if a release ever renames it the download still
     * falls back to the release page.
     */
    private fun atomFeedAsReleaseJson(xml: String): String {
        val entry = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: throw IOException("Atom feed carried no release entry")
        fun group(pattern: String, source: String = entry): String =
            Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1).orEmpty().trim()
        val tag = group("<title>(.*?)</title>")
        if (tag.isBlank()) throw IOException("Atom entry carried no tag")
        val htmlUrl = group("<link[^>]*href=\"([^\"]+)\"")
        val notes = group("<content[^>]*>(.*?)</content>")
        val apk = "mtforum-stable-$tag.apk"
        val download = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag/$apk"
        return buildJsonObject {
            put("tag_name", tag)
            put("name", "MT论坛 stable $tag")
            put("html_url", htmlUrl)
            put("body", notes)
            putJsonArray("assets") {
                addJsonObject {
                    put("name", apk)
                    put("browser_download_url", download)
                }
            }
        }.toString()
    }

    override suspend fun downloadUpdate(url: String): Pair<InputStream, Long>? = withContext(Dispatchers.IO) {
        if (!appSettingsRepository.preferencesFlow.first().allowInternetAccess) {
            Timber.d("downloadUpdate: Skipped because internet access is disabled")
            return@withContext null
        }

        Timber.d("Starting download stream from: $url")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.e("Download failed: ${response.code}")
                response.close()
                return@withContext null
            }
            val body = response.body
            val contentLength = body.contentLength()

            Pair(body.byteStream(), contentLength)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Exception during download request")
            null
        }
    }
}
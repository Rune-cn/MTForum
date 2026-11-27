// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber

/**
 * Fetches a cookie-gated attachment into a local file.
 *
 * Discuz serves `forum.php?mod=attachment&aid=…` only to a logged-in session and only after the
 * acw challenge has been solved, so a plain browser download of that URL returns 403 or the JS
 * stub. The request therefore goes through the shared authenticated stack ([OkHttpClient] from
 * [ForumHttpClient], which carries [ForumCookieJar] and [AcwInterceptor]) instead of the system
 * downloader.
 *
 * A **paid** attachment (`下载积分: 金币 -1` on its card) is not served by that URL at all: the first
 * response is a 302 to `mod=misc&action=attachcredit…`, whose 提示信息 page must be followed through
 * exactly one `点击此链接进行跳转` link (the aid hash is regenerated and a `ck` token appended). That
 * handshake is handled here when the first response is HTML; a second HTML response is a failure,
 * never another hop — re-following would loop or charge the account twice. See REVERSE_SPEC §23.
 *
 * The body is streamed to disk — a post attachment can be hundreds of megabytes, so buffering it
 * in memory would OOM. Progress is delivered as a fraction of `Content-Length` (unknown length
 * degrades to `null`, which the UI shows as an indeterminate bar rather than a fake 0 %).
 *
 * **Cookies are never logged** (AGENTS rule 10): only the URL and the byte counts reach the log.
 */
class ForumAttachmentDownloader(
    private val client: OkHttpClient,
) {

    /**
     * Streams [url] into [target], reporting progress through [onProgress].
     *
     * @param onProgress fraction in `0f..1f`, or `null` when the server sent no `Content-Length`.
     * @return the number of bytes written.
     * @throws IOException when the request fails, the server rejects it (403/404), the paid
     *   attachment's attachcredit handshake refuses the download (the server's own message is the
     *   exception message), or the response is an HTML page instead of the file.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: ((Float?) -> Unit)? = null,
    ): Long = withContext(Dispatchers.IO) {
        get(url).use { response ->
            requireSuccess(response)
            if (response.body.contentType().isHtml()) {
                // Reading the stub page is cheap (Discuz 提示信息 pages are a few KB) and lets the
                // handshake / notice extraction run on the bytes the user would have seen.
                val html = response.body.string()
                return@withContext followAttachCredit(html, response.request.url.toString(), target, onProgress)
            }
            stream(response, target, onProgress)
        }
    }

    /**
     * One-shot GET, carrying the same UA/Referer as every other forum request.
     *
     * The caller owns the returned [Response] and must close it.
     */
    private fun get(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ForumHttpClient.MOBILE_UA)
            .header("Referer", ForumHttpClient.BASE_URL + "forum.php")
            .get()
            .build()
        return client.newCall(request).execute()
    }

    private fun requireSuccess(response: Response) {
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
    }

    /**
     * Classifies an HTML response and performs the single allowed attachcredit hop.
     *
     * Three HTML outcomes are possible:
     * - the **attachcredit** handshake page → follow its one continue link, then stream the file;
     * - a **failure notice** (金币不足 / 抱歉 / 无法下载 / 请先登录 / 权限) → fail with the server text;
     * - the **acw** JS stub (the interceptor normally solved it) → fail, without a second solver.
     *
     * A second HTML response is never followed: it means the handshake failed.
     */
    private suspend fun followAttachCredit(
        html: String,
        pageUrl: String,
        target: File,
        onProgress: ((Float?) -> Unit)?,
    ): Long {
        if (!ForumHtmlParser.isAttachCreditPage(html, pageUrl)) {
            val notice = ForumHtmlParser.extractNotice(html)
            if (notice != null && ForumHtmlParser.isAttachmentFailureNotice(html)) {
                throw IOException(notice)
            }
            throw IOException(
                when {
                    ForumHtmlParser.isAcwChallenge(html) ->
                        "challenge page instead of a file (acw_sc__v2 unsolved)"
                    else -> notice?.let { "HTML page instead of a file: $it" }
                        ?: "HTML page instead of a file"
                },
            )
        }

        val notice = ForumHtmlParser.extractNotice(html)
        // A refusal can itself be rendered on the attachcredit page (e.g. 金币不足): surface the
        // server's own wording and stop instead of following a link that cannot deliver a file.
        if (ForumHtmlParser.isAttachmentFailureNotice(html)) {
            throw IOException(notice ?: "attachcredit refused the download")
        }

        val continueHref = ForumHtmlParser.attachCreditContinueUrl(html)
            ?: throw IOException(notice ?: "attachcredit page carries no continue link")
        val continueUrl = resolveContinueUrl(pageUrl, continueHref)
        Timber.tag(TAG).i("attachcredit hop: %s -> %s", pageUrl, continueUrl)

        get(continueUrl).use { response ->
            requireSuccess(response)
            if (response.body.contentType().isHtml()) {
                val secondHtml = response.body.string()
                throw IOException(
                    ForumHtmlParser.extractNotice(secondHtml)
                        ?: "attachcredit check did not return a file",
                )
            }
            return stream(response, target, onProgress)
        }
    }

    /**
     * Uses the decoded continue href **verbatim** when it is absolute — re-encoding would corrupt
     * the aid hash / `ck` token the server just minted. Only a relative href is resolved against
     * the page URL (and the raw href is the fallback when the URI is malformed).
     */
    private fun resolveContinueUrl(pageUrl: String, href: String): String {
        if (href.startsWith("https://", ignoreCase = true) || href.startsWith("http://", ignoreCase = true)) {
            return href
        }
        return runCatching { java.net.URI(pageUrl).resolve(href).toString() }.getOrElse { href }
    }

    /** Streams [response]'s body to [target]; rejects a truncated body and reports progress. */
    private suspend fun stream(
        response: Response,
        target: File,
        onProgress: ((Float?) -> Unit)?,
    ): Long {
        val body = response.body
        val total = body.contentLength().takeIf { it > 0 }
        target.parentFile?.mkdirs()
        var written = 0L
        body.byteStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var lastReported = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    // Throttle to whole-percent steps: a per-chunk callback would recompose
                    // the progress bar hundreds of times a second for no visible benefit.
                    val percent = total?.let { ((written * 100) / it).toInt() } ?: -1
                    if (percent != lastReported) {
                        lastReported = percent
                        onProgress?.invoke(total?.let { (written.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
                output.flush()
            }
        }
        if (total != null && written != total) {
            // A truncated body must not be reported as a finished download.
            throw IOException("incomplete: $written of $total bytes")
        }
        onProgress?.invoke(1f)
        Timber.tag(TAG).i("downloaded %s -> %d bytes", response.request.url, written)
        return written
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val TAG = "ForumAttachment"
    }
}

/** True when the body is an HTML page (the handshake/notice/challenge stub, never a file). */
private fun MediaType?.isHtml(): Boolean =
    this?.toString()?.contains("text/html", ignoreCase = true) == true

/**
 * Turns an attachment [name] into a safe on-disk file name.
 *
 * The name comes from the post, i.e. from another user, so it must never escape the download
 * directory: path separators, `..`, control characters and Windows-reserved characters are all
 * removed, and a leading dot is stripped so a download cannot create a hidden file. An empty result
 * falls back to [fallback] so the caller always gets a usable name.
 */
internal fun sanitiseAttachmentFileName(name: String, fallback: String = "attachment"): String {
    // A URL-ish source may carry `?query#fragment`; strip those first. A genuine `?` inside a
    // plain file name is then treated as a reserved character below, so `.zip` survives both ways.
    val base = name.substringBefore('#').let { candidate ->
        val queryStart = candidate.indexOf('?')
        // Only treat `?` as a query marker when it is followed by `key=value` (how a URL writes
        // it); otherwise it is a reserved character inside the name itself.
        if (queryStart >= 0 && candidate.substring(queryStart + 1).contains('=')) {
            candidate.substring(0, queryStart)
        } else {
            candidate
        }
    }
    val cleaned = base
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .replace("..", "_")
        .trim()
        .trimStart('.')
        .trim()
        .take(MAX_NAME_LENGTH)
    return cleaned.ifBlank { fallback }
}

/** Long enough for any real file name, short enough to stay under common filesystem limits. */
private const val MAX_NAME_LENGTH = 120

/** True when a cancelled download should be reported as cancelled rather than as a failure. */
internal fun Throwable.isCancellation(): Boolean = this is CancellationException
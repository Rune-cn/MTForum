// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
     * @throws IOException when the request fails, the server rejects it (403/404), or the response
     *   is the acw challenge page instead of the file.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: ((Float?) -> Unit)? = null,
    ): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ForumHttpClient.MOBILE_UA)
            .header("Referer", ForumHttpClient.BASE_URL + "forum.php")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body
            val contentType = body.contentType()?.toString().orEmpty()
            // The anti-bot interceptor replays a solved challenge, but if it exhausted its attempts
            // the "file" is a 4 KB HTML stub. Writing that to disk would look like success, so it
            // is rejected here — a download that produces an HTML page is a failure, not a file.
            if (contentType.contains("text/html", ignoreCase = true)) {
                throw IOException("challenge page instead of a file ($contentType)")
            }

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
            Timber.tag("ForumAttachment").i("downloaded %s -> %d bytes", url, written)
            written
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

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
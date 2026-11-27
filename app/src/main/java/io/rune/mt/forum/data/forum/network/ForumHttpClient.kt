// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/** One file part of a multipart POST (attachment upload). */
class MultipartPart(
    val fieldName: String,
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * One fetched page: the HTTP status, the URL the body actually came from and the decoded body.
 *
 * [url] is the **final** URL, so a silent redirect (e.g. a stale cookie being bounced to
 * `logging.php?action=login`) is visible in a device log instead of being guessed at.
 */
data class ForumHttpResult(
    val code: Int,
    val url: String,
    val body: String,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * The forum HTTP surface the services talk to.
 *
 * [ForumHttpClient] is its only implementation, so the cookie jar and [AcwInterceptor] stay in one
 * place (rule 1). The interface exists so a service that must be exercised against a captured page
 * can be handed a fixture-backed source in a unit test without opening a socket.
 */
interface ForumHttpGateway {
    /** Fetches [path] and throws when the status is not 2xx. */
    suspend fun getHtml(path: String): String

    /** Same GET as [getHtml] but returns the status/URL too, for diagnostics. */
    suspend fun getHtmlResult(path: String): ForumHttpResult

    /** Form POST (`application/x-www-form-urlencoded`). */
    suspend fun postForm(path: String, fields: Map<String, String>): String

    /** Multipart POST, used for the composer's form (attachments ride along as parts). */
    suspend fun postMultipart(path: String, fields: Map<String, String>, parts: List<MultipartPart>): String
}

/**
 * Thin HTTP facade for bbs.binmt.cc.
 *
 * Every call goes through [AcwInterceptor] so the anti-bot challenger is transparently solved.
 * Mobile UA is mandatory: the desktop template has a completely different DOM.
 *
 * Both entry points are `suspend` AND run their blocking `execute()` inside
 * [Dispatchers.IO]. Marking a function `suspend` does not move it off the caller's dispatcher,
 * so without the `withContext` every screen that loaded on the main dispatcher died with
 * `NetworkOnMainThreadException` — the failure that originally surfaced as "主页和导读都崩".
 * Keeping the switch here means no caller has to remember it.
 */
class ForumHttpClient(
    private val cookieJar: ForumCookieJar,
) : ForumHttpGateway {

    companion object {
        const val BASE_URL = "https://bbs.binmt.cc/"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val TIMEOUT_SECONDS = 20L
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(AcwInterceptor(cookieJar))
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun getHtml(path: String): String {
        val result = getHtmlResult(path)
        if (!result.isSuccessful) error("HTTP ${result.code} for $path")
        return result.body
    }

    /**
     * The same GET as [getHtml], with the status code and the final URL kept.
     *
     * A screen that must explain *why* it shows nothing ("never requested" vs "200 but the parser
     * found no rows" vs "bounced to the login form") cannot do that from a bare body string, so
     * the draft list reads this variant and logs the three facts.
     */
    override suspend fun getHtmlResult(path: String): ForumHttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (path.startsWith("http")) path else BASE_URL + path.removePrefix("/"))
            .header("User-Agent", MOBILE_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", BASE_URL + "forum.php")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            ForumHttpResult(
                code = response.code,
                url = response.request.url.toString(),
                body = response.body?.string().orEmpty(),
            )
        }
    }

    override suspend fun postForm(path: String, fields: Map<String, String>): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .apply { fields.forEach { (k, v) -> add(k, v) } }
            .build()
        val request = Request.Builder()
            .url(BASE_URL + path.removePrefix("/"))
            .header("User-Agent", MOBILE_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", BASE_URL + "forum.php")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            Timber.tag("ForumHttpClient").d("POST %s -> %d", path, response.code)
            response.body?.string().orEmpty()
        }
    }

    /**
     * Multipart POST used to submit a new thread with image attachments.
     *
     * Discuz's mobile new-thread form is `multipart/form-data` and carries the pictures inline as
     * `Filedata` parts, so file upload is not a separate hop. Wrapped in [Dispatchers.IO] like the
     * other entry points — the request body can be several megabytes of images.
     */
    override suspend fun postMultipart(
        path: String,
        fields: Map<String, String>,
        parts: List<MultipartPart>,
    ): String = withContext(Dispatchers.IO) {
        val body = buildMultipartBody(fields, parts)
        val request = Request.Builder()
            .url(BASE_URL + path.removePrefix("/"))
            .header("User-Agent", MOBILE_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", BASE_URL + "forum.php")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            Timber.tag("ForumHttpClient").d("POST(multipart) %s -> %d", path, response.code)
            response.body?.string().orEmpty()
        }
    }
}

/**
 * Builds the `multipart/form-data` body of a Discuz write: plain fields first, then the files.
 *
 * Extracted so the wire shape can be asserted without a socket (`ForumNewThreadMultipartTest`):
 * the new-thread contract is "the eight hidden/submitted fields **plus** one `Filedata` part per
 * image, with the file's own name and sniffed MIME type" (docs/REVERSE_SPEC.md §16). Discuz reads
 * the files from the same POST as the message; there is no separate upload endpoint.
 */
internal fun buildMultipartBody(
    fields: Map<String, String>,
    parts: List<MultipartPart>,
): MultipartBody = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .apply {
        fields.forEach { (name, value) -> addFormDataPart(name, value) }
        parts.forEach { part ->
            addFormDataPart(
                part.fieldName,
                part.fileName,
                part.bytes.toRequestBody(part.contentType.toMediaTypeOrNull()),
            )
        }
    }
    .build()

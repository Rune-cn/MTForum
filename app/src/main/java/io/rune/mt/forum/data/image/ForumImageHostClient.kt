// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.image

import io.rune.mt.forum.domain.forum.model.ForumImageHostPolicy
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import io.rune.mt.forum.domain.forum.model.ImageHostResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * The MT image host (`https://img.binmt.cc`, announced in thread 98890).
 *
 * **This is a different service from the forum.** It runs on its own host with its own session
 * cookie, so it gets its own OkHttp client with its **own** cookie jar — the forum's
 * `cQWy_2132_auth` must never be sent here (rule 14 is about host scoping, and this client simply
 * has no forum cookies to send). Nothing in this class touches [ForumHttpClient]: the forum's
 * `AcwInterceptor` is irrelevant on that host, and `ForumCookieJar` holds the session.
 *
 * Measured flow (docs/REVERSE_SPEC.md §24):
 * 1. `GET /` → the HTML carries `<meta name="csrf-token" content="…">`; the token is per session.
 * 2. `POST /upload`, `multipart/form-data`, **one** file part named `file`, with
 *    `X-CSRF-TOKEN`, `X-Requested-With: XMLHttpRequest`, `Accept: application/json` and
 *    `Referer: <host>/`.
 * 3. `200 {"status":true,…, "data":{"links":{"url":…,"bbcode":…,"thumbnail_url":…}}}`.
 *
 * Failure modes:
 * - **419** (`{"message":"CSRF token mismatch."}`) — the token went stale. The fix is to re-fetch
 *   the page and retry **once** with the new token; a second 419 is a real failure. [upload]
 *   implements exactly one refresh-retry, never a loop.
 * - `status:false` in a 200 body — a rejection; the server's own `message` is surfaced verbatim.
 * - anything unparsable — `Failure` with a reason, never an exception ("nothing happened" is the
 *   one outcome the composer must not produce).
 *
 * The whole call runs in [Dispatchers.IO] (rule 2): the HTTP `execute()` plus the byte array copy
 * would otherwise be a `NetworkOnMainThreadException` on a real device.
 */
class ForumImageHostClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    client: OkHttpClient = defaultClient(),
) : ImageHostGateway {

    /**
     * The host's client. A caller-provided client is copied with **this host's own** cookie jar so
     * that even a shared client can never leak the forum session here.
     */
    private val client: OkHttpClient = client.newBuilder()
        .cookieJar(ImageHostCookieJar())
        .build()

    private val root: String = baseUrl.trimEnd('/')

    override suspend fun upload(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): ImageHostResult = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext ImageHostResult.Failure("图片是空的，无法上传")
        val uploadName = ForumImageHostPolicy.uploadFileName(fileName, mimeType)
            ?: return@withContext ImageHostResult.Failure("图床只接受 jpg/png/gif/webp 格式")

        var token = fetchToken()
        if (token == null) {
            Timber.tag(TAG).w("token missing from %s (no CSRF meta tag)", root)
            return@withContext ImageHostResult.Failure("无法从图床页面获取令牌，请稍后再试")
        }

        var response = postUpload(token, uploadName, mimeType, bytes)
        if (response.code == HTTP_CSRF) {
            // The token is per session and expires; one refresh-retry is the measured fix.
            Timber.tag(TAG).i("upload got 419 on %s, refreshing token once", root)
            token = fetchToken()
            if (token == null) {
                return@withContext ImageHostResult.Failure("图床令牌已过期，重新获取失败")
            }
            response = postUpload(token, uploadName, mimeType, bytes)
            if (response.code == HTTP_CSRF) {
                Timber.tag(TAG).w("upload still 419 after one token refresh")
                return@withContext ImageHostResult.Failure("图床令牌校验失败（419），请稍后再试")
            }
        }
        parseUploadResponse(response.code, response.body)
    }

    /** One HTTP response kept as code + body so the caller never touches a closed response. */
    private class RawResponse(val code: Int, val body: String)

    /**
     * Fetches the per-session CSRF token from the host's home page.
     *
     * A regex, not jsoup, on purpose: this page belongs to a **different** service, and DOM
     * selectors live in `ForumHtmlParser` only (rule 3). The meta tag is the one fact needed here.
     */
    private fun fetchToken(): String? {
        val request = Request.Builder()
            .url("$root/")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                // Log host + status only: the body carries the token (rule 10).
                Timber.tag(TAG).i("GET %s -> %d", root, response.code)
                if (!response.isSuccessful) return@use null
                val html = response.body?.string().orEmpty()
                extractCsrfToken(html)
            }
        }.onFailure { Timber.tag(TAG).w(it, "token fetch from %s failed", root) }.getOrNull()
    }

    private fun postUpload(
        token: String,
        uploadName: String,
        mimeType: String,
        bytes: ByteArray,
    ): RawResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                uploadName,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url("$root/upload")
            .header("X-CSRF-TOKEN", token)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
            .header("Referer", "$root/")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                // Bytes and status only: never the token, never a cookie (rule 10).
                Timber.tag(TAG).i(
                    "POST %s/upload bytes=%d name=%s -> %d",
                    root,
                    bytes.size,
                    uploadName,
                    response.code,
                )
                RawResponse(response.code, response.body?.string().orEmpty())
            }
        }.getOrElse { failure ->
            Timber.tag(TAG).w(failure, "upload POST to %s failed", root)
            RawResponse(0, "")
        }
    }

    private fun parseUploadResponse(code: Int, body: String): ImageHostResult {
        if (code == 0) return ImageHostResult.Failure("网络错误，图片没有上传")
        if (code == HTTP_CSRF) return ImageHostResult.Failure("图床令牌校验失败（419），请稍后再试")
        if (code !in 200..299) return ImageHostResult.Failure("图床返回 HTTP $code")

        val envelope = runCatching { json.decodeFromString<UploadEnvelope>(body) }
            .onFailure { Timber.tag(TAG).w(it, "image host response is not the expected JSON") }
            .getOrNull()
            ?: return ImageHostResult.Failure("图床返回的内容无法解析")
        if (!envelope.status) {
            return ImageHostResult.Failure(
                envelope.message.ifBlank { "图床拒绝了这张图片" },
            )
        }
        val links = envelope.data?.links
        val url = links?.url?.takeIf { it.isNotBlank() }
            ?: return ImageHostResult.Failure("图床没有返回图片地址")
        return ImageHostResult.Success(
            url = url,
            // Prefer the host's own bbcode; fall back to the same shape if it ever omits it.
            bbcode = links.bbcode?.takeIf { it.isNotBlank() } ?: "[img]$url[/img]",
            thumbnailUrl = links.thumbnailUrl?.takeIf { it.isNotBlank() },
        )
    }

    /** Wire shape of `POST /upload` (only the fields the composer needs). */
    @Serializable
    private data class UploadEnvelope(
        val status: Boolean = false,
        val message: String = "",
        val data: UploadData? = null,
    )

    @Serializable
    private data class UploadData(val links: UploadLinks? = null)

    @Serializable
    private data class UploadLinks(
        val url: String = "",
        val bbcode: String = "",
        @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://img.binmt.cc"

        private const val TAG = "ImageHost"
        private const val HTTP_CSRF = 419
        private const val TIMEOUT_SECONDS = 30L

        /**
         * A desktop-ish UA: the upload endpoint answers JSON to any client, but a browser-shaped
         * UA is what the site's own uploader sends.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val TOKEN_NAME_FIRST = Regex(
            """<meta[^>]*name\s*=\s*["']csrf-token["'][^>]*content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val TOKEN_CONTENT_FIRST = Regex(
            """<meta[^>]*content\s*=\s*["']([^"']+)["'][^>]*name\s*=\s*["']csrf-token["']""",
            RegexOption.IGNORE_CASE,
        )

        private val json = Json { ignoreUnknownKeys = true }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        /** Reads `<meta name="csrf-token" content="…">`, tolerating reversed attribute order. */
        fun extractCsrfToken(html: String): String? {
            val byName = TOKEN_NAME_FIRST.find(html)?.groupValues?.getOrNull(1)
            if (!byName.isNullOrBlank()) return byName
            return TOKEN_CONTENT_FIRST.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }

        /**
         * The payload policy (formats, stored file name) lives in
         * [io.rune.mt.forum.domain.forum.model.ForumImageHostPolicy], because the composer's image
         * preparation must refuse the same files the host would — see `ComposerImagePrep`.
         */
    }
}

/**
 * The image host's own cookie store.
 *
 * Kept private to this client on purpose: the point of the separation is that the forum's session
 * cookie cannot travel to `img.binmt.cc`. Only what this host set is ever sent back to it.
 */
private class ImageHostCookieJar : CookieJar {

    private val store = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            cookies.forEach { cookie ->
                store.removeAll { it.name == cookie.name && it.domain == cookie.domain }
                store += cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(store) { store.filter { it.expiresAt > now && it.matches(url) } }
    }
}
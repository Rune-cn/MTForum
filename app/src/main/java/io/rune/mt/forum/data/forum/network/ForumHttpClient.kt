// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

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
) {

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

    suspend fun getHtml(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (path.startsWith("http")) path else BASE_URL + path.removePrefix("/"))
            .header("User-Agent", MOBILE_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", BASE_URL + "forum.php")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} for $path")
            }
            response.body?.string().orEmpty()
        }
    }

    suspend fun postForm(path: String, fields: Map<String, String>): String = withContext(Dispatchers.IO) {
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
}

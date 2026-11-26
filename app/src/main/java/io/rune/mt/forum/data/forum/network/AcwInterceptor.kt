// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import timber.log.Timber

/**
 * Detects the `acw_sc__v2` anti-bot challenger, computes the signature and replays the
 * request with the signed cookie. Without this every page is a 4 KB JS stub.
 */
class AcwInterceptor(
    private val cookieJar: ForumCookieJar,
) : Interceptor {

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val COOKIE = "acw_sc__v2"
        const val SITE_HOST = "bbs.binmt.cc"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        // Only the forum host serves the JS challenger. CDN asset/image hosts never do, and
        // peeking 64 KB of every image to look for one is pure overhead.
        if (request.url.host != SITE_HOST) return chain.proceed(request)
        var attempt = 0
        while (true) {
            val response = chain.proceed(request)
            val bodyString = response.peekBody(64 * 1024).string()
            val signature = if (AcwSignature.isChallenger(bodyString)) AcwSignature.solve(bodyString) else null
            if (signature == null || attempt >= MAX_ATTEMPTS) return response

            Timber.tag("AcwInterceptor").d("challenger on %s, retry %d", request.url, attempt + 1)
            cookieJar.setCookie(COOKIE, signature)
            response.close()
            request = request.newBuilder()
                .header("Cookie", buildCookieHeader())
                .build()
            attempt++
        }
    }

    private fun buildCookieHeader(): String =
        cookieJar.snapshot().entries.joinToString("; ") { "${it.key}=${it.value}" }
}

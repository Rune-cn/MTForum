// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Coil image loading configured for the forum CDNs.
 *
 * The avatar and attachment hosts (`cdn.binmt.cc`, `icdn.binmt.cc`, `oos.binmt.cc`) reject bare
 * requests, which is why the default Coil loader left some avatars blank. This loader sends a
 * browser User-Agent and a site Referer on every image request.
 *
 * It deliberately does NOT reuse [ForumHttpClient]'s client: that one carries the acw interceptor
 * and the session cookie jar, and images are public assets.
 */
fun buildForumImageLoader(context: PlatformContext): ImageLoader {
    val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", ForumHttpClient.MOBILE_UA)
                .header("Referer", ForumHttpClient.BASE_URL)
                .build()
            chain.proceed(request)
        }
        .build()

    return ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
        }
        .crossfade(true)
        .build()
}

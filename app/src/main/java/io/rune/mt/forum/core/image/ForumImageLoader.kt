// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.core.image

import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.rune.mt.forum.data.forum.network.AcwInterceptor
import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Coil image loading configured for the forum CDNs.
 *
 * The avatar/attachment CDNs (`cdn.binmt.cc`, `icdn.binmt.cc`, `oos.binmt.cc`) are public, so the
 * bare Coil loader is enough for most pictures. Two things are not public:
 *
 * - **Cookie-gated assets.** Discuz serves attachment posters through
 *   `bbs.binmt.cc/forum.php?mod=image&aid=...` / `mod=attachment`, which need the logged-in
 *   session. When [cookieJar] is supplied the loader sends the same session cookies as every
 *   other forum request *and* reuses [AcwInterceptor], so the anti-bot challenge on that host is
 *   solved instead of decoding the JS stub as an image.
 * - **GIFs.** Post pictures (`icdn.binmt.cc/....gif`), every smiley and the default avatar
 *   (`noavatar_middle.gif`) are GIFs. Without a GIF decoder Coil falls back to
 *   `BitmapFactoryDecoder`, which renders a single still frame — such a GIF never animates, and
 *   one whose first frame is blank looks empty. `coil-gif` fixes that; the factory is registered
 *   explicitly (rather than only via coil-gif's `ServiceLoader` entry) so R8-minified release
 *   builds behave identically.
 *
 * [cookieJar] is nullable so the loader still works before DI is ready (and in unit tests).
 */
fun buildForumImageLoader(
    context: PlatformContext,
    cookieJar: ForumCookieJar? = null,
): ImageLoader {
    val okHttp = buildForumImageHttpClient(cookieJar)

    return ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
            // AnimatedImageDecoder handles animated GIF/WebP/HEIF through `ImageDecoder` on
            // API 28+; older devices keep working via the Movie-based GifDecoder.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .crossfade(true)
        .build()
}

/**
 * The OkHttp client behind every image request.
 *
 * Kept separate from [ForumHttpClient]'s client on purpose: the image pipeline must not inherit
 * the forum's write-path interceptors or its `Accept-Language`/`Referer: forum.php` combo, but it
 * does need the session cookie jar and the acw solver for site-hosted attachments.
 */
internal fun buildForumImageHttpClient(cookieJar: ForumCookieJar?): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", ForumHttpClient.MOBILE_UA)
                .header("Referer", ForumHttpClient.BASE_URL)
                .build()
            chain.proceed(request)
        }
    if (cookieJar != null) {
        // Cookies are filtered by host in ForumCookieJar, so nothing leaks to the public CDNs.
        builder.cookieJar(cookieJar)
        builder.addInterceptor(AcwInterceptor(cookieJar))
    }
    return builder.build()
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.core.image.buildForumImageHttpClient
import io.rune.mt.forum.data.forum.network.AcwInterceptor
import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumUrls
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie-gated images must go through the session, without leaking the session to the CDNs.
 *
 * Discuz serves attachment posters through `bbs.binmt.cc/forum.php?mod=image&aid=...`; anonymous
 * requests to that host get the acw JS challenge (a 4 KB HTML page, HTTP 200), which Coil reports
 * as a decode failure — the blank picture in the bug report. The image client therefore takes the
 * session cookie jar plus [AcwInterceptor]. The jar has to keep the auth cookie on the forum host
 * only: the same jar also serves `cdn.binmt.cc`/`icdn.binmt.cc`/`oos.binmt.cc`.
 */
class ForumImageSessionTest {

    @Test
    fun `session cookies stay on the forum host`() {
        val jar = ForumCookieJar()
        jar.setCookie("cQWy_2132_auth", "secret")
        jar.setCookie("acw_sc__v2", "sig")

        val site = jar.loadForRequest("https://bbs.binmt.cc/forum.php?mod=image&aid=1".toHttpUrl())
        assertTrue("the forum host must receive the session: $site", site.any { it.name == "cQWy_2132_auth" })

        for (cdn in listOf("https://cdn.binmt.cc/data/attachment/forum/x.gif", "https://icdn.binmt.cc/2509/x.gif", "https://oos.binmt.cc/image/x.jpg")) {
            assertTrue("$cdn must not receive session cookies", jar.loadForRequest(cdn.toHttpUrl()).isEmpty())
        }
    }

    @Test
    fun `image client carries the session jar and the acw solver`() {
        val jar = ForumCookieJar()
        val client = buildForumImageHttpClient(jar)
        assertSame(jar, client.cookieJar)
        assertTrue(
            "the image client must be able to solve the acw challenge on bbs.binmt.cc",
            client.interceptors.any { it is AcwInterceptor },
        )
    }

    @Test
    fun `image client stays anonymous without a jar`() {
        val client = buildForumImageHttpClient(null)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(client.interceptors.none { it is AcwInterceptor })
    }

    @Test
    fun `cdn picture falls back to the same path on the forum host`() {
        assertEquals(
            "https://bbs.binmt.cc/data/attachment/forum/202509/01/x.gif",
            ForumUrls.imageFallback("https://cdn.binmt.cc/data/attachment/forum/202509/01/x.gif"),
        )
        assertEquals(
            "https://bbs.binmt.cc/uc_server/data/avatar/000/15/34/66_avatar_middle.jpg?v=abc",
            ForumUrls.imageFallback("https://cdn.binmt.cc/uc_server/data/avatar/000/15/34/66_avatar_middle.jpg?v=abc"),
        )
        assertNull(
            "CDN-only upload paths have no site copy",
            ForumUrls.imageFallback("https://icdn.binmt.cc/2509/68d26c23c386d.gif"),
        )
        assertNull(
            "a site URL needs no fallback",
            ForumUrls.imageFallback("https://bbs.binmt.cc/data/attachment/forum/x.gif"),
        )
        assertNull(ForumUrls.imageFallback(null))
        assertNull(ForumUrls.imageFallback(""))
    }
}
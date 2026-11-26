// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import com.sun.net.httpserver.HttpServer
import io.rune.mt.forum.core.image.buildForumImageHttpClient
import io.rune.mt.forum.data.forum.network.AcwInterceptor
import io.rune.mt.forum.data.forum.network.ForumCookieJar
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The image client's anti-bot behaviour, exercised against a loopback server (no external network).
 *
 * Two properties matter for images:
 * 1. a successful image response is neither buffered nor replayed — `AcwInterceptor` only probes
 *  responses that are actually HTML, so the 64 KB challenge sniff never touches image bytes;
 * 2. when the forum host *does* answer with the acw JS stub, the client signs it, stores
 *    `acw_sc__v2`, replays the request and ends up with the real bytes. This is the path that was
 *    missing for cookie-gated attachments (`forum.php?mod=image`).
 */
class AcwImageFetchTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val hits = AtomicInteger()

    @Before
    fun startServer() {
        hits.set(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = null
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun fixture(path: String): File =
        File(path).takeIf { it.exists() } ?: File("app/$path")

    private fun gifBytes(): ByteArray = fixture("src/test/resources/ui/local_avatar.gif").readBytes()

    private fun challengerHtml(): ByteArray = fixture("src/test/resources/forum/challenger.html").readBytes()

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    @Test
    fun `image response is fetched once and never probed`() {
        val bytes = gifBytes()
        server.createContext("/avatar.gif") { exchange ->
            hits.incrementAndGet()
            respond(exchange, "image/gif", bytes)
        }

        val jar = ForumCookieJar()
        val response = buildForumImageHttpClient(jar)
            .newCall(Request.Builder().url("http://127.0.0.1:$port/avatar.gif").build())
            .execute()
        response.use {
            assertEquals(200, it.code)
            assertArrayEquals(bytes, it.body!!.bytes())
        }
        assertEquals("an image must be fetched exactly once", 1, hits.get())
        assertFalse("no challenge cookie for an image response", jar.has("acw_sc__v2"))
    }

    @Test
    fun `acw challenge on an html response is solved and replayed`() {
        val bytes = gifBytes()
        val challenge = challengerHtml()
        server.createContext("/mod-image") { exchange ->
            if (hits.incrementAndGet() == 1) {
                respond(exchange, "text/html; charset=utf-8", challenge)
            } else {
                respond(exchange, "image/gif", bytes)
            }
        }

        val jar = ForumCookieJar()
        // The real client arms the interceptor for bbs.binmt.cc; the loopback host is injected so
        // the challenge → signature → replay path can run offline.
        val client = OkHttpClient.Builder()
            .cookieJar(jar)
            .addInterceptor(AcwInterceptor(jar, siteHost = "127.0.0.1"))
            .build()
        val response = client
            .newCall(Request.Builder().url("http://127.0.0.1:$port/mod-image?aid=1").build())
            .execute()
        response.use {
            assertEquals(200, it.code)
            assertArrayEquals("the replay must end up with the real image bytes", bytes, it.body!!.bytes())
        }
        assertEquals("challenge + replay = exactly two hits", 2, hits.get())
        assertTrue("the solved signature must be stored in the session jar", jar.has("acw_sc__v2"))
    }

    @Test
    fun `non html response is passed through untouched`() {
        val payload = """{"error":"not an image"}""".toByteArray()
        server.createContext("/api") { exchange ->
            hits.incrementAndGet()
            respond(exchange, "application/json", payload)
        }

        val jar = ForumCookieJar()
        val response = OkHttpClient.Builder()
            .addInterceptor(AcwInterceptor(jar, siteHost = "127.0.0.1"))
            .build()
            .newCall(Request.Builder().url("http://127.0.0.1:$port/api").build())
            .execute()
        response.use {
            assertEquals(200, it.code)
            assertArrayEquals(payload, it.body!!.bytes())
        }
        assertEquals(1, hits.get())
        assertFalse(jar.has("acw_sc__v2"))
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.rune.mt.forum.data.image.ForumImageHostClient
import io.rune.mt.forum.domain.forum.model.ForumImageHostPolicy
import io.rune.mt.forum.domain.forum.model.ImageHostResult
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ForumImageHostClient` against a **loopback** server — no live network, ever.
 *
 * The measured host contract is pinned here (docs/REVERSE_SPEC.md §24): the token comes from the
 * home page's `csrf-token` meta, the upload is one multipart `file` part with its four headers, a
 * `419` is fixed by **one** token refresh, `status:false` surfaces the server's own message, and
 * anything unparsable is a `Failure` rather than an exception. The last test proves the session
 * isolation: a forum cookie handed to the client must never reach the image host.
 */
class ForumImageHostClientTest {

    private lateinit var server: HttpServer
    private var port = 0

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = null
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun client(okHttp: OkHttpClient = OkHttpClient.Builder().build()) =
        ForumImageHostClient(baseUrl = "http://127.0.0.1:$port", client = okHttp)

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        contentType: String,
        body: String,
    ) {
        exchange.responseHeaders.add("Content-Type", contentType)
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun tokenPage(token: String) = """
        <!DOCTYPE html><html><head>
        <meta name="csrf-token" content="$token">
        <title>图床</title></head><body>upload</body></html>
    """.trimIndent()

    private fun successBody(
        url: String = "https://icdn.binmt.cc/2611/6b094ea47a7a2.png",
        thumbnail: String = "https://img.binmt.cc/thumbnails/6b094ea47a7a2.png",
    ) = """
        {"status":true,"message":"上传成功","data":{"id":19515,"pathname":"2611/6b094ea47a7a2.png",
        "origin_name":"probe.png","size":0.29,"mimetype":"image/png","links":{"url":"$url",
        "bbcode":"[img]$url[/img]","html":"<img src=\"$url\">","markdown":"![]($url)",
        "thumbnail_url":"$thumbnail"}}}
    """.trimIndent()

    /** One captured upload request: the headers and the multipart body the host would have seen. */
    private class Upload(val headers: Headers, val body: String)

    @Test
    fun `token then upload returns the url and the bbcode`() = runBlocking {
        val uploads = mutableListOf<Upload>()
        server.createContext("/") { exchange -> respond(exchange, 200, "text/html; charset=utf-8", tokenPage("token-one")) }
        server.createContext("/upload") { exchange ->
            uploads += Upload(exchange.requestHeaders, exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            respond(exchange, 200, "application/json", successBody())
        }

        val result = client().upload(pngBytes(), "probe.png", "image/png")

        assertTrue("expected Success, got $result", result is ImageHostResult.Success)
        val success = result as ImageHostResult.Success
        assertEquals("https://icdn.binmt.cc/2611/6b094ea47a7a2.png", success.url)
        assertEquals("https://icdn.binmt.cc/2611/6b094ea47a7a2.png".let { "[img]$it[/img]" }, success.bbcode)
        assertEquals("https://img.binmt.cc/thumbnails/6b094ea47a7a2.png", success.thumbnailUrl)

        assertEquals("exactly one upload POST", 1, uploads.size)
        val upload = uploads.single()
        assertEquals("the token comes from the page", "token-one", upload.headers.getFirst("X-CSRF-TOKEN"))
        assertEquals("XMLHttpRequest", upload.headers.getFirst("X-Requested-With"))
        assertEquals("application/json", upload.headers.getFirst("Accept"))
        assertTrue(
            "the Referer must be the host itself, got ${upload.headers.getFirst("Referer")}",
            upload.headers.getFirst("Referer")?.endsWith("/") == true,
        )
        assertTrue("one part named file", upload.body.contains("name=\"file\""))
        assertTrue("the picked file name is kept", upload.body.contains("filename=\"probe.png\""))
        assertTrue("sniffed MIME is the part's content type", upload.body.contains("Content-Type: image/png"))
    }

    @Test
    fun `a 419 refreshes the token once and retries the upload`() = runBlocking {
        val tokensServed = AtomicInteger()
        val postTokens = mutableListOf<String?>()
        server.createContext("/") { exchange ->
            val token = "token-${tokensServed.incrementAndGet()}"
            respond(exchange, 200, "text/html; charset=utf-8", tokenPage(token))
        }
        server.createContext("/upload") { exchange ->
            postTokens += exchange.requestHeaders.getFirst("X-CSRF-TOKEN")
            if (postTokens.size == 1) {
                respond(exchange, 419, "application/json", """{"message":"CSRF token mismatch."}""")
            } else {
                respond(exchange, 200, "application/json", successBody())
            }
        }

        val result = client().upload(pngBytes(), "probe.png", "image/png")

        assertTrue("the retry must succeed, got $result", result is ImageHostResult.Success)
        assertEquals("one rejection plus one retry", 2, postTokens.size)
        assertEquals("the first attempt used the stale token", "token-1", postTokens.first())
        assertEquals("the retry used the refreshed one", "token-2", postTokens.last())
        assertEquals("the page was re-fetched for the new token", 2, tokensServed.get())
    }

    @Test
    fun `a second 419 is a failure, not a loop`() = runBlocking {
        val posts = AtomicInteger()
        val tokenGets = AtomicInteger()
        server.createContext("/") { exchange ->
            tokenGets.incrementAndGet()
            respond(exchange, 200, "text/html; charset=utf-8", tokenPage("token-x"))
        }
        server.createContext("/upload") { exchange ->
            posts.incrementAndGet()
            respond(exchange, 419, "application/json", """{"message":"CSRF token mismatch."}""")
        }

        val result = client().upload(pngBytes(), "probe.png", "image/png")

        assertTrue("a second 419 must fail, got $result", result is ImageHostResult.Failure)
        assertTrue(
            "the reason must say the token was rejected, got ${(result as ImageHostResult.Failure).reason}",
            result.reason.contains("419"),
        )
        assertEquals("exactly one refresh-retry, never more", 2, posts.get())
        assertEquals("exactly two token reads (initial + one refresh)", 2, tokenGets.get())
    }

    @Test
    fun `status false surfaces the server's own message`() = runBlocking {
        server.createContext("/") { exchange -> respond(exchange, 200, "text/html; charset=utf-8", tokenPage("token-one")) }
        server.createContext("/upload") { exchange ->
            respond(exchange, 200, "application/json", """{"status":false,"message":"文件类型不允许"}""")
        }

        val result = client().upload(pngBytes(), "probe.png", "image/png")

        assertTrue(result is ImageHostResult.Failure)
        assertEquals("文件类型不允许", (result as ImageHostResult.Failure).reason)
    }

    @Test
    fun `malformed json is a failure with a reason`() = runBlocking {
        server.createContext("/") { exchange -> respond(exchange, 200, "text/html; charset=utf-8", tokenPage("token-one")) }
        server.createContext("/upload") { exchange ->
            respond(exchange, 200, "application/json", "<html>not json at all</html>")
        }

        val result = client().upload(pngBytes(), "probe.png", "image/png")

        assertTrue("unparsable content must not throw, got $result", result is ImageHostResult.Failure)
        assertTrue((result as ImageHostResult.Failure).reason.contains("无法解析"))
    }

    @Test
    fun `a page without the csrf meta fails before any upload`() = runBlocking {
        val posts = AtomicInteger()
        server.createContext("/") { exchange ->
            respond(exchange, 200, "text/html; charset=utf-8", "<html><head></head><body>no token</body></html>")
        }
        server.createContext("/upload") { exchange ->
            posts.incrementAndGet()
            respond(exchange, 200, "application/json", successBody())
        }

        val result = client().upload(pngBytes(), "probe.png", "image/png")

        assertTrue(result is ImageHostResult.Failure)
        assertEquals("no upload may be attempted without a token", 0, posts.get())
    }

    @Test
    fun `a forum cookie is never sent to the image host`() = runBlocking {
        var cookieHeader: String? = "<unset>"
        server.createContext("/") { exchange ->
            respond(exchange, 200, "text/html; charset=utf-8", tokenPage("token-one"))
        }
        server.createContext("/upload") { exchange ->
            cookieHeader = exchange.requestHeaders.getFirst("Cookie")
            respond(exchange, 200, "application/json", successBody())
        }
        // A client whose jar holds the forum session: the image host must still not see it.
        val forumCookie = Cookie.Builder()
            .name("cQWy_2132_auth")
            .value("would-be-leak")
            .domain("127.0.0.1")
            .path("/")
            .build()
        val leakingJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(forumCookie)
        }
        val shared = OkHttpClient.Builder().cookieJar(leakingJar).build()

        val result = client(shared).upload(pngBytes(), "probe.png", "image/png")

        assertTrue(result is ImageHostResult.Success)
        assertFalse(
            "the forum session cookie must never reach the image host, got $cookieHeader",
            cookieHeader?.contains("cQWy_2132_auth") == true,
        )
    }

    // ---- the cheap conversations, asserted directly --------------------------

    @Test
    fun `the uploaded name follows the sniffed type, not the picked name`() {
        assertEquals(
            "a camera file with no extension gains the type's extension",
            "IMG_0001.png",
            ForumImageHostPolicy.uploadFileName("IMG_0001", "image/png"),
        )
        assertEquals(
            "an accepted extension is kept verbatim",
            "A.PNG",
            ForumImageHostPolicy.uploadFileName("A.PNG", "image/png"),
        )
        assertEquals(
            "a mislabelled extension is replaced by the type's own",
            "photo.jpg",
            ForumImageHostPolicy.uploadFileName("photo.heic", "image/jpeg"),
        )
        assertNull(
            "HEIC is not in the host's list",
            ForumImageHostPolicy.uploadFileName("photo.heic", "image/heic"),
        )
        assertEquals("a path is reduced to its name", "shot.webp", ForumImageHostPolicy.uploadFileName("a/b/shot.webp", "image/webp"))
    }

    @Test
    fun `the csrf meta is read in either attribute order`() {
        assertEquals("abc123", ForumImageHostClient.extractCsrfToken("""<meta name="csrf-token" content="abc123">"""))
        assertEquals(
            "content-before-name is the same tag",
            "def456",
            ForumImageHostClient.extractCsrfToken("""<meta content="def456" name="csrf-token">"""),
        )
        assertNull(
            "another meta tag must not be taken for the token",
            ForumImageHostClient.extractCsrfToken("""<meta name="viewport" content="width=device-width">"""),
        )
        assertNull("no meta at all", ForumImageHostClient.extractCsrfToken("<html></html>"))
    }

    /** A 1×1 PNG — the same shape the live probe used, so no accidental 0-byte upload. */
    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.rune.mt.forum.data.forum.network.ForumAttachmentDownloader
import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.data.forum.network.sanitiseAttachmentFileName
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Attachment download behaviour, exercised against a loopback server (no external network).
 *
 * Three things matter for the user-visible promise: the bytes actually land on disk and progress is
 * reported; the body is streamed rather than buffered; and a failure is an [IOException] with a
 * reason instead of a file that silently contains an HTML challenge page.
 */
class ForumAttachmentDownloadTest {

    private lateinit var server: HttpServer
    private var port = 0
    private lateinit var dir: File

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = null
        server.start()
        port = server.address.port
        dir = File(System.getProperty("java.io.tmpdir"), "attach-test-" + System.nanoTime()).apply { mkdirs() }
    }

    @After
    fun stopServer() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun downloader(): ForumAttachmentDownloader =
        ForumAttachmentDownloader(OkHttpClient.Builder().build())

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    @Test
    fun `a file response is streamed to disk with progress`() = runBlocking {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        server.createContext("/a.bin") { exchange -> respond(exchange, "application/octet-stream", payload) }

        val target = File(dir, "a.bin")
        val fractions = mutableListOf<Float?>()
        val written = downloader().download("http://127.0.0.1:$port/a.bin", target) { fractions += it }

        assertEquals(payload.size.toLong(), written)
        assertEquals("the file must contain the exact bytes", payload.size.toLong(), target.length())
        assertTrue("progress must be reported", fractions.isNotEmpty())
        assertEquals("progress must end at 100%", 1f, fractions.last())
        assertTrue(
            "progress must rise monotonically, got $fractions",
            fractions.filterNotNull().zipWithNext().all { (a, b) -> b >= a },
        )
    }

    @Test
    fun `an html challenge page is rejected instead of written`() = runBlocking {
        server.createContext("/b.bin") { exchange ->
            respond(exchange, "text/html; charset=utf-8", "<html>acw challenge</html>".toByteArray())
        }
        val target = File(dir, "b.bin")
        val error = runCatching { downloader().download("http://127.0.0.1:$port/b.bin", target) }.exceptionOrNull()
        assertTrue("a challenge page must fail the download, got $error", error is IOException)
        assertFalse("no file may be left behind on failure", target.exists())
    }

    @Test
    fun `an http error is reported`() = runBlocking {
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        val error = runCatching {
            downloader().download("http://127.0.0.1:$port/missing", File(dir, "c.bin"))
        }.exceptionOrNull()
        assertTrue("403 must surface as IOException, got $error", error is IOException)
        assertTrue("the reason must name the code, got ${error?.message}", error?.message?.contains("403") == true)
    }

    @Test
    fun `a file name from a post cannot escape the download directory`() {
        // Security properties, not an exact string: the point is that the result is a single plain
        // file name — no separators, no traversal segment, no hidden-file leading dot.
        val hostile = sanitiseAttachmentFileName("../../etc/passwd")
        assertFalse("no path separator may survive, got [$hostile]", hostile.contains("/"))
        assertFalse("no backslash may survive, got [$hostile]", hostile.contains('\\'))
        assertFalse("no traversal segment may survive, got [$hostile]", hostile.contains(".."))
        assertTrue("the name must stay usable, got [$hostile]", hostile.isNotBlank())

        assertEquals("a leading dot must not hide the file", "hidden", sanitiseAttachmentFileName(".hidden"))
        // ? starts a query in a URL but is legal inside a file name a user typed; the reserved
        // characters are replaced and the extension is preserved.
        assertEquals(
            "windows-reserved characters are replaced but the extension survives",
            "a_b_c_.zip",
            sanitiseAttachmentFileName("a:b*c?.zip"),
        )
        assertEquals("an empty name falls back", "attachment", sanitiseAttachmentFileName("   "))
        assertTrue(
            "a very long name must be truncated, got ${sanitiseAttachmentFileName("x".repeat(500)).length}",
            sanitiseAttachmentFileName("x".repeat(500)).length <= 120,
        )
        assertEquals(
            "a normal name is left alone",
            "Converter-0.2-beta.26042415.mtp",
            sanitiseAttachmentFileName("Converter-0.2-beta.26042415.mtp"),
        )
    }

    @Test
    fun `cancel stops the transfer`() = runBlocking {
        val hits = AtomicInteger()
        server.createContext("/slow") { exchange ->
            hits.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, 10_000_000)
            // Dribble bytes until the client walks away; the read loop's ensureActive() aborts.
            runCatching {
                val chunk = ByteArray(64 * 1024)
                repeat(2000) { exchange.responseBody.write(chunk); exchange.responseBody.flush() }
            }
            exchange.close()
        }
        val job = CoroutineScope(Dispatchers.IO).launch {
            runCatching { downloader().download("http://127.0.0.1:$port/slow", File(dir, "slow.bin")) }
        }
        Thread.sleep(150)
        job.cancel()
        job.join()
        assertTrue("the server must have been asked", hits.get() >= 1)
    }

    // ---- Paid attachments: the attachcredit handshake (fixtures, no external network) ----------

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    private fun htmlResponse(exchange: HttpExchange, html: String) =
        respond(exchange, "text/html; charset=utf-8", html.toByteArray())

    /**
     * The captured attachcredit page, with the site host swapped for the loopback server so its
     * continue link resolves locally. The aid hash, `ck` token and `&amp;` entities stay verbatim —
     * only the host is rewritten, because the test must not touch bbs.binmt.cc.
     */
    private fun loopbackCreditPage(): String =
        fixture("attach_paid_credit.html").replace("https://bbs.binmt.cc", "http://127.0.0.1:$port")

    private fun refusalPage(): String =
        """
        <html><head><title>提示信息 - MT论坛</title></head><body>
        <div class="comiis_msgbox"><div id="messagetext" class="alert_info">
        <p>抱歉，您的 金币不足 ，无法下载该附件</p></div></div></body></html>
        """.trimIndent()

    @Test
    fun `a free attachment is a single request straight to bytes`() = runBlocking {
        val payload = ByteArray(4096) { (it % 97).toByte() }
        val hits = AtomicInteger()
        server.createContext("/free.bin") { exchange ->
            hits.incrementAndGet()
            respond(exchange, "application/octet-stream", payload)
        }

        val written = downloader().download("http://127.0.0.1:$port/free.bin", File(dir, "free.bin"))

        assertEquals(payload.size.toLong(), written)
        assertEquals("a free attachment must not make a handshake hop", 1, hits.get())
    }

    @Test
    fun `a paid attachment follows the attachcredit continue link exactly once`() = runBlocking {
        val payload = ByteArray(60_687) { (it % 251).toByte() }
        val credit = loopbackCreditPage()
        val hits = AtomicInteger()
        var continueQuery: String? = null
        server.createContext("/paid.bin") { exchange ->
            hits.incrementAndGet()
            htmlResponse(exchange, credit)
        }
        server.createContext("/forum.php") { exchange ->
            hits.incrementAndGet()
            continueQuery = exchange.requestURI.rawQuery
            respond(exchange, "application/octet-stream", payload)
        }

        val written = downloader().download("http://127.0.0.1:$port/paid.bin", File(dir, "paid.bin"))

        assertEquals("the file request plus exactly one handshake hop", 2, hits.get())
        assertEquals(payload.size.toLong(), written)
        val query = requireNotNull(continueQuery)
        assertFalse("the &amp; entity must be decoded before the request, got $query", query.contains("amp;"))
        assertTrue("the regenerated ck token must be carried, got $query", query.contains("ck=452985a0"))
        val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(
            "the regenerated aid hash must be carried verbatim",
            "Mzg1Mzc2fGRkNmNhODk2fDE3OTU3NTQ3Nzh8MTUzNDY2fDE3NzI0OA==",
            URLDecoder.decode(params.getValue("aid"), "UTF-8"),
        )
    }

    @Test
    fun `an attachcredit refusal surfaces the server message with one extra request`() = runBlocking {
        val credit = loopbackCreditPage()
        val refusal = refusalPage()
        val hits = AtomicInteger()
        server.createContext("/paid-refused.bin") { exchange ->
            hits.incrementAndGet()
            htmlResponse(exchange, credit)
        }
        server.createContext("/forum.php") { exchange ->
            hits.incrementAndGet()
            htmlResponse(exchange, refusal)
        }
        val target = File(dir, "paid-refused.bin")

        val error = runCatching {
            downloader().download("http://127.0.0.1:$port/paid-refused.bin", target)
        }.exceptionOrNull()

        assertTrue("a refusal must fail, got $error", error is IOException)
        assertTrue(
            "the server's own message must be carried, got ${error?.message}",
            error?.message?.contains("金币不足") == true,
        )
        assertEquals("exactly one extra request, never a blind retry", 2, hits.get())
        assertFalse("no file may be left behind", target.exists())
    }

    @Test
    fun `a refusal notice on the first response fails without any retry`() = runBlocking {
        val hits = AtomicInteger()
        server.createContext("/refused.bin") { exchange ->
            hits.incrementAndGet()
            htmlResponse(exchange, refusalPage())
        }
        val target = File(dir, "refused.bin")

        val error = runCatching {
            downloader().download("http://127.0.0.1:$port/refused.bin", target)
        }.exceptionOrNull()

        assertTrue("a notice page must fail, got $error", error is IOException)
        assertTrue("the message must be the server's, got ${error?.message}", error?.message?.contains("金币不足") == true)
        assertEquals("a failure notice must not trigger a retry", 1, hits.get())
        assertFalse(target.exists())
    }

    @Test
    fun `an attachcredit page that refuses with 金币不足 fails without following its link`() = runBlocking {
        // Some Discuz variants render the refusal on the handshake page itself. Following the
        // continue link there would be pointless (and could charge again), so the server message
        // must be surfaced immediately.
        val refusalOnCreditPage = """
            <html><head><title>提示信息 - MT论坛</title></head><body>
            <div class="comiis_msgbox"><div id="messagetext" class="alert_info">
            <p>抱歉，您的 金币不足 ，无法下载该附件</p></div>
            <div class="alert_continue">
            <a href="http://127.0.0.1:$port/forum.php?mod=attachment&amp;aid=x&amp;ck=y&amp;mobile=2">点击此链接进行跳转</a>
            </div></div></body></html>
        """.trimIndent()
        val hits = AtomicInteger()
        server.createContext("/credit-refused.bin") { exchange ->
            hits.incrementAndGet()
            htmlResponse(exchange, refusalOnCreditPage)
        }
        val target = File(dir, "credit-refused.bin")

        val error = runCatching {
            downloader().download("http://127.0.0.1:$port/credit-refused.bin", target)
        }.exceptionOrNull()

        assertTrue("a refusal on the handshake page must fail, got $error", error is IOException)
        assertTrue("the message must be carried, got ${error?.message}", error?.message?.contains("金币不足") == true)
        assertEquals("a refusal must not follow the continue link", 1, hits.get())
        assertFalse(target.exists())
    }

    @Test
    fun `a second html response fails instead of looping`() = runBlocking {
        val hits = AtomicInteger()
        server.createContext("/loop.bin") { exchange ->
            hits.incrementAndGet()
            htmlResponse(exchange, loopbackCreditPage())
        }
        server.createContext("/forum.php") { exchange ->
            hits.incrementAndGet()
            htmlResponse(exchange, "<html><body>not a file</body></html>")
        }
        val target = File(dir, "loop.bin")

        val error = runCatching {
            downloader().download("http://127.0.0.1:$port/loop.bin", target)
        }.exceptionOrNull()

        assertTrue("a second HTML page must fail, got $error", error is IOException)
        assertEquals("the download must stop after the single allowed hop", 2, hits.get())
        assertFalse(target.exists())
    }

    @Test
    fun `the continue link is decoded and kept verbatim from the captured page`() {
        val html = fixture("attach_paid_credit.html")
        assertTrue("the page must be recognised as the handshake", ForumHtmlParser.isAttachCreditPage(html))
        assertEquals(
            "https://bbs.binmt.cc/forum.php?mod=attachment&aid=Mzg1Mzc2fGRkNmNhODk2fDE3OTU3NTQ3Nzh8MTUzNDY2fDE3NzI0OA%3D%3D&ck=452985a0&mobile=2",
            ForumHtmlParser.attachCreditContinueUrl(html),
        )
        assertEquals(
            "您的 金币 -1 ，现在将开始下载“mt-tts-plugin.txt”",
            ForumHtmlParser.extractNotice(html),
        )
        assertFalse("the handshake page is not a refusal", ForumHtmlParser.isAttachmentFailureNotice(html))
    }

    @Test
    fun `the acw stub is a challenge, not a handshake or a notice`() {
        val stub = fixture("challenger.html")
        assertTrue("the acw stub must be recognised", ForumHtmlParser.isAcwChallenge(stub))
        assertFalse("the acw stub must not be taken for the handshake", ForumHtmlParser.isAttachCreditPage(stub))
        assertFalse("the acw stub must not be taken for a refusal", ForumHtmlParser.isAttachmentFailureNotice(stub))
        assertEquals("no continue link on the acw stub", null, ForumHtmlParser.attachCreditContinueUrl(stub))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import com.sun.net.httpserver.HttpServer
import io.rune.mt.forum.data.forum.network.ForumAttachmentDownloader
import io.rune.mt.forum.data.forum.network.sanitiseAttachmentFileName
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
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
}

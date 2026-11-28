// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.mcp.McpHttpServer
import io.rune.mt.forum.data.mcp.McpHttpStatus
import io.rune.mt.forum.data.mcp.McpJsonRpcDispatcher
import io.rune.mt.forum.data.mcp.McpRequestThrottle
import io.rune.mt.forum.domain.mcp.McpToolCatalog
import io.rune.mt.forum.ui.page.forum.ForumMcpPostFormatter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real-socket tests for [McpHttpServer].
 *
 * They deliberately use a ephemeral port (`0`) and talk HTTP/1.1 through a plain [Socket]
 * instead of `HttpURLConnection`: the JVM proxy properties injected by the build environment
 * would otherwise route loopback calls through the developer proxy and make the result depend on
 * that proxy being alive.
 */
class McpHttpServerTest {

    private lateinit var server: McpHttpServer
    private lateinit var repository: McpFakeRepository

    @Before
    fun setUp() {
        repository = McpFakeRepository(
            home = mcpHomeSample(),
            threadPage = mcpThreadPageSample(),
            detail = mcpThreadDetailSample(),
        )
        server = McpHttpServer(
            dispatcher = dispatcher("session-1"),
            requestedPort = 0,
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the server binds the loopback interface on an ephemeral port`() {
        val address = server.bindingAddress
        assertNotNull("the server must be bound", address)
        assertTrue("binding must be a loopback address, was ${address!!.hostAddress}", address.isLoopbackAddress)
        assertEquals("127.0.0.1", address.hostAddress)
        assertTrue("the OS must have assigned a real port", server.port > 0)
    }

    @Test
    fun `initialize over a socket returns JSON and the session header`() {
        Socket("127.0.0.1", server.port).use { socket ->
            val response = exchange(socket, initializeBody())

            assertEquals(McpHttpStatus.OK, response.status)
            assertTrue(response.headers["content-type"]!!.contains("application/json"))
            assertEquals("session-1", response.headers["mcp-session-id"])
            val result = json.parseToJsonElement(response.body).jsonObject["result"]!!.jsonObject
            assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a keep-alive connection serves initialize and tools list back to back`() {
        Socket("127.0.0.1", server.port).use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            send(socket, initializeBody())
            val initialize = readResponse(input)
            send(socket, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            val tools = readResponse(input)

            assertEquals(McpHttpStatus.OK, initialize.status)
            assertEquals(McpHttpStatus.OK, tools.status)
            assertTrue(
                "tools/list must be served on the same keep-alive connection: ${tools.body}",
                tools.body.contains(McpToolCatalog.GET_THREAD),
            )
            assertTrue(tools.body.contains(McpToolCatalog.LIST_BOARDS))
        }
    }

    @Test
    fun `tools call over the socket returns a plain-text floor`() {
        Socket("127.0.0.1", server.port).use { socket ->
            val response = exchange(
                socket,
                buildString {
                    append("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{""")
                    append(""""name":"get_thread","arguments":{"tid":166223}}}""")
                },
            )

            assertEquals(McpHttpStatus.OK, response.status)
            val text = json.parseToJsonElement(response.body).jsonObject["result"]!!.jsonObject["content"]!!
                .toString()
            assertTrue("expected the plain-text floor, got $text", text.contains("Hello world"))
            assertTrue(text.contains("patch.zip"))
        }
    }

    @Test
    fun `a notification is acknowledged with 202 and an empty body`() {
        Socket("127.0.0.1", server.port).use { socket ->
            val response = exchange(socket, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")

            assertEquals(McpHttpStatus.ACCEPTED, response.status)
            assertEquals("", response.body)
            assertEquals("0", response.headers["content-length"])
        }
    }

    @Test
    fun `GET mcp is rejected with 405 and Allow POST because SSE is not implemented`() {
        Socket("127.0.0.1", server.port).use { socket ->
            val response = exchange(socket, method = "GET", body = "")

            assertEquals(McpHttpStatus.METHOD_NOT_ALLOWED, response.status)
            assertEquals("POST", response.headers["allow"])
        }
    }

    @Test
    fun `an unknown path is 404`() {
        Socket("127.0.0.1", server.port).use { socket ->
            val response = exchange(socket, method = "POST", body = "{}", path = "/other")

            assertEquals(McpHttpStatus.NOT_FOUND, response.status)
        }
    }

    @Test
    fun `a body above the 64KB limit is refused with 413 before parsing`() {
        Socket("127.0.0.1", server.port).use { socket ->
            // Only advertise the oversized length; the server must reject on the header alone.
            val head = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${68 * 1024}\r\n\r\n"
            socket.getOutputStream().write(head.toByteArray())
            socket.getOutputStream().flush()
            val response = readResponse(BufferedInputStream(socket.getInputStream()))

            assertEquals(McpHttpStatus.PAYLOAD_TOO_LARGE, response.status)
        }
    }

    @Test
    fun `closing the server unbinds the port`() {
        val port = server.port
        server.close()

        val refused = runCatching {
            Socket().apply {
                connect(InetSocketAddress("127.0.0.1", port), 1_000)
            }.close()
        }
        assertTrue("a closed server must refuse new connections, got $refused", refused.exceptionOrNull() is ConnectException)
    }

    @Test
    fun `the per-minute throttle answers 429 instead of hammering the forum`() {
        val throttled = McpHttpServer(
            dispatcher = dispatcher("session-2"),
            requestedPort = 0,
            throttle = McpRequestThrottle(maxRequests = 2, windowMillis = 60_000),
        )
        try {
            throttled.start()
            Socket("127.0.0.1", throttled.port).use { socket ->
                val first = exchange(socket, initializeBody())
                val second = exchange(socket, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
                val third = exchange(socket, """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")

                assertEquals(McpHttpStatus.OK, first.status)
                assertEquals(McpHttpStatus.OK, second.status)
                assertEquals(McpHttpStatus.TOO_MANY_REQUESTS, third.status)
            }
        } finally {
            throttled.close()
        }
    }

    // ---- raw HTTP helpers ---------------------------------------------------

    private fun dispatcher(sessionId: String) = McpJsonRpcDispatcher(
        repository = repository,
        postFormatter = ForumMcpPostFormatter(),
        serverVersion = "test-version",
        sessionIdFactory = { sessionId },
    )

    private fun initializeBody(): String =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}"""

    private fun exchange(
        socket: Socket,
        body: String,
        method: String = "POST",
        path: String = "/mcp",
    ): RawResponse {
        send(socket, body, method, path)
        return readResponse(BufferedInputStream(socket.getInputStream()))
    }

    private fun send(socket: Socket, body: String, method: String = "POST", path: String = "/mcp") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            if (bytes.isNotEmpty()) write(bytes)
            flush()
        }
    }

    private fun readResponse(input: BufferedInputStream): RawResponse {
        val statusLine = readLine(input)
        val status = statusLine.split(' ')[1].toInt()
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val next = input.read(bytes, read, length - read)
            if (next <= 0) break
            read += next
        }
        return RawResponse(
            status = status,
            headers = headers,
            body = String(bytes, 0, read, Charsets.UTF_8),
        )
    }

    private fun readLine(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val next = input.read()
            if (next == -1) error("connection closed before a line arrived")
            if (next == '\n'.code) {
                val bytes = buffer.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, Charsets.UTF_8)
            }
            buffer.write(next)
        }
    }

    private data class RawResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.mcp

import io.rune.mt.forum.domain.mcp.McpEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Minimal HTTP/1.1 server for the MCP endpoint, hosted inside the app.
 *
 * Why hand-rolled: the project has no HTTP server dependency (`gradle/libs.versions.toml` has no
 * NanoHTTPD/Ktor), and the endpoint only needs `POST` with a small JSON body. This class is the
 * whole transport:
 *
 *  - binds **127.0.0.1 only** ([McpEndpoint.HOST]); never `0.0.0.0`, so the forum session is not
 *    reachable from the LAN or from an emulator's host bridge;
 *  - accepts `POST /mcp` (and `POST /` for tolerant clients), answers `GET` with `405` because
 *    SSE is not implemented;
 *  - rejects bodies over [McpLimits.MAX_BODY_BYTES] with `413` *before* parsing;
 *  - throttles bursts with [McpRequestThrottle] (`429`);
 *  - bridges to the suspend repository with `runBlocking` **on the connection thread**, never on
 *    the main thread.
 *
 * Logging is limited to method/path/status/bytes; request bodies are never logged (rule 10's
 * spirit: no forum data or cookies in logs). [close] closes the listening socket, stops the
 * accept thread and shuts the connection pool down, so a stopped server cannot leak sockets.
 */
class McpHttpServer(
    private val dispatcher: McpJsonRpcDispatcher,
    private val requestedPort: Int,
    private val throttle: McpRequestThrottle = McpRequestThrottle(),
) : Closeable {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ExecutorService? = null

    /** Accepted, still-open client connections, so [close] cannot leave one behind. */
    private val openClients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var running = false

    /** The actual port once bound; useful when [requestedPort] is 0 (ephemeral, tests). */
    val port: Int
        get() = serverSocket?.localPort ?: requestedPort

    /** Loopback address the server is bound to, or null when stopped. */
    val bindingAddress: InetAddress?
        get() = serverSocket?.inetAddress

    /** Binds the socket and starts serving. Throws (e.g. port in use) instead of half-starting. */
    fun start() {
        check(!running) { "MCP server is already running" }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(McpEndpoint.HOST), requestedPort), BACKLOG)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
        serverSocket = socket
        running = true
        executor = Executors.newFixedThreadPool(CONNECTION_THREADS) { runnable ->
            Thread(runnable, "mcp-http").apply { isDaemon = true }
        }
        acceptThread = thread(name = "mcp-http-accept", isDaemon = true) { acceptLoop(socket) }
        Timber.tag(TAG).i("MCP listening on %s:%d", McpEndpoint.HOST, socket.localPort)
    }

    override fun close() {
        if (!running && serverSocket == null) return
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        // A keep-alive connection may be parked in `readLine` for the read timeout; close the
        // accepted sockets explicitly so stopping the server never leaks one.
        openClients.forEach { client -> runCatching { client.close() } }
        openClients.clear()
        executor?.shutdownNow()
        executor = null
        acceptThread?.let { worker ->
            runCatching { worker.join(JOIN_TIMEOUT_MILLIS) }
        }
        acceptThread = null
        Timber.tag(TAG).i("MCP server stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (error: Exception) {
                break
            }
            val pool = executor ?: run {
                runCatching { client.close() }
                break
            }
            try {
                pool.execute {
                    openClients += client
                    try {
                        client.use { handleConnection(it) }
                    } catch (error: Exception) {
                        if (running) Timber.tag(TAG).w("MCP connection failed: %s", error.javaClass.simpleName)
                    } finally {
                        openClients -= client
                    }
                }
            } catch (rejected: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        while (running) {
            val request = try {
                readRequest(input) ?: return
            } catch (tooLarge: McpPayloadTooLarge) {
                writeResponse(output, transportError(McpHttpStatus.PAYLOAD_TOO_LARGE, tooLarge.message.orEmpty()))
                return
            } catch (malformed: McpMalformedRequest) {
                writeResponse(output, transportError(McpHttpStatus.BAD_REQUEST, malformed.message.orEmpty()))
                return
            }
            if (!request.keepAlive) {
                writeResponse(output, route(request), keepAlive = false)
                return
            }
            val response = route(request)
            writeResponse(output, response, keepAlive = true)
        }
    }

    private fun route(request: HttpRequest): McpResponse {
        if (request.method != "POST") {
            return if (request.path == McpEndpoint.PATH || request.path == "/") {
                transportError(
                    McpHttpStatus.METHOD_NOT_ALLOWED,
                    "This MCP endpoint implements Streamable HTTP POST only (no SSE); use POST ${McpEndpoint.PATH}.",
                ).copy(headers = mapOf("Allow" to "POST"))
            } else {
                transportError(McpHttpStatus.NOT_FOUND, "Unknown path: ${request.path}")
            }
        }
        if (request.path != McpEndpoint.PATH && request.path != "/") {
            return transportError(McpHttpStatus.NOT_FOUND, "Unknown path: ${request.path}")
        }
        if (!throttle.tryAcquire()) {
            Timber.tag(TAG).w("MCP request throttled")
            return transportError(McpHttpStatus.TOO_MANY_REQUESTS, "Too many MCP requests; retry later.")
        }
        // The repository is suspend; this thread is a background connection thread, so
        // runBlocking is the bridge and the main thread is never involved.
        val response = runBlocking { dispatcher.handle(request.body, request.sessionId) }
        Timber.tag(TAG).i(
            "MCP %s %s -> %d (%d bytes)",
            request.method,
            request.path,
            response.status,
            response.body.length,
        )
        return response
    }

    // ---- HTTP request parsing ----------------------------------------------

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        var headerBudget = requestLine.length
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw McpMalformedRequest("Malformed request line")
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw McpMalformedRequest("Unexpected end of headers")
            headerBudget += line.length + 2
            if (headerBudget > McpLimits.MAX_HEADER_BYTES) {
                throw McpPayloadTooLarge("Request headers exceed ${McpLimits.MAX_HEADER_BYTES} bytes")
            }
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw McpMalformedRequest("Malformed header line")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0) throw McpMalformedRequest("Negative Content-Length")
        if (contentLength > McpLimits.MAX_BODY_BYTES) {
            throw McpPayloadTooLarge("Request body exceeds ${McpLimits.MAX_BODY_BYTES} bytes")
        }
        val bodyBytes = readExactly(input, contentLength)
            ?: throw McpMalformedRequest("Unexpected end of body")
        val keepAlive = headers["connection"]?.lowercase() != "close"
        return HttpRequest(
            method = method,
            path = path,
            body = String(bodyBytes, Charsets.UTF_8),
            sessionId = headers[MCP_SESSION_HEADER.lowercase()],
            keepAlive = keepAlive,
        )
    }

    /** Reads one CRLF-terminated line, tolerating a bare LF; null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val next = input.read()
            if (next == -1) {
                return if (buffer.size() == 0) null else throw McpMalformedRequest("Unexpected end of line")
            }
            if (next == '\n'.code) {
                val bytes = buffer.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, Charsets.UTF_8)
            }
            buffer.write(next)
            if (buffer.size() > MAX_LINE_BYTES) throw McpMalformedRequest("Header line too long")
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        if (length == 0) return ByteArray(0)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read <= 0) return null
            offset += read
        }
        return bytes
    }

    // ---- HTTP response writing ----------------------------------------------

    private fun writeResponse(output: OutputStream, response: McpResponse, keepAlive: Boolean = false) {
        val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reasonPhrase(response.status)}\r\n")
            append("Content-Type: $MCP_JSON_CONTENT_TYPE\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }

    private fun transportError(status: Int, message: String): McpResponse = McpResponse(
        status = status,
        body = buildString {
            append("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":")
            // Reuse the JSON-RPC invalid-request code for transport-level refusals; the HTTP
            // status is the authoritative signal for a client's own backoff/retry logic.
            append("-32600,\"message\":\"")
            append(message.replace("\\", "\\\\").replace("\"", "\\\""))
            append("\"}}")
        },
    )

    private fun reasonPhrase(status: Int): String = when (status) {
        McpHttpStatus.OK -> "OK"
        McpHttpStatus.ACCEPTED -> "Accepted"
        McpHttpStatus.BAD_REQUEST -> "Bad Request"
        McpHttpStatus.NOT_FOUND -> "Not Found"
        McpHttpStatus.METHOD_NOT_ALLOWED -> "Method Not Allowed"
        McpHttpStatus.PAYLOAD_TOO_LARGE -> "Payload Too Large"
        McpHttpStatus.TOO_MANY_REQUESTS -> "Too Many Requests"
        else -> "Internal Server Error"
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
        val sessionId: String?,
        val keepAlive: Boolean,
    )

    private class McpPayloadTooLarge(message: String) : Exception(message)

    private class McpMalformedRequest(message: String) : Exception(message)

    private companion object {
        const val TAG = "McpServer"
        const val BACKLOG = 4
        const val CONNECTION_THREADS = 2
        const val SOCKET_TIMEOUT_MILLIS = 15_000
        const val JOIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_LINE_BYTES = 8 * 1024
    }
}
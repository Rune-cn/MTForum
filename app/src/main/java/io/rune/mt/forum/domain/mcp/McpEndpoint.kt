// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.mcp

/**
 * The one address the in-app MCP server ever listens on.
 *
 * The transport is the MCP **Streamable HTTP** subset: a single JSON-RPC 2.0 endpoint that answers
 * every `POST` with `application/json`. There is no SSE stream (a `GET` is rejected with 405), so
 * the server fits in one small `ServerSocket` thread and needs no networking library.
 *
 * The socket is bound to the loopback address only and never to the Wi-Fi/LAN interface; the
 * server is a local developer surface for an MCP client running on the same device.
 */
object McpEndpoint {
    /** Loopback interface. Binding `0.0.0.0` would expose the forum session to the whole LAN. */
    const val HOST = "127.0.0.1"

    /** JSON-RPC endpoint path; `POST /` is accepted as a tolerant alias. */
    const val PATH = "/mcp"

    /** Default port; user-configurable in 设置 → MCP 服务. */
    const val DEFAULT_PORT = 8765

    /** Ports below 1024 need root and are not usable for an app-owned socket. */
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    /** MCP protocol revision this server implements (2025-06-18 JSON-RPC transport). */
    const val PROTOCOL_VERSION = "2025-06-18"

    /** The endpoint URL shown to the user / pasted into an MCP client config. */
    fun url(port: Int): String = "http://$HOST:$port$PATH"

    fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

    /** Falls back to the default instead of ever binding a privileged or invalid port. */
    fun normalizePort(port: Int): Int = if (isValidPort(port)) port else DEFAULT_PORT
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.mcp

/** HTTP status codes used by the MCP endpoint (the transport subset is deliberately tiny). */
object McpHttpStatus {
    const val OK = 200
    const val ACCEPTED = 202
    const val BAD_REQUEST = 400
    const val NOT_FOUND = 404
    const val METHOD_NOT_ALLOWED = 405
    const val PAYLOAD_TOO_LARGE = 413
    const val TOO_MANY_REQUESTS = 429
    const val INTERNAL_ERROR = 500
}

/** Header carrying the optional session id minted by `initialize` (spec 2025-06-18). */
const val MCP_SESSION_HEADER = "Mcp-Session-Id"

/** Content type of every JSON response; SSE is not implemented. */
const val MCP_JSON_CONTENT_TYPE = "application/json; charset=utf-8"

/**
 * A transport-neutral reply produced by [McpJsonRpcDispatcher].
 *
 * The dispatcher never touches a socket, so JSON-RPC behaviour is unit-tested as a pure function;
 * [McpHttpServer] only turns this into bytes.
 */
data class McpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)
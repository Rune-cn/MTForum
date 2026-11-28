// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.mcp

import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.mcp.McpEndpoint
import io.rune.mt.forum.domain.mcp.McpPostFormatter
import io.rune.mt.forum.domain.mcp.McpToolCatalog
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber

/**
 * JSON-RPC 2.0 dispatcher for the MCP Streamable HTTP transport subset.
 *
 * Pure function of (request body, repository): no sockets, no Android APIs, no main-thread
 * assumptions. The caller runs it on a background thread and bridges to the suspend repository
 * there; [handle] itself is `suspend` and simply awaits repository calls.
 *
 * Implemented methods: `initialize`, `notifications/initialized` (any notification is ack'd with
 * 202), `ping`, `tools/list`, `tools/call`. Everything exposed by `tools/call` is read-only:
 * four forum queries backed by [ForumRepository]. There is no method that can post, send a PM,
 * sign in or change settings.
 */
class McpJsonRpcDispatcher(
    private val repository: ForumRepository,
    private val postFormatter: McpPostFormatter,
    private val serverName: String = "MT论坛 MCP",
    private val serverVersion: String = "1.0.0",
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Handles one JSON-RPC frame.
     *
     * [requestSessionId] is the client's echo of a previous `Mcp-Session-Id`; the server does not
     * keep sessions, so it is accepted and ignored (documented in `docs/MCP.md`).
     */
    suspend fun handle(body: String, requestSessionId: String? = null): McpResponse {
        if (body.isBlank()) {
            return jsonRpcError(id = null, code = PARSE_ERROR, message = "Parse error: empty body")
        }
        val root = try {
            JSON.parseToJsonElement(body)
        } catch (error: Exception) {
            return jsonRpcError(id = null, code = PARSE_ERROR, message = "Parse error: ${error.message ?: "invalid JSON"}")
        }
        if (root !is JsonObject) {
            return jsonRpcError(id = null, code = INVALID_REQUEST, message = "Invalid Request: a single JSON-RPC object is required")
        }

        val idElement = root["id"]
        val isNotification = !root.containsKey("id")
        if (idElement != null && idElement !is JsonPrimitive) {
            return jsonRpcError(id = null, code = INVALID_REQUEST, message = "Invalid Request: id must be a string or a number")
        }
        if (root["jsonrpc"]?.jsonPrimitive?.contentOrNull != "2.0") {
            return jsonRpcError(id = idElement, code = INVALID_REQUEST, message = "Invalid Request: jsonrpc must be \"2.0\"")
        }
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull
            ?: return jsonRpcError(id = idElement, code = INVALID_REQUEST, message = "Invalid Request: method is required")
        val params = root["params"] as? JsonObject

        // Notifications never get a JSON-RPC response; 202 with an empty body acknowledges them.
        if (isNotification || method.startsWith("notifications/")) {
            return McpResponse(McpHttpStatus.ACCEPTED, "")
        }

        return when (method) {
            "initialize" -> {
                val result = buildJsonObject {
                    put("protocolVersion", McpEndpoint.PROTOCOL_VERSION)
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {
                            put("listChanged", false)
                        }
                    }
                    putJsonObject("serverInfo") {
                        put("name", serverName)
                        put("version", serverVersion)
                    }
                    put(
                        "instructions",
                        "Read-only MT论坛 access: boards, thread lists, thread floors (plain text + attachments) " +
                            "and in-forum search. No posting, private messages, login or writes.",
                    )
                }
                success(
                    id = idElement,
                    result = result,
                    headers = mapOf(MCP_SESSION_HEADER to sessionIdFactory()),
                )
            }

            "ping" -> success(idElement, buildJsonObject { })

            "tools/list" -> success(
                idElement,
                buildJsonObject {
                    putJsonArray("tools") {
                        McpToolCatalog.tools.forEach { info ->
                            add(
                                buildJsonObject {
                                    put("name", info.name)
                                    put("title", info.title)
                                    put("description", info.description)
                                    put("inputSchema", toolSchema(info.name))
                                },
                            )
                        }
                    }
                },
            )

            "tools/call" -> toolsCall(idElement, params)

            else -> jsonRpcError(
                id = idElement,
                code = METHOD_NOT_FOUND,
                message = "Method not found: $method",
            )
        }
    }

    // ---- tools/call ---------------------------------------------------------

    private suspend fun toolsCall(id: JsonElement?, params: JsonObject?): McpResponse {
        if (params == null) {
            return jsonRpcError(id, INVALID_PARAMS, "Invalid params: tools/call needs an object with name and arguments")
        }
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return jsonRpcError(id, INVALID_PARAMS, "Invalid params: tools/call.name is required")
        if (name !in McpToolCatalog.names) {
            return jsonRpcError(id, METHOD_NOT_FOUND, "Unknown tool: $name")
        }
        val arguments = when (val raw = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> return jsonRpcError(id, INVALID_PARAMS, "Invalid params: tools/call.arguments must be an object")
        }

        val text = try {
            when (name) {
                McpToolCatalog.LIST_BOARDS -> listBoards()
                McpToolCatalog.LIST_THREADS -> listThreads(arguments)
                McpToolCatalog.GET_THREAD -> getThread(arguments)
                McpToolCatalog.SEARCH_THREADS -> searchThreads(arguments)
                // Unreachable: the catalog check above rejects everything else first.
                else -> return jsonRpcError(id, METHOD_NOT_FOUND, "Unknown tool: $name")
            }
        } catch (invalid: McpInvalidArguments) {
            return jsonRpcError(id, INVALID_PARAMS, "Invalid params: ${invalid.message}")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (loginRequired: ForumLoginRequiredException) {
            Timber.tag(TAG).i("tool %s answered with the login form", name)
            return toolResult(id, LOGIN_REQUIRED_MESSAGE, isError = true)
        } catch (error: Exception) {
            // A tool-level failure is a normal MCP result with isError=true, never a crash and
            // never a JSON-RPC transport error (spec: tool execution errors use isError).
            Timber.tag(TAG).w(error, "tool %s failed", name)
            return toolResult(
                id,
                "Tool failed: ${error.javaClass.simpleName}: ${error.message ?: "no detail"}",
                isError = true,
            )
        }
        return toolResult(id, text, isError = false)
    }

    // ---- list_boards --------------------------------------------------------

    private suspend fun listBoards(): String {
        val home = repository.getHome()
        // Shrink long descriptions instead of dropping rows: rule 16 forbids client-side
        // truncation of the site's own ordering, and the JSON must stay valid.
        var text = encodeBoards(home.forums, fieldCap = McpLimits.MAX_TITLE_CHARS)
        if (text.length > McpLimits.MAX_RESULT_CHARS) {
            text = encodeBoards(home.forums, fieldCap = 80)
        }
        return text
    }

    private fun encodeBoards(forums: List<Forum>, fieldCap: Int): String = buildJsonObject {
        put("count", forums.size)
        putJsonArray("forums") {
            forums.forEach { forum ->
                add(
                    buildJsonObject {
                        put("fid", forum.fid)
                        put("name", McpLimits.capText(forum.name, fieldCap).first)
                        forum.description
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put("description", McpLimits.capText(it, fieldCap).first) }
                        forum.todayCount?.let { put("todayThreads", it) }
                    },
                )
            }
        }
    }.toString()

    // ---- list_threads -------------------------------------------------------

    private suspend fun listThreads(arguments: JsonObject): String {
        val fid = arguments.intArg("fid")
        val page = arguments.pageArg()
        val result = repository.getThreads(fid = fid, page = page)
        var text = encodeThreadPage(result, fid = fid, fieldCap = McpLimits.MAX_TITLE_CHARS)
        if (text.length > McpLimits.MAX_RESULT_CHARS) {
            text = encodeThreadPage(result, fid = fid, fieldCap = 80)
        }
        return text
    }

    private fun encodeThreadPage(page: ForumThreadPage, fid: Int, fieldCap: Int): String = buildJsonObject {
        put("fid", fid)
        put("page", page.page)
        put("totalPages", page.totalPages)
        put("count", page.threads.size)
        putJsonArray("threads") {
            page.threads.forEach { thread -> add(threadJson(thread, fieldCap)) }
        }
    }.toString()

    private fun threadJson(thread: ForumThread, fieldCap: Int): JsonObject = buildJsonObject {
        put("tid", thread.tid)
        put("title", McpLimits.capText(thread.title, fieldCap).first)
        put("author", McpLimits.capText(thread.authorName, fieldCap).first)
        put("replies", thread.replyCount)
        thread.timeText?.takeIf { it.isNotBlank() }?.let { put("time", it) }
        if (thread.pinned) put("pinned", true)
        if (thread.digest) put("digest", true)
    }

    // ---- get_thread ---------------------------------------------------------

    private suspend fun getThread(arguments: JsonObject): String {
        val tid = arguments.intArg("tid")
        val page = arguments.pageArg()
        val detail = repository.getThreadDetail(tid = tid, page = page)
        val floors = detail.posts
        // Reserve room for the envelope, then share the remaining budget across the page's
        // floors. One huge OP can no longer crowd every reply out of the result.
        val budget = (McpLimits.MAX_RESULT_CHARS - ENVELOPE_CHARS).coerceAtLeast(floors.size * MIN_FLOOR_BUDGET)
        val perFloor = if (floors.isEmpty()) McpLimits.MAX_POST_TEXT_CHARS else {
            (budget / floors.size).coerceIn(MIN_FLOOR_BUDGET, McpLimits.MAX_POST_TEXT_CHARS)
        }
        return encodeDetail(detail, perFloor)
    }

    private fun encodeDetail(detail: ForumThreadDetail, bodyCap: Int): String = buildJsonObject {
        put("tid", detail.tid)
        put("title", detail.title)
        detail.forumName?.takeIf { it.isNotBlank() }?.let { put("forum", it) }
        put("page", detail.page)
        put("totalPages", detail.totalPages)
        put("count", detail.posts.size)
        putJsonArray("floors") {
            detail.posts.forEachIndexed { index, post -> add(floorJson(post, index, bodyCap)) }
        }
    }.toString()

    private fun floorJson(post: ForumPost, index: Int, bodyCap: Int): JsonObject {
        val content = postFormatter.format(post.contentHtml)
        val (body, bodyTruncated) = McpLimits.capText(content.text, bodyCap)
        return buildJsonObject {
            // Floor 0 is the OP (the app's own ladder); replies continue 1, 2, …
            put("floor", post.floor ?: (index + 1))
            put("author", post.authorName)
            post.timeText?.takeIf { it.isNotBlank() }?.let { put("time", it) }
            put("isOp", post.isOp)
            put("body", body)
            if (bodyTruncated) put("truncated", true)
            if (content.attachments.isNotEmpty()) {
                putJsonArray("attachments") {
                    content.attachments.forEach { attachment ->
                        add(
                            buildJsonObject {
                                put("name", attachment.name)
                                put("url", attachment.url)
                                if (attachment.costCoins > 0) put("costCoins", attachment.costCoins)
                            },
                        )
                    }
                }
            }
        }
    }

    // ---- search_threads -----------------------------------------------------

    private suspend fun searchThreads(arguments: JsonObject): String {
        val query = arguments.stringArg("q")
        val page = arguments.pageArg()
        // Search is login-gated on the site; ask the session first so a guest gets a readable
        // explanation instead of a repository exception or an empty list.
        if (!repository.session.value.isLoggedIn) throw ForumLoginRequiredException()
        val result = repository.searchThreads(keyword = query, page = page)
        var text = buildJsonObject {
            put("query", query)
            put("page", result.page)
            put("totalPages", result.totalPages)
            put("count", result.threads.size)
            putJsonArray("threads") {
                result.threads.forEach { thread ->
                    add(threadJson(thread, fieldCap = McpLimits.MAX_TITLE_CHARS))
                }
            }
        }.toString()
        if (text.length > McpLimits.MAX_RESULT_CHARS) {
            text = buildJsonObject {
                put("query", query)
                put("page", result.page)
                put("totalPages", result.totalPages)
                put("count", result.threads.size)
                putJsonArray("threads") {
                    result.threads.forEach { thread -> add(threadJson(thread, fieldCap = 80)) }
                }
            }.toString()
        }
        return text
    }

    // ---- schemas ------------------------------------------------------------

    private fun toolSchema(name: String): JsonObject = when (name) {
        McpToolCatalog.LIST_BOARDS -> schema(emptyMap())

        McpToolCatalog.LIST_THREADS -> schema(
            mapOf("fid" to intProperty("Board id (fid) from list_boards.", minimum = 1), "page" to pageProperty()),
            required = listOf("fid"),
        )

        McpToolCatalog.GET_THREAD -> schema(
            mapOf("tid" to intProperty("Thread id (tid) from list_threads or search_threads.", minimum = 1), "page" to pageProperty()),
            required = listOf("tid"),
        )

        McpToolCatalog.SEARCH_THREADS -> schema(
            mapOf(
                "q" to buildJsonObject {
                    put("type", "string")
                    put("description", "Search keyword; the site's own in-forum search is used.")
                    put("minLength", 1)
                },
                "page" to pageProperty(),
            ),
            required = listOf("q"),
        )

        else -> schema(emptyMap())
    }

    private fun schema(properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                properties.forEach { (name, value) -> put(name, value) }
            }
            if (required.isNotEmpty()) {
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }
            put("additionalProperties", false)
        }

    private fun intProperty(description: String, minimum: Int): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        put("minimum", minimum)
    }

    private fun pageProperty(): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", "1-based page number; the site's own pager decides the page count.")
        put("minimum", 1)
        put("default", 1)
    }

    // ---- argument parsing ---------------------------------------------------

    /**
     * Reads a required integer argument.
     *
     * A wrong JSON type or a value below [MIN_ID] is an `Invalid params` (-32602), never a silent
     * default: the client must fix its call instead of receiving an unrelated board/thread.
     */
    private fun JsonObject.intArg(name: String): Int {
        val element = this[name]
        if (element == null || element == JsonNull) invalid("$name is required")
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: invalid("$name must be an integer")
        if (value < MIN_ID) invalid("$name must be >= $MIN_ID")
        return value
    }

    /** `page` is optional and defaults to 1; a present-but-wrong value is still an error. */
    private fun JsonObject.pageArg(): Int {
        val element = this["page"]
        if (element == null || element == JsonNull) return 1
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: invalid("page must be an integer")
        if (value < 1) invalid("page must be >= 1")
        return value
    }

    private fun JsonObject.stringArg(name: String): String {
        val element = this[name]
        if (element == null || element == JsonNull) invalid("$name is required")
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
            ?: invalid("$name must be a string")
        if (value.isEmpty()) invalid("$name must not be blank")
        return value
    }

    private fun invalid(message: String): Nothing = throw McpInvalidArguments(message)

    // ---- response builders --------------------------------------------------

    private fun success(
        id: JsonElement?,
        result: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): McpResponse = McpResponse(
        status = McpHttpStatus.OK,
        body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", result)
        }.toString(),
        headers = headers,
    )

    private fun jsonRpcError(id: JsonElement?, code: Int, message: String): McpResponse = McpResponse(
        status = McpHttpStatus.OK,
        body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString(),
    )

    /** A tool execution result: `content: [ {type:text,text} ]` plus the `isError` flag. */
    private fun toolResult(id: JsonElement?, text: String, isError: Boolean): McpResponse = success(
        id = id,
        result = buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            put("isError", isError)
        },
    )

    /** Dialect used when the repository throws inside a tool; shared with the UI docs. */
    private class McpInvalidArguments(message: String) : Exception(message)

    private companion object {
        const val TAG = "McpServer"

        /** JSON-RPC 2.0 standard error codes. */
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        const val MIN_ID = 1
        const val ENVELOPE_CHARS = 1_500
        const val MIN_FLOOR_BUDGET = 400

        const val LOGIN_REQUIRED_MESSAGE =
            "search_threads requires a signed-in session: the forum serves search only to logged-in " +
                "accounts. Sign in inside the MT论坛 app, then retry."

        val JSON = Json { ignoreUnknownKeys = true }
    }
}
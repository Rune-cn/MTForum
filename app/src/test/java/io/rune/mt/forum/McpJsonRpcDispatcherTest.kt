// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.mcp.McpHttpStatus
import io.rune.mt.forum.data.mcp.McpJsonRpcDispatcher
import io.rune.mt.forum.data.mcp.McpResponse
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import io.rune.mt.forum.domain.mcp.McpToolCatalog
import io.rune.mt.forum.ui.page.forum.ForumMcpPostFormatter
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JSON-RPC tests for the in-app MCP server: no sockets, fake repository.
 *
 * The transport is only "POST one JSON object, get one JSON object"; these tests pin the exact
 * envelopes an MCP client expects (`initialize`, `tools/list`, `tools/call`), the four read-only
 * tools, and every documented failure mapping (parse error, invalid params, unknown tool,
 * tool-level `isError`).
 */
class McpJsonRpcDispatcherTest {

    private val formatter = ForumMcpPostFormatter()
    private val json = Json { ignoreUnknownKeys = true }

    // ---- initialize / lifecycle --------------------------------------------

    @Test
    fun `initialize returns protocol version capabilities and server info`() = runBlocking {
        val response = dispatcher(McpFakeRepository()).handle(request("initialize", id = 1))

        assertEquals(McpHttpStatus.OK, response.status)
        assertEquals("stale-session", response.headers["Mcp-Session-Id"])
        val result = result(response)
        assertEquals("2025-06-18", result["protocolVersion"]?.jsonPrimitive?.content)
        val tools = result["capabilities"]?.jsonObject?.get("tools")?.jsonObject
        assertNotNull("capabilities.tools must exist", tools)
        assertFalse("listChanged must be false", tools!!["listChanged"]!!.jsonPrimitive.boolean)
        val serverInfo = result["serverInfo"]!!.jsonObject
        assertEquals("MT论坛 MCP", serverInfo["name"]?.jsonPrimitive?.content)
        assertEquals("test-version", serverInfo["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialized notification is acknowledged with 202 and an empty body`() = runBlocking {
        val response = dispatcher(McpFakeRepository())
            .handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertEquals(McpHttpStatus.ACCEPTED, response.status)
        assertEquals("", response.body)
    }

    @Test
    fun `a notification never receives a JSON-RPC response, even for an unknown method`() = runBlocking {
        val response = dispatcher(McpFakeRepository())
            .handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{}}""")

        assertEquals(McpHttpStatus.ACCEPTED, response.status)
        assertEquals("", response.body)
    }

    // ---- tools/list ---------------------------------------------------------

    @Test
    fun `tools list exposes exactly the four read-only forum tools`() = runBlocking {
        val response = dispatcher(McpFakeRepository()).handle(request("tools/list", id = 2))
        val tools = result(response)["tools"]!!.jsonArray

        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf(
                McpToolCatalog.LIST_BOARDS,
                McpToolCatalog.LIST_THREADS,
                McpToolCatalog.GET_THREAD,
                McpToolCatalog.SEARCH_THREADS,
            ),
            names,
        )
        // No mutating operation is reachable from the MCP surface.
        listOf("reply", "create_thread", "send_private_message", "login", "sign_in", "add_favorite").forEach {
            assertFalse("$it must not be exposed", names.contains(it))
        }
    }

    @Test
    fun `tool declarations carry title description and a typed input schema`() = runBlocking {
        val response = dispatcher(McpFakeRepository()).handle(request("tools/list", id = 3))
        val tools = result(response)["tools"]!!.jsonArray

        tools.forEach { element ->
            val tool = element.jsonObject
            assertTrue("name", tool["name"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue("title", tool["title"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue("description", tool["description"]!!.jsonPrimitive.content.isNotBlank())
            val schema = tool["inputSchema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertNotNull(schema["properties"])
        }

        val byName = tools.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val threads = byName[McpToolCatalog.LIST_THREADS]!!.jsonObject["inputSchema"]!!.jsonObject
        assertEquals("integer", threads["properties"]!!.jsonObject["fid"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("fid"), (threads["required"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(1, threads["properties"]!!.jsonObject["page"]!!.jsonObject["default"]!!.jsonPrimitive.int)

        val search = byName[McpToolCatalog.SEARCH_THREADS]!!.jsonObject["inputSchema"]!!.jsonObject
        assertEquals("string", search["properties"]!!.jsonObject["q"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("q"), (search["required"] as JsonArray).map { it.jsonPrimitive.content })

        val boards = byName[McpToolCatalog.LIST_BOARDS]!!.jsonObject["inputSchema"]!!.jsonObject
        assertNull("list_boards takes no arguments", boards["required"])
    }

    // ---- list_boards --------------------------------------------------------

    @Test
    fun `list_boards returns every board in site order`() = runBlocking {
        val repo = McpFakeRepository(home = mcpHomeSample())
        val response = callTool(repo, McpToolCatalog.LIST_BOARDS)

        assertFalse(toolIsError(response))
        val payload = json.parseToJsonElement(toolText(response)).jsonObject
        assertEquals(2, payload["count"]!!.jsonPrimitive.int)
        val rows = payload["forums"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, rows[0]["fid"]!!.jsonPrimitive.int)
        assertEquals("ANDROID 逆向", rows[0]["name"]!!.jsonPrimitive.content)
        assertEquals(7, rows[0]["todayThreads"]!!.jsonPrimitive.int)
        assertEquals(39, rows[1]["fid"]!!.jsonPrimitive.int)
        assertEquals("灌水区", rows[1]["name"]!!.jsonPrimitive.content)
    }

    // ---- list_threads -------------------------------------------------------

    @Test
    fun `list_threads passes fid and the default page then keeps server order`() = runBlocking {
        val repo = McpFakeRepository(threadPage = mcpThreadPageSample())
        val response = callTool(repo, McpToolCatalog.LIST_THREADS, buildJsonObject { put("fid", 2) })

        assertFalse(toolIsError(response))
        assertEquals(2 to 1, repo.lastThreadsRequest)
        val payload = json.parseToJsonElement(toolText(response)).jsonObject
        assertEquals(2, payload["fid"]!!.jsonPrimitive.int)
        assertEquals(1, payload["page"]!!.jsonPrimitive.int)
        assertEquals(5, payload["totalPages"]!!.jsonPrimitive.int)
        val rows = payload["threads"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(101, 102), rows.map { it["tid"]!!.jsonPrimitive.int })
        assertEquals("alice", rows[0]["author"]!!.jsonPrimitive.content)
        assertEquals(3, rows[0]["replies"]!!.jsonPrimitive.int)
        assertEquals("2026-01-02", rows[0]["time"]!!.jsonPrimitive.content)
        assertEquals(true, rows[1]["pinned"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `list_threads forwards an explicit page`() = runBlocking {
        val repo = McpFakeRepository(threadPage = mcpThreadPageSample(page = 3))
        callTool(repo, McpToolCatalog.LIST_THREADS, buildJsonObject { put("fid", 2); put("page", 3) })

        assertEquals(2 to 3, repo.lastThreadsRequest)
    }

    // ---- get_thread ---------------------------------------------------------

    @Test
    fun `get_thread flattens floors to plain text and lists attachments`() = runBlocking {
        val repo = McpFakeRepository(detail = mcpThreadDetailSample())
        val response = callTool(repo, McpToolCatalog.GET_THREAD, buildJsonObject { put("tid", 166223) })

        assertFalse(toolIsError(response))
        assertEquals(166223 to 1, repo.lastDetailRequest)
        val payload = json.parseToJsonElement(toolText(response)).jsonObject
        assertEquals("How to patch", payload["title"]!!.jsonPrimitive.content)
        assertEquals("ANDROID 逆向", payload["forum"]!!.jsonPrimitive.content)

        val floors = payload["floors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, floors.size)
        assertEquals(0, floors[0]["floor"]!!.jsonPrimitive.int)
        assertEquals(true, floors[0]["isOp"]!!.jsonPrimitive.boolean)
        val bodyText = floors[0]["body"]!!.jsonPrimitive.content
        assertTrue("the post text must survive, got: $bodyText", bodyText.contains("Hello world"))
        assertFalse("HTML tags must be stripped", bodyText.contains("<p"))
        assertFalse("inline markup must be stripped", bodyText.contains("<strong"))

        val attachments = floors[0]["attachments"]!!.jsonArray.map { it.jsonObject }
        assertEquals("patch.zip", attachments[0]["name"]!!.jsonPrimitive.content)
        assertTrue(
            "attachment URL must be absolute: ${attachments[0]["url"]}",
            attachments[0]["url"]!!.jsonPrimitive.content.contains("mod=attachment"),
        )
        assertEquals(2, attachments[0]["costCoins"]!!.jsonPrimitive.int)

        assertEquals(1, floors[1]["floor"]!!.jsonPrimitive.int)
        assertEquals(0, floors[1]["attachments"]?.jsonArray?.size ?: 0)
    }

    @Test
    fun `get_thread caps a huge floor body and marks it truncated`() = runBlocking {
        val huge = "字".repeat(9_000)
        val repo = McpFakeRepository(
            detail = ForumThreadDetail(
                tid = 7,
                title = "Long",
                posts = listOf(mcpPostSample(index = 0, html = "<p>$huge</p>")),
                page = 1,
                totalPages = 1,
            ),
        )
        val response = callTool(repo, McpToolCatalog.GET_THREAD, buildJsonObject { put("tid", 7) })

        assertFalse(toolIsError(response))
        val floor = json.parseToJsonElement(toolText(response)).jsonObject["floors"]!!.jsonArray[0].jsonObject
        val body = floor["body"]!!.jsonPrimitive.content
        assertTrue("body must be capped, was ${body.length}", body.length <= 4_000 + 20)
        assertEquals(true, floor["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(toolText(response).length <= 48_000)
    }

    // ---- search_threads -----------------------------------------------------

    @Test
    fun `search_threads requires a signed-in session and never calls the site`() = runBlocking {
        val repo = McpFakeRepository(loggedIn = false)
        val response = callTool(repo, McpToolCatalog.SEARCH_THREADS, buildJsonObject { put("q", "签名") })

        assertTrue(toolIsError(response))
        assertTrue(toolText(response).contains("signed-in"))
        assertNull("the site must not be hit while signed out", repo.lastSearchRequest)
    }

    @Test
    fun `search_threads returns rows for a signed-in session`() = runBlocking {
        val repo = McpFakeRepository(
            loggedIn = true,
            searchPage = mcpThreadPageSample(page = 2).copy(fid = 0),
        )
        val response = callTool(
            repo,
            McpToolCatalog.SEARCH_THREADS,
            buildJsonObject { put("q", " 签名 "); put("page", 2) },
        )

        assertFalse(toolIsError(response))
        assertEquals("签名" to 2, repo.lastSearchRequest)
        val payload = json.parseToJsonElement(toolText(response)).jsonObject
        assertEquals("签名", payload["query"]!!.jsonPrimitive.content)
        assertEquals(2, payload["page"]!!.jsonPrimitive.int)
        assertEquals(2, payload["threads"]!!.jsonArray.size)
    }

    @Test
    fun `search_threads maps ForumLoginRequiredException to isError`() = runBlocking {
        val repo = McpFakeRepository()
        repo.searchFailure = ForumLoginRequiredException()
        val response = callTool(repo, McpToolCatalog.SEARCH_THREADS, buildJsonObject { put("q", "签名") })

        assertTrue(toolIsError(response))
        assertTrue(toolText(response).contains("signed-in"))
    }

    // ---- failure mapping ----------------------------------------------------

    @Test
    fun `unknown tool is a method-not-found JSON-RPC error`() = runBlocking {
        val response = callTool(McpFakeRepository(), "create_thread", buildJsonObject { put("fid", 2) })

        val error = error(response)
        assertEquals(-32601, error["code"]!!.jsonPrimitive.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("create_thread"))
        assertNull("an unknown tool must not be reported as a tool result", response.body.indexOf("isError").takeIf { it >= 0 })
    }

    @Test
    fun `a missing required argument is invalid params`() = runBlocking {
        val error = error(callTool(McpFakeRepository(), McpToolCatalog.LIST_THREADS, buildJsonObject { }))

        assertEquals(-32602, error["code"]!!.jsonPrimitive.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("fid"))
    }

    @Test
    fun `a wrong argument type is invalid params`() = runBlocking {
        val error = error(
            callTool(McpFakeRepository(), McpToolCatalog.LIST_THREADS, buildJsonObject { put("fid", "2") }),
        )

        assertEquals(-32602, error["code"]!!.jsonPrimitive.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("fid"))
    }

    @Test
    fun `an out-of-range page is invalid params`() = runBlocking {
        val error = error(
            callTool(
                McpFakeRepository(),
                McpToolCatalog.GET_THREAD,
                buildJsonObject { put("tid", 1); put("page", 0) },
            ),
        )

        assertEquals(-32602, error["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a repository failure becomes a tool result with isError true`() = runBlocking {
        val repo = McpFakeRepository()
        repo.homeFailure = IOException("boom")
        val response = callTool(repo, McpToolCatalog.LIST_BOARDS)

        assertFalse("tool failures are not JSON-RPC errors", response.body.contains("\"error\""))
        assertTrue(toolIsError(response))
        assertTrue(toolText(response).contains("boom"))
    }

    @Test
    fun `malformed JSON is a parse error`() = runBlocking {
        val response = dispatcher(McpFakeRepository()).handle("{not json")

        val error = error(response)
        assertEquals(-32700, error["code"]!!.jsonPrimitive.int)
        assertEquals("null", body(response)["id"].toString())
    }

    @Test
    fun `unknown method is method not found`() = runBlocking {
        val response = dispatcher(McpFakeRepository()).handle(request("resources/list", id = 9))

        assertEquals(-32601, error(response)["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a non-object frame is an invalid request`() = runBlocking {
        val response = dispatcher(McpFakeRepository()).handle("[]")

        assertEquals(-32600, error(response)["code"]!!.jsonPrimitive.int)
    }

    // ---- helpers ------------------------------------------------------------

    private fun dispatcher(repo: McpFakeRepository) = McpJsonRpcDispatcher(
        repository = repo,
        postFormatter = formatter,
        serverName = "MT论坛 MCP",
        serverVersion = "test-version",
        sessionIdFactory = { "stale-session" },
    )

    private fun callTool(
        repo: McpFakeRepository,
        name: String,
        arguments: JsonObject = buildJsonObject { },
    ): McpResponse = runBlocking {
        dispatcher(repo).handle(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 42)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", name)
                    put("arguments", arguments)
                }
            }.toString(),
        )
    }

    private fun request(method: String, id: Int): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
    }.toString()

    private fun body(response: McpResponse): JsonObject = json.parseToJsonElement(response.body).jsonObject

    private fun result(response: McpResponse): JsonObject = body(response)["result"]!!.jsonObject

    private fun error(response: McpResponse): JsonObject = body(response)["error"]!!.jsonObject

    private fun toolText(response: McpResponse): String =
        result(response)["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun toolIsError(response: McpResponse): Boolean =
        result(response)["isError"]!!.jsonPrimitive.boolean
}
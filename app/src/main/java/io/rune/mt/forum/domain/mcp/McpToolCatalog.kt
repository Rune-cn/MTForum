// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.mcp

/**
 * Metadata for one tool the local MCP server exposes.
 *
 * Kept in the domain layer so the 设置 page can list exactly what is exposed without importing the
 * transport implementation. Every tool is **read-only** and backed by `ForumRepository`; the
 * catalog deliberately has no posting, private-message, login or sign-in entry.
 */
data class McpToolInfo(
    val name: String,
    val title: String,
    val description: String,
)

object McpToolCatalog {
    const val LIST_BOARDS = "list_boards"
    const val LIST_THREADS = "list_threads"
    const val GET_THREAD = "get_thread"
    const val SEARCH_THREADS = "search_threads"

    val tools: List<McpToolInfo> = listOf(
        McpToolInfo(
            name = LIST_BOARDS,
            title = "List boards",
            description = "Board list (fid + name) from the forum home page.",
        ),
        McpToolInfo(
            name = LIST_THREADS,
            title = "List threads",
            description = "Server-ordered thread rows of one board: tid, title, author, replies, time.",
        ),
        McpToolInfo(
            name = GET_THREAD,
            title = "Get thread",
            description = "One thread page: title, forum, and every floor with plain-text body and attachments.",
        ),
        McpToolInfo(
            name = SEARCH_THREADS,
            title = "Search threads",
            description = "In-forum search by keyword; requires the app's signed-in session.",
        ),
    )

    val names: Set<String> = tools.mapTo(linkedSetOf()) { it.name }
}
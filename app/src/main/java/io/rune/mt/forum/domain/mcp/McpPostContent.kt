// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.mcp

/** One attachment advertised by a post body, as the MCP client needs to name/download it. */
data class McpAttachment(
    val name: String,
    val url: String,
    /** Paid-attachment price hint (`下载积分: 金币 -N`); 0 when free. Informational only. */
    val costCoins: Int = 0,
)

/** A post body flattened for the model: readable text plus the attachment cards it contained. */
data class McpPostContent(
    val text: String,
    val attachments: List<McpAttachment> = emptyList(),
)

/**
 * Bridges a forum post's raw HTML to plain text + attachment metadata.
 *
 * Implemented on the UI side (`ForumBodyParser` owns all body-flattening rules, rule 25), so the
 * MCP data layer never grows a second renderer or its own DOM selectors.
 */
fun interface McpPostFormatter {
    fun format(html: String): McpPostContent
}
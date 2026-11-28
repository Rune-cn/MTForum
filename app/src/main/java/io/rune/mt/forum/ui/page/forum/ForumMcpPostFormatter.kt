// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import io.rune.mt.forum.domain.mcp.McpAttachment
import io.rune.mt.forum.domain.mcp.McpPostContent
import io.rune.mt.forum.domain.mcp.McpPostFormatter

/**
 * [McpPostFormatter] over [ForumBodyParser].
 *
 * Rule 25 keeps every body-flattening rule inside `ForumBodyParser`/`ForumHtmlText`; the MCP
 * server must not grow a second HTML stripper. This adapter therefore reuses the parser twice:
 * `plainText` for the readable body the model receives, and `parse` only to lift the attachment
 * cards (`ForumBodyBlock.Attachment`) that the plain-text flattening drops.
 */
class ForumMcpPostFormatter : McpPostFormatter {

    override fun format(html: String): McpPostContent {
        if (html.isBlank()) return McpPostContent(text = "")
        val attachments = runCatching { ForumBodyParser.parse(html) }
            .getOrDefault(emptyList())
            .filterIsInstance<ForumBodyBlock.Attachment>()
            .map { block ->
                McpAttachment(
                    name = block.name,
                    url = block.url,
                    costCoins = block.costCoins,
                )
            }
        return McpPostContent(
            text = ForumBodyParser.plainText(html),
            attachments = attachments,
        )
    }
}
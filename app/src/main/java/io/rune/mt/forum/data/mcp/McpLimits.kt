// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.mcp

/**
 * Hard limits of the local MCP surface.
 *
 * They exist so a phone-hosted server cannot be turned into a memory pump: request bodies are
 * small JSON-RPC frames, tool output is capped per floor and per response, and every cap is
 * reflected in the output (`truncated` flags / a truncation suffix) instead of silently dropping
 * rows.
 */
object McpLimits {
    /** Raw HTTP body limit; a larger POST gets `413` before any JSON parsing. */
    const val MAX_BODY_BYTES: Int = 64 * 1024

    /** HTTP header block limit (request line included). */
    const val MAX_HEADER_BYTES: Int = 16 * 1024

    /** Upper bound for one tool result's text content. */
    const val MAX_RESULT_CHARS: Int = 48_000

    /** Upper bound for one floor's plain-text body inside `get_thread`. */
    const val MAX_POST_TEXT_CHARS: Int = 4_000

    /** Upper bound for a thread/board title or description inside a list result. */
    const val MAX_TITLE_CHARS: Int = 300

    /** Suffix appended when a field/body was cut to fit [MAX_RESULT_CHARS]. */
    const val TRUNCATION_SUFFIX: String = "…(truncated)"

    /** Caps [text] at [limit] characters, reporting whether it was cut. */
    fun capText(text: String, limit: Int): Pair<String, Boolean> {
        if (limit <= 0 || text.length <= limit) return text to false
        return text.take(limit) + TRUNCATION_SUFFIX to true
    }
}
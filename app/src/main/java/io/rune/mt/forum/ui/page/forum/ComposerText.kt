// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

/**
 * Result of one programmatic edit of a composer field: the new text and where the caret must land.
 *
 * The two composers ([NewThreadPage] and the private-message box in [ForumConversationPage]) insert
 * BBCode at the user's caret — image-host links, bold/italic/underline pairs, quote and link
 * brackets. Both fields are `TextFieldValue`-backed, so the caret is a real index and the insertion
 * must not just append at the end: a reply that inserts its picture after the first sentence would
 * otherwise land at the bottom of the message.
 */
data class ComposerEdit(val text: String, val caret: Int)

/**
 * The pure caret insertion logic, kept out of the composables so it can be asserted directly.
 *
 * Offsets are UTF-16 indices, exactly what Compose's `TextFieldValue.selection` reports, and every
 * entry point clamps the incoming caret into `0..text.length` — a stale caret from a field that was
 * reset (draft restore, screen rotation) must never throw.
 */
object ComposerText {

    /** Inserts [snippet] at [caret]; the caret ends up right after it. */
    fun insert(text: String, caret: Int, snippet: String): ComposerEdit {
        val at = caret.coerceIn(0, text.length)
        return ComposerEdit(
            text = text.substring(0, at) + snippet + text.substring(at),
            caret = at + snippet.length,
        )
    }

    /**
     * Inserts an empty BBCode pair at [caret] with the caret **between** the tags, so the user can
     * type straight into the markup.
     */
    fun wrap(text: String, caret: Int, open: String, close: String): ComposerEdit {
        val at = caret.coerceIn(0, text.length)
        return ComposerEdit(
            text = text.substring(0, at) + open + close + text.substring(at),
            caret = at + open.length,
        )
    }

    /** The BBCode pair of [name] (`b` → `[b]`/`[/b]`). */
    fun pair(name: String): Pair<String, String> = "[$name]" to "[/$name]"

    /** Convenience wrapper for a tag named [name] (`b`, `i`, `u`, `quote`, `url`). */
    fun wrapTag(text: String, caret: Int, name: String): ComposerEdit {
        val (open, close) = pair(name)
        return wrap(text, caret, open, close)
    }
}
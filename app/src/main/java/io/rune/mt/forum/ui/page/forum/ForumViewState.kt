// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

/**
 * State of the reply composer opened from the thread screen.
 *
 * Failures are carried as a string resource ([errorRes]) when the client itself knows the cause
 * ("not signed in", "thread not loaded yet"); a message returned by the server stays in [error]
 * because it is already human-readable and cannot be translated locally.
 */
data class ReplyState(
    val visible: Boolean = false,
    val text: String = "",
    val isSubmitting: Boolean = false,
    /** Localizable failure, resolved to text by the composer. */
    val errorRes: Int? = null,
    /** Server-provided failure text, shown verbatim. */
    val error: String? = null,
    /** Author of the floor being quoted, when the composer was opened from a 回复 tap. */
    val quoteAuthor: String? = null,
    /** Plain-text excerpt of the quoted floor, capped so the payload stays small. */
    val quoteText: String? = null,
)
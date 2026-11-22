// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

/**
 * State of the reply composer opened from the thread screen.
 *
 * [error] is a resolved, user-facing string rather than an enum: the failure modes here are
 * server-specific ("请先登录后再回复" vs a network error) and carrying the text keeps the UI dumb.
 */
data class ReplyState(
    val visible: Boolean = false,
    val text: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** Author of the floor being quoted, when the composer was opened from a 回复 tap. */
    val quoteAuthor: String? = null,
    /** Plain-text excerpt of the quoted floor, capped so the payload stays small. */
    val quoteText: String? = null,
)

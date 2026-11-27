// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import io.rune.mt.forum.domain.forum.model.ForumReplyDraft

/**
 * State of the reply composer opened from the thread screen.
 *
 * Failures are carried as a string resource ([errorRes]) when the client itself knows the cause
 * ("not signed in", "thread not loaded yet"); a message returned by the server stays in [error]
 * because it is already human-readable and cannot be translated locally.
 *
 * [pendingDraft] drives the 恢复草稿/丢弃 prompt for the stored draft of [tid]; while it is
 * non-null, dismissing the sheet does not overwrite that draft (see [ForumViewModel]).
 */
data class ReplyState(
    val visible: Boolean = false,
    /** Thread the composer answers; identifies the stored draft. */
    val tid: Int = 0,
    val text: String = "",
    val isSubmitting: Boolean = false,
    /** True while the composer's 保存草稿 is posting the draft to the site. */
    val isDraftBusy: Boolean = false,
    /** Localizable failure, resolved to text by the composer. */
    val errorRes: Int? = null,
    /** Server-provided failure text, shown verbatim. */
    val error: String? = null,
    /** Author of the floor being quoted, when the composer was opened from a 回复 tap. */
    val quoteAuthor: String? = null,
    /** Plain-text excerpt of the quoted floor, capped so the payload stays small. */
    val quoteText: String? = null,
    /** Stored draft found for [tid]; shown as a restore prompt until restored or discarded. */
    val pendingDraft: ForumReplyDraft? = null,
    /** Transient: an explicit save just finished; the sheet toasts it and clears the flag. */
    val draftSaved: Boolean = false,
    /** Set when the last draft save failed; the sheet shows it and keeps the text. */
    val draftError: String? = null,
)
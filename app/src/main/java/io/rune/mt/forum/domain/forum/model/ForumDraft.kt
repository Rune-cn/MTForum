// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

import kotlinx.serialization.Serializable

/**
 * A draft of the 发帖 (new thread) composer, kept **on this device only**.
 *
 * The site's own 保存草稿 button is not usable: the mobile form's `save=1` is layered on top of the
 * normal newthread POST, and a measured submit with `save=1` + `topicsubmit=yes` published a real
 * thread instead of storing a draft (see docs/REVERSE_SPEC.md §16). The client therefore persists
 * the composer locally; nothing here is ever sent to the server.
 *
 * Images are stored as content-URI strings only. The bytes are re-read when the draft is restored;
 * a URI whose permission/backing file is gone is reported as skipped instead of failing the restore.
 */
@Serializable
data class ForumNewThreadDraft(
    /** Board the thread would be posted to; also the identity of the draft. */
    val fid: Int,
    /** Board title as it was shown in the composer, so a restored draft keeps its label. */
    val forumName: String = "",
    val subject: String = "",
    val message: String = "",
    /** Content URIs of the attached images, in pick order. Never the image bytes. */
    val imageUris: List<String> = emptyList(),
    /** Epoch millis of the last save; the store evicts the oldest drafts first. */
    val savedAt: Long = 0L,
)

/** A locally stored draft of the reply composer, identified by the thread it answers. */
@Serializable
data class ForumReplyDraft(
    /** Thread the reply belongs to; also the identity of the draft. */
    val tid: Int,
    /** Author of the quoted floor, when the draft was started from a 回复 tap. */
    val quoteAuthor: String? = null,
    /** Plain-text excerpt of the quoted floor. */
    val quoteText: String? = null,
    val text: String = "",
    /** Epoch millis of the last save; the store evicts the oldest drafts first. */
    val savedAt: Long = 0L,
)

/**
 * The whole on-device draft store, serialized as one JSON string.
 *
 * Kept as a single document (rather than one preference key per draft) so a save is one atomic
 * write and the eviction cap can be enforced across both composers at once.
 */
@Serializable
data class ForumDraftIndex(
    val newThreads: List<ForumNewThreadDraft> = emptyList(),
    val replies: List<ForumReplyDraft> = emptyList(),
) {
    val size: Int get() = newThreads.size + replies.size
}
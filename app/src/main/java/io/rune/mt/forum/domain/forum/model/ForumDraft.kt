// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

import kotlinx.serialization.Serializable

/**
 * A draft that the **site** stores for the signed-in author (服务器草稿).
 *
 * The site's 保存草稿 button is real: it sets the form's hidden `save` field to `1` and submits the
 * same newthread/edit POST, and the server persists a real `thread-<tid>` that is hidden from the
 * board listing, invisible to guests, and shown in the 草稿 list
 * (`forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2`) with a 草稿 badge on its
 * thread page. See docs/REVERSE_SPEC.md §16.1.
 *
 * The list row does not print the draft's post id, so [pid] stays null until the draft is opened
 * through [ForumDraftEdit] (the edit form carries `pid`); the edit/delete flows use the harvested
 * form's own `pid` field.
 */
data class ForumDraft(
    val tid: Int,
    /** Board the draft belongs to, from the row's board link (`forum-<fid>-1.html`). */
    val fid: Int,
    /** Post id of the draft, when known (only after the edit form has been read). */
    val pid: Long? = null,
    val title: String = "",
    /** The row's excerpt (`div.list_body a`), which is the body preview the site prints. */
    val excerpt: String? = null,
    val forumName: String? = null,
    /** Relative timestamp as printed by the site (e.g. "5 分钟前"). */
    val timeText: String? = null,
    /** Absolute link of the draft's thread page, used as the row's identity/deep link. */
    val threadUrl: String? = null,
)

/** One page of the 草稿 list. The site paginates with `page=` like every other guide view. */
data class ForumDraftPage(
    val drafts: List<ForumDraft> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
)

/**
 * The edit form of a draft, harvested field by field.
 *
 * The server rejects (or silently ignores) a hand-built minimal field set, so every input of
 * `form#postform` is kept verbatim in [fields]: `formhash`, `posttime`, `delete`, `fid`, `tid`,
 * `pid`, `page`, `editsubmit`, `subject`, `message`, `htmlon`, `usesig`, plus the optional
 * checkbox fields. Publishing keeps [fields] and clears `save`; keeping the draft posts the same
 * fields with `save=1`; deleting posts them with `delete=1`.
 */
data class ForumEditForm(
    /** The form's own action (already normalised to a site-relative path with `mobile=2`). */
    val action: String,
    val formhash: String = "",
    val tid: Int = 0,
    val fid: Int = 0,
    val pid: Long? = null,
    val subject: String = "",
    val message: String = "",
    /** Every field of the form in document order, including the three above. */
    val fields: Map<String, String> = emptyMap(),
)

/** A draft opened for editing: the composer's contents plus the form needed to submit it. */
data class ForumDraftEdit(
    val tid: Int,
    val fid: Int,
    val pid: Long?,
    val subject: String,
    /** Body as BBCode, exactly what the site's textarea carried. */
    val message: String,
    val form: ForumEditForm,
)

/** Outcome of a draft write (save / publish / delete). */
sealed interface DraftActionResult {
    /**
     * The server accepted the write.
     *
     * [tid] is the thread the server redirected to (publish gives the now-public thread, a save
     * gives the draft's own thread) and stays null when the response carried no redirect.
     */
    data class Success(val message: String? = null, val tid: Int? = null) : DraftActionResult

    /** The site rejected the write, or the response proves it did not happen. */
    data class Failure(val reason: String) : DraftActionResult
}

/**
 * A draft of the 发帖 (new thread) composer, kept **on this device** as an offline safety net.
 *
 * Server drafts are the primary store now; this local copy exists so a composer whose text was
 * never accepted by the server (network failure, stale session) still survives process death. It
 * is written when a server save fails (or when the page is left before a save), never as the
 * primary save path.
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
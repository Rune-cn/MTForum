// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumDraft
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumReplyDraft
import io.rune.mt.forum.domain.forum.model.NewThreadImage

/**
 * Drafts of the 发帖/回复 composers, stored on the **site** for the signed-in author.
 *
 * The forum's own 保存草稿 button is a real feature (docs/REVERSE_SPEC.md §16.1): it sets the
 * form's hidden `save` field to `1` and submits the same newthread/edit POST. The server then keeps
 * a hidden `thread-<tid>` that only its author can see, that does not appear in the board listing,
 * and that is listed under 草稿. Drafts therefore survive a device change or a data wipe, which the
 * old client-local-only model could not do.
 *
 * All methods are `suspend` and the implementation performs its blocking HTTP/parse work inside
 * `withContext(Dispatchers.IO)`.
 */
interface ForumDraftService {

    /**
     * One page of the 草稿 list
     * (`forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2`).
     *
     * Throws [ForumLoginRequiredException] when the site answers with the login form: a guest has
     * no drafts and an empty list would be a lie.
     */
    suspend fun getDrafts(page: Int = 1): ForumDraftPage

    /** Saves (or overwrites) a new-thread draft in [fid] by POSTing the form with `save=1`. */
    suspend fun saveNewThreadDraft(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage> = emptyList(),
    ): DraftActionResult

    /**
     * Saves a reply draft of thread [tid] by POSTing the reply form with `save=1`.
     *
     * The reply form carries the same `save` field as the new-thread form; [message] is the full
     * body the composer would have posted (quote included), so the saved draft restores verbatim.
     */
    suspend fun saveReplyDraft(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String? = null,
    ): DraftActionResult

    /** Loads a draft's edit form and parses its subject/body so the composer can be filled. */
    suspend fun loadDraft(tid: Int, fid: Int, pid: Long? = null): ForumDraftEdit

    /**
     * Publishes a draft: POSTs the harvested edit form with `save` empty.
     *
     * This is the site's 发表 button, i.e. it makes the draft a real thread. The UI must only ever
     * call it behind an explicit confirmation.
     */
    suspend fun publishDraft(edit: ForumEditForm): DraftActionResult

    /** Deletes a draft: POSTs the harvested edit form with `delete=1` (the 删除 button). */
    suspend fun deleteDraft(edit: ForumEditForm): DraftActionResult

    /** Re-saves an already-open draft (the edit page's 保存草稿), keeping it a draft. */
    suspend fun updateDraft(edit: ForumEditForm, subject: String, message: String): DraftActionResult
}

/**
 * On-device storage for the 发帖/回复 composers, kept as an **offline safety net** next to the
 * server drafts in [ForumDraftService].
 *
 * Server drafts are the primary store now (see [ForumDraftService]); this local copy exists so
 * text that the server never accepted — a failed POST, a stale session, a composer abandoned
 * before any save — is not lost when the process dies. There is at most one draft per board (new
 * thread) and one per thread (reply), and the implementation caps the total to keep it bounded.
 *
 * All methods are `suspend` because the implementation touches the disk; callers are expected to
 * invoke them from a background dispatcher (the repository itself also wraps disk work in IO).
 */
interface ForumDraftRepository {

    /** The stored new-thread draft for [fid], or null when the board has none. */
    suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft?

    /** Stores [draft], replacing any previous draft of the same board. */
    suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft)

    /** Drops the stored new-thread draft of [fid]; a no-op when there is none. */
    suspend fun deleteNewThreadDraft(fid: Int)

    /** The stored reply draft for [tid], or null when the thread has none. */
    suspend fun replyDraft(tid: Int): ForumReplyDraft?

    /** Stores [draft], replacing any previous draft of the same thread. */
    suspend fun saveReplyDraft(draft: ForumReplyDraft)

    /** Drops the stored reply draft of [tid]; a no-op when there is none. */
    suspend fun deleteReplyDraft(tid: Int)

    /** Number of drafts currently stored across both composers (diagnostics/tests). */
    suspend fun draftCount(): Int
}
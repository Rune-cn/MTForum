// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumReplyDraft

/**
 * On-device storage for the 发帖/回复 composers.
 *
 * Drafts are client-local by design: the site's `save=1` field publishes instead of saving (see
 * [io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft]), so there is no server draft to sync
 * with. There is at most one draft per board (new thread) and one per thread (reply), and the
 * implementation caps the total to keep the store bounded.
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
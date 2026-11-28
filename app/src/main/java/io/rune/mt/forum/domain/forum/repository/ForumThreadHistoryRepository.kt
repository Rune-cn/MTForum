// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

import io.rune.mt.forum.domain.forum.model.ForumThreadHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Device-local list of the threads the user opened, most recently visited first.
 *
 * The thread page calls [record] after the first page of floors has loaded, which is the only
 * moment that proves the thread really opened (see `ForumThreadPage`). Nothing here talks to the
 * site: the list is a convenience for "where was I", so it exists for guests too.
 *
 * Implementations keep one entry per tid (a repeat visit only refreshes its timestamp), keep the
 * list bounded ([FORUM_THREAD_HISTORY_LIMIT]), and degrade a corrupt document to an empty list
 * rather than failing the screen.
 */
interface ForumThreadHistoryRepository {

    /** The stored visits, most recent first; emits again after every record/remove/clear. */
    val entries: Flow<List<ForumThreadHistoryEntry>>

    /**
     * Records a visit of [tid]. The same tid moves to the front with a fresh timestamp instead of
     * being stored twice; a non-positive tid is ignored.
     */
    suspend fun record(tid: Int, title: String, forumName: String?)

    /** Drops the entry of [tid]; a no-op when there is none. */
    suspend fun remove(tid: Int)

    /** Drops every entry (the page's 清空 action, behind its confirmation dialog). */
    suspend fun clear()
}

/** Maximum visits kept on the device; the oldest entries are evicted as new ones arrive. */
const val FORUM_THREAD_HISTORY_LIMIT = 100

/** A store that keeps nothing, used as the default for callers that do not record visits. */
object NoopForumThreadHistoryRepository : ForumThreadHistoryRepository {
    override val entries: Flow<List<ForumThreadHistoryEntry>> = flowOf(emptyList())
    override suspend fun record(tid: Int, title: String, forumName: String?) = Unit
    override suspend fun remove(tid: Int) = Unit
    override suspend fun clear() = Unit
}

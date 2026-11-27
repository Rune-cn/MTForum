// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

import kotlinx.coroutines.flow.Flow

/**
 * On-device list of the keywords the user searched for, most recent first.
 *
 * The home magnifier (`ForumSearchOverlay`) shows this list so repeating a search is one tap
 * instead of retyping it. It is purely a convenience: nothing about the site's search flow
 * (formhash → searchid, docs/HANDOVER_CN.md §15.1) depends on it, and a lost or corrupt list must
 * only cost the shortcuts, never a crash.
 *
 * Implementations keep the list bounded ([FORUM_SEARCH_HISTORY_LIMIT]) and de-duplicate
 * case-insensitively: searching the same word twice moves it to the front instead of appearing
 * twice.
 */
interface ForumSearchHistoryRepository {

    /** The stored keywords, most recent first; emits again after every record/clear. */
    val recent: Flow<List<String>>

    /** Moves [keyword] (trimmed) to the front; blank input is ignored. */
    suspend fun record(keyword: String)

    /** Drops every stored keyword. */
    suspend fun clear()
}

/** Maximum keywords kept on the device; older ones are evicted as new searches arrive. */
const val FORUM_SEARCH_HISTORY_LIMIT = 10
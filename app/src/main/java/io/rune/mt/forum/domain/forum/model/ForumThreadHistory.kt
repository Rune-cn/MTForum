// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

import kotlinx.serialization.Serializable

/**
 * One thread the user opened on this device (帖子浏览历史).
 *
 * The history is **device-local**: the thread page writes it once its first page of floors has
 * loaded, and it is visible signed in or out because it never leaves the phone (the site's
 * member-only rules apply to its own pages, not to a local shortcut list).
 *
 * One row per [tid]: opening the same thread again only bumps [visitedAt] and moves the row to the
 * front, so a repeatedly read thread cannot flood the list.
 */
@Serializable
data class ForumThreadHistoryEntry(
    /** Thread id; also the identity of the row, so a repeat visit keeps a single entry. */
    val tid: Int,
    /** Title as last seen; the history page falls back to `#tid` when the site gave none. */
    val title: String = "",
    /** Board the thread belongs to, read from the thread page breadcrumb; null when unknown. */
    val forumName: String? = null,
    /** Epoch millis of the most recent visit; the store keeps the newest first. */
    val visitedAt: Long = 0L,
)

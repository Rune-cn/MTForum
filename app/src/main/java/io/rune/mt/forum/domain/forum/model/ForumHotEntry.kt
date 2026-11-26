// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * One row of the site's own 热帖排行 block on the forum home page.
 *
 * The block is server-rendered and already ranked, so [rank] and the document order are both
 * meaningful and must never be recomputed client-side. The home page only shows rank, tid and
 * title; the block carries no author/reply/view data, so this model deliberately has no such
 * fields instead of inventing zeroes.
 */
data class ForumHotEntry(
    val rank: Int,
    val tid: Int,
    val title: String,
)
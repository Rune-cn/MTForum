// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/** A paged list of threads belonging to one board. */
data class ForumThreadPage(
    val fid: Int,
    val threads: List<ForumThread>,
    val page: Int = 1,
    val totalPages: Int = 1,
    val notice: List<ForumThread> = emptyList(),
)

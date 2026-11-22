// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/** Full thread view: first post plus replies plus paging info. */
data class ForumThreadDetail(
    val tid: Int,
    val fid: Int? = null,
    val title: String,
    val posts: List<ForumPost>,
    val page: Int = 1,
    val totalPages: Int = 1,
    val formhash: String? = null,
    val noticeAuthor: String? = null,
)

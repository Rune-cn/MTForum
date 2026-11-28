// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/** Full thread view: first post plus replies plus paging info. */
data class ForumThreadDetail(
    val tid: Int,
    val fid: Int? = null,
    val title: String,
    /**
     * Name of the board the thread belongs to, read from the breadcrumb (`a.kmtit`,
     * `#pt`/`div.comiis_nav`) or, for templates without a crumb, from the
     * `<title>标题 - 版块 - MT论坛</title>` shape.
     */
    val forumName: String? = null,
    val posts: List<ForumPost>,
    val page: Int = 1,
    val totalPages: Int = 1,
    val formhash: String? = null,
    val noticeAuthor: String? = null,
    /**
     * How many people 推荐ed (顶) the thread, from the OP's recommend strip.
     *
     * The count lives in `ul.comiis_recommend_list_a em.comiis_recommend_num` on the first page
     * only; later pages do not carry the OP strip, so they parse as 0 and the view model keeps the
     * page-1 value when appending.
     */
    val recommendCount: Int = 0,
)

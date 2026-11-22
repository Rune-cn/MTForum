// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/** A single reply / floor (楼层) inside a thread. */
data class ForumPost(
    val pid: Long,
    val tid: Int,
    val authorName: String,
    val authorUid: Int,
    val authorAvatarUrl: String? = null,
    val authorLevel: String? = null,
    val timeText: String? = null,
    val location: String? = null,
    /** Raw HTML of the post body; renderer must handle img/br/a. */
    val contentHtml: String,
    val floor: Int? = null,
    val isOp: Boolean = false,
)

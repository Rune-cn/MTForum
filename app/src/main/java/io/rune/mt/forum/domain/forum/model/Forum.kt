// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/** A discussion board (版块) from the BBS index. */
data class Forum(
    val fid: Int,
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val threadCount: Int? = null,
    val postCount: Int? = null,
    val todayCount: Int? = null,
    val lastThreadTitle: String? = null,
)

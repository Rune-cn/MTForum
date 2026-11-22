// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/** A thread row (主题帖) in a board listing. */
data class ForumThread(
    val tid: Int,
    val fid: Int? = null,
    val title: String,
    val summary: String? = null,
    val authorName: String,
    val authorUid: Int,
    val authorAvatarUrl: String? = null,
    val authorLevel: String? = null,
    val timeText: String? = null,
    val replyCount: Int = 0,
    val viewCount: Int = 0,
    val recommendCount: Int = 0,
    val pinned: Boolean = false,
    val digest: Boolean = false,
    /** Thumbnails attached to the post (empty when the thread has no pictures). */
    val imageUrls: List<String> = emptyList(),
)

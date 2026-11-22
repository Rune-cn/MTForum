// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/** Logged-in account / author profile summary. */
data class ForumUser(
    val uid: Int,
    val name: String,
    val avatarUrl: String? = null,
    val level: String? = null,
    val credit: String? = null,
    val isLoggedIn: Boolean = false,
)

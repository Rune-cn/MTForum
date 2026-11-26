// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * One page of the signed-in user's favourites (`home.php?mod=space&do=favorite&view=me&type=all`).
 *
 * The favourites page is the only authenticated page that prints the session's `formhash`
 * (inside its logout link), and the delete POST needs it. Keeping it next to the rows means the
 * screen can remove an entry without a second round trip just to fetch the token.
 */
data class ForumFavoritesPage(
    val favorites: List<ForumFavorite> = emptyList(),
    /** Session formhash printed by the favourites page; null when the page was not parsed. */
    val formhash: String? = null,
    val page: Int = 1,
    val totalPages: Int = 1,
)
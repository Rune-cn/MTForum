// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * Everything the forum home tab renders.
 *
 * [forums] is the union of the `forum.php` shortcut grid and the site's full board list
 * (`forum.php?forumlist=1`), both fetched inside the repository's single `getHome()` call so the
 * home screen still loads once. [hotRanking] comes from the home page only.
 */
data class ForumHomeData(
    val forums: List<Forum>,
    /** Site-ranked 热帖排行 rows, in document order (1..10). */
    val hotRanking: List<ForumHotEntry>,
)
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/** Everything the forum home tab renders, fetched from the single `forum.php` request. */
data class ForumHomeData(
    val forums: List<Forum>,
    /** Site-ranked 热帖排行 rows, in document order (1..10). */
    val hotRanking: List<ForumHotEntry>,
)
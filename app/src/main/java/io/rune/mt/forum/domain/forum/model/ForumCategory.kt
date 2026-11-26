// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * One 主题分类 tab of a board listing.
 *
 * [typeId] is the Discuz `typeid` the site puts in `forum.php?mod=forumdisplay&fid=..&filter=typeid&typeid=..`;
 * `null` is the strip's 全部 entry, which is the board's unfiltered listing. The strip is printed
 * above the rows of the same response, so the categories of a board are parsed together with its
 * threads and never cost a second request.
 */
data class ForumCategory(
    val typeId: Int?,
    val name: String,
)
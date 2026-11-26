// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

import androidx.annotation.StringRes
import io.rune.mt.forum.R

/**
 * The five tabs of the 导读 (guide) screen.
 *
 * [value] is the `view` query parameter of the site's guide page — it is part of the URL, so it
 * must match the site exactly. [labelRes] is the tab label shared by the home tab's 热帖排行
 * section and this screen.
 */
enum class ForumGuideView(
    val value: String,
    @StringRes val labelRes: Int,
) {
    Latest("newthread", R.string.forum_guide_tab_latest),
    Hot("hot", R.string.forum_guide_tab_hot),
    Digest("digest", R.string.forum_guide_tab_digest),
    Reply("new", R.string.forum_guide_tab_reply),
    Sofa("sofa", R.string.forum_guide_tab_sofa),
}
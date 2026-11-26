// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

import androidx.annotation.StringRes
import io.rune.mt.forum.R

/**
 * The 提醒 (notice inbox) tabs.
 *
 * [value] is the `view` query parameter of the site's notice page — the live site prints it that
 * way (`home.php?mod=space&do=notice&view=system|interactive|mypost&mobile=2`); `filter=` is the
 * desktop template's shape and returns the login wall for these pages.
 */
enum class ForumNoticeTab(
    val value: String,
    @StringRes val labelRes: Int,
) {
    /** 系统提醒: site/system messages, no author. */
    System("system", R.string.forum_notice_tab_system),

    /**
     * 回复我的: the site's own label is 坛友互动 (`view=interactive`) — pokes, friend requests,
     * wall posts, comments and likes addressed to the signed-in user.
     */
    Reply("interactive", R.string.forum_notice_tab_reply),

    /** 我的帖子: replies / comments / mentions on the signed-in user's own threads. */
    MyPost("mypost", R.string.forum_notice_tab_mypost),
}

/**
 * What a notice row is about, inferred from the action phrase inside the row body.
 *
 * The site does not tag rows with a machine-readable kind (all rows share
 * `<li class="b_b bg_f cl">`), so the phrase is the only signal: “回复了您的帖子” → [Reply],
 * “点评了您的帖子” → [Comment], “提到了您” → [Mention], “赞了/挺了” → [Like]. A row without an
 * author link is a site message ([System]); anything else stays [Other].
 */
enum class ForumNoticeKind { Reply, Comment, Mention, Like, System, Other }

/**
 * One row of the 提醒 inbox.
 *
 * Markup (live 2026-11-26, `view=mypost`):
 * `div.comiis_notice_list ul li` → `a.notice_img img` (avatar), `div.ntc_body` (author anchor +
 * action phrase + target link), `h2.f_d` (屏蔽 icon + relative time), `a[id^=a_note_]` (notice id).
 */
data class ForumNotice(
    /** The site's own notice id (`a_note_7011967`); null when a row carries no ignore link. */
    val id: Long? = null,
    val kind: ForumNoticeKind = ForumNoticeKind.Other,
    val authorName: String = "",
    val authorAvatarUrl: String? = null,
    /**
     * Row body HTML (`div.ntc_body`) with the author anchor and the trailing 查看 link removed:
     * the author is rendered separately, and the whole row is the tap target.
     */
    val contentHtml: String = "",
    /** Relative time as printed by the site ("3 天前"). */
    val timeText: String? = null,
    /** Target thread id, taken from `ptid=` in the row's post link; null when it links elsewhere. */
    val tid: Int? = null,
    /** Title of the target thread when the row links to one. */
    val threadTitle: String = "",
    /**
     * Unread emphasis. The live page renders every row as `<li class="b_b bg_f cl">` with no
     * unread marker (verified on a 13-row `view=mypost` page), so this stays false until the site
     * prints a marker; the UI already styles it (dot + medium weight) for when it does.
     */
    val unread: Boolean = false,
)

/** One page of one [tab]; [page] is 1-based and [totalPages] comes from the site's own pager. */
data class ForumNoticePage(
    val tab: ForumNoticeTab,
    val page: Int = 1,
    val totalPages: Int = 1,
    val notices: List<ForumNotice> = emptyList(),
)
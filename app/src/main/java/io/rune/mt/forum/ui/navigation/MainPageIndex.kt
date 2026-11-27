// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.navigation

/**
 * Pager page of the 论坛 home tab inside the main shell.
 *
 * The bottom bar is `论坛 · 导读 · 消息 · 设置`, so index 0 is the app's home. A home deep link
 * (`https://bbs.binmt.cc/forum.php`, `mtforum://home`) navigates to the shell and requests this
 * page instead of relying on whatever tab was open last.
 */
const val MAIN_PAGE_FORUM = 0

/**
 * Which page the shell's pager should open on.
 *
 * An explicit request (a home link arriving while the shell is composed) wins over the tab the
 * user last left the app on ([lastIndex]); the result is clamped so a stale value from an older
 * build cannot point outside the bar. With no request the previous tab is restored, which is the
 * pre-existing behaviour.
 */
internal fun resolveInitialMainPageIndex(
    lastIndex: Int,
    requestedIndex: Int?,
    pageCount: Int,
): Int {
    if (pageCount <= 0) return 0
    val target = requestedIndex ?: lastIndex
    return target.coerceIn(0, pageCount - 1)
}
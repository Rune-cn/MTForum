// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.navigation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Parser for the forum URLs this client can open internally.
 *
 * Shapes covered (Discuz's canonical forms, plus the app's own share text):
 *  - `https://bbs.binmt.cc/thread-<tid>-<page>-<author>.html`
 *  - `https://bbs.binmt.cc/forum-<fid>-<page>.html`
 *  - `https://bbs.binmt.cc/forum.php?mod=viewthread&tid=<tid>`
 *  - `https://bbs.binmt.cc/forum.php?mod=forumdisplay&fid=<fid>`
 *  - `https://bbs.binmt.cc/forum.php` / `forum.php?mod=index` — the **home** page
 *  - `mtforum://thread/<tid>`
 *  - `mtforum://forum/<fid>`
 *  - `mtforum://home` — the **home** page
 *
 * The home page is `Route.Main`: the shell's pager page 0 is the 论坛 tab, and a home link also
 * clears any deeper scroll/tab the shell was on (`SettingsSharedViewModel.requestMainPage`).
 *
 * Every caller is untrusted (browsers, other apps, `adb shell am start`), so each branch is a
 * total function: a malformed or incomplete URI returns `null` instead of throwing. The list and
 * thread pages load their own title from the site, hence the empty `title`/`name` in the routes.
 */
private const val FORUM_HOST = "bbs.binmt.cc"
private const val CUSTOM_SCHEME = "mtforum"
private const val FORUM_PHP_PATH = "/forum.php"
private const val HOME_HOST = "home"

private val THREAD_PATH = Regex("""^/thread-(\d+)(?:-\d+)*\.html$""", RegexOption.IGNORE_CASE)
private val BOARD_PATH = Regex("""^/forum-(\d+)(?:-\d+)*\.html$""", RegexOption.IGNORE_CASE)

/** Parses a VIEW intent payload into a navigation route, or `null` when it is not a forum link. */
internal fun forumDeepLinkFrom(uri: Uri?): Route? {
    val link = uri ?: return null
    return runCatching {
        when (link.scheme?.lowercase()) {
            // The manifest only declares https, but accepting http costs nothing: old copies of a
            // link still resolve, and the site redirects that scheme to https anyway.
            "http", "https" -> webRoute(link)
            CUSTOM_SCHEME -> customRoute(link)
            else -> null
        }
    }.getOrNull()
}

private fun webRoute(uri: Uri): Route? {
    if (!FORUM_HOST.equals(uri.host, ignoreCase = true)) return null
    val path = uri.path.orEmpty()
    THREAD_PATH.matchEntire(path)?.let { return threadRoute(it.groupValues[1]) }
    BOARD_PATH.matchEntire(path)?.let { return boardRoute(it.groupValues[1]) }
    if (!FORUM_PHP_PATH.equals(path, ignoreCase = true)) return null
    // forum.php carries the destination in the query string; an unknown mod is not our link.
    return when (val mod = uri.getQueryParameter("mod")?.lowercase()) {
        // The bare board index (and Discuz's `mod=index`) is the app's home: the 论坛 tab.
        null, "", "index" -> Route.Main
        "viewthread" -> threadRoute(uri.getQueryParameter("tid"))
        "forumdisplay" -> boardRoute(uri.getQueryParameter("fid"))
        else -> null
    }
}

private fun customRoute(uri: Uri): Route? {
    val id = uri.pathSegments?.firstOrNull()
    return when (uri.host?.lowercase()) {
        "thread" -> threadRoute(id)
        "forum" -> boardRoute(id)
        // `mtforum://home` (with or without a trailing slash path) is the board index.
        HOME_HOST -> Route.Main
        else -> null
    }
}

private fun threadRoute(rawId: String?): Route.ForumThread? =
    forumId(rawId)?.let { Route.ForumThread(it, "") }

private fun boardRoute(rawId: String?): Route.ForumList? =
    forumId(rawId)?.let { Route.ForumList(it, "") }

/** Discuz ids are positive ints; 0/negative/overflow/junk would only produce a dead page. */
private fun forumId(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it > 0 }

/**
 * One-shot deep-link hand-off between the Activity (which only sees intents) and the navigation
 * shell (which owns the back stack).
 *
 * The flow holds a route only until the shell has navigated to it: [onColdStart]/[onNewIntent]
 * put one in, [consume] clears it after the jump. Keeping the cold-start rule here instead of in
 * the Activity makes it testable without launching the whole Compose/Koin graph.
 */
internal class ForumDeepLinkState {
    private val mutableRoute = MutableStateFlow<Route?>(null)

    val route: StateFlow<Route?> = mutableRoute.asStateFlow()

    /**
     * A cold start is the only time the launch intent should navigate. A recreated activity
     * (language switch, process-death restore) still carries the original VIEW intent; replaying
     * it would yank the user away from the back stack they were actually on.
     */
    fun onColdStart(intent: Intent?, savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        offer(intent)
    }

    /** Warm start: the running single activity received another VIEW intent. */
    fun onNewIntent(intent: Intent?) {
        offer(intent)
    }

    fun consume() {
        mutableRoute.value = null
    }

    private fun offer(intent: Intent?) {
        // A non-forum intent (e.g. the launcher's MAIN intent while the app is open) must not
        // cancel a link that is already waiting for the next composition.
        val route = forumDeepLinkFrom(intent?.data) ?: return
        mutableRoute.value = route
    }
}
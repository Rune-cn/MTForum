// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.rune.mt.forum.ui.navigation.ForumDeepLinkState
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.navigation.forumDeepLinkFrom
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deep-link shape coverage: every supported forum URL parses to a route, everything else is
 * rejected without throwing. `Uri.parse` needs the Robolectric Android runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class ForumDeepLinkTest {

    private fun parse(url: String): Route? = forumDeepLinkFrom(Uri.parse(url))

    // ---- thread-<tid>-<page>-<author>.html -------------------------------------------------

    @Test
    fun `thread html path maps to the thread route`() {
        assertEquals(Route.ForumThread(171354, ""), parse("https://bbs.binmt.cc/thread-171354-1-1.html"))
        assertEquals(Route.ForumThread(171354, ""), parse("https://bbs.binmt.cc/thread-171354-3-2.html"))
        // Discuz also links a thread without the page/author suffix.
        assertEquals(Route.ForumThread(171354, ""), parse("https://bbs.binmt.cc/thread-171354-1.html"))
    }

    @Test
    fun `thread html path ignores query, fragment, scheme case and host case`() {
        assertEquals(
            Route.ForumThread(171354, ""),
            parse("https://bbs.binmt.cc/thread-171354-1-1.html?from=singlemessage#pid42"),
        )
        assertEquals(Route.ForumThread(171354, ""), parse("http://bbs.binmt.cc/thread-171354-1-1.html"))
        assertEquals(Route.ForumThread(171354, ""), parse("HTTPS://BBS.BINMT.CC/THREAD-171354-1-1.HTML"))
    }

    // ---- forum-<fid>-<page>.html ------------------------------------------------------------

    @Test
    fun `board html path maps to the board route`() {
        assertEquals(Route.ForumList(41, ""), parse("https://bbs.binmt.cc/forum-41-1.html"))
        assertEquals(Route.ForumList(41, ""), parse("https://bbs.binmt.cc/forum-41-5.html"))
        assertEquals(Route.ForumList(41, ""), parse("https://bbs.binmt.cc/forum-41.html"))
    }

    // ---- forum.php?mod=... ------------------------------------------------------------------

    @Test
    fun `viewthread query maps to the thread route`() {
        assertEquals(
            Route.ForumThread(171354, ""),
            parse("https://bbs.binmt.cc/forum.php?mod=viewthread&tid=171354"),
        )
        assertEquals(
            Route.ForumThread(171354, ""),
            parse("https://bbs.binmt.cc/forum.php?mod=viewthread&tid=171354&page=2&mobile=2"),
        )
    }

    @Test
    fun `forumdisplay query maps to the board route`() {
        assertEquals(
            Route.ForumList(41, ""),
            parse("https://bbs.binmt.cc/forum.php?mod=forumdisplay&fid=41&orderby=replies&page=5"),
        )
    }

    @Test
    fun `forum php without a mod is the home page`() {
        // The board index is the app's home: the shell navigates there and selects the 论坛 tab.
        assertEquals(Route.Main, parse("https://bbs.binmt.cc/forum.php"))
        assertEquals(Route.Main, parse("https://bbs.binmt.cc/forum.php?"))
        assertEquals(Route.Main, parse("https://bbs.binmt.cc/forum.php?mod=index"))
        assertEquals(Route.Main, parse("https://bbs.binmt.cc/forum.php?mod=INDEX&mobile=2"))
    }

    @Test
    fun `forum php with unknown or incomplete mod is ignored`() {
        assertNull(parse("https://bbs.binmt.cc/forum.php?mod=guide&view=hot&index=1&page=1"))
        assertNull(parse("https://bbs.binmt.cc/forum.php?mod=viewthread&fid=41"))
        assertNull(parse("https://bbs.binmt.cc/forum.php?mod=forumdisplay&tid=1"))
        // The query form only exists on forum.php, not on arbitrary site paths.
        assertNull(parse("https://bbs.binmt.cc/index.php?mod=viewthread&tid=1"))
        assertNull(parse("https://bbs.binmt.cc/forum.php/extra?mod=viewthread&tid=1"))
    }

    // ---- mtforum://... ---------------------------------------------------------------------

    @Test
    fun `custom scheme maps thread and board`() {
        assertEquals(Route.ForumThread(171354, ""), parse("mtforum://thread/171354"))
        assertEquals(Route.ForumList(41, ""), parse("mtforum://forum/41"))
        assertEquals(Route.ForumThread(171354, ""), parse("mtforum://THREAD/171354"))
    }

    @Test
    fun `custom scheme home is the app home page`() {
        assertEquals(Route.Main, parse("mtforum://home"))
        assertEquals(Route.Main, parse("mtforum://home/"))
        assertEquals(Route.Main, parse("mtforum://HOME"))
    }

    @Test
    fun `custom scheme rejects unknown hosts and missing ids`() {
        assertNull(parse("mtforum://thread"))
        assertNull(parse("mtforum://thread/"))
        assertNull(parse("mtforum://thread/abc"))
        assertNull(parse("mtforum://thread/0"))
        assertNull(parse("mtforum://forum/0"))
        assertNull(parse("mtforum://other/1"))
    }

    // ---- rejected / malformed --------------------------------------------------------------

    @Test
    fun `foreign hosts and paths are not forum links`() {
        assertNull(parse("https://example.com/thread-1-1-1.html"))
        assertNull(parse("https://bbs.binmt.cc/space-uid-1.html"))
        assertNull(parse("https://bbs.binmt.cc/thread-1-1-1.htm"))
        assertNull(parse("https://bbs.binmt.cc/thread/1"))
    }

    @Test
    fun `non positive or non numeric ids are rejected`() {
        assertNull(parse("https://bbs.binmt.cc/thread-abc-1-1.html"))
        assertNull(parse("https://bbs.binmt.cc/thread-0-1-1.html"))
        assertNull(parse("https://bbs.binmt.cc/thread--1-1.html"))
        assertNull(parse("https://bbs.binmt.cc/thread-9999999999-1-1.html"))
        assertNull(parse("https://bbs.binmt.cc/forum-abc-1.html"))
        assertNull(parse("https://bbs.binmt.cc/forum-0-1.html"))
        assertNull(parse("https://bbs.binmt.cc/forum.php?mod=viewthread&tid=abc"))
        assertNull(parse("https://bbs.binmt.cc/forum.php?mod=viewthread&tid=-1"))
    }

    @Test
    fun `junk input never throws`() {
        assertNull(forumDeepLinkFrom(null))
        assertNull(parse(""))
        assertNull(parse("not a uri"))
        assertNull(parse("::::"))
        assertNull(parse("https://"))
        assertNull(parse("mtforum:"))
    }

    // ---- intent -> one-shot state ----------------------------------------------------------

    @Test
    fun `cold start publishes the parsed route`() {
        val state = ForumDeepLinkState()
        state.onColdStart(intent("https://bbs.binmt.cc/thread-171354-1-1.html"), null)
        assertEquals(Route.ForumThread(171354, ""), state.route.value)
    }

    @Test
    fun `recreated activity does not replay the launch intent`() {
        val state = ForumDeepLinkState()
        state.onColdStart(intent("https://bbs.binmt.cc/thread-171354-1-1.html"), Bundle())
        assertNull(state.route.value)
    }

    @Test
    fun `warm intent updates the pending route`() {
        val state = ForumDeepLinkState()
        state.onNewIntent(intent("https://bbs.binmt.cc/thread-1-1-1.html"))
        assertEquals(Route.ForumThread(1, ""), state.route.value)

        state.onNewIntent(intent("mtforum://forum/7"))
        assertEquals(Route.ForumList(7, ""), state.route.value)

        state.onNewIntent(intent("mtforum://home"))
        assertEquals(Route.Main, state.route.value)
    }

    @Test
    fun `cold start from a home link publishes the shell route`() {
        val state = ForumDeepLinkState()
        state.onColdStart(intent("https://bbs.binmt.cc/forum.php"), null)
        assertEquals(Route.Main, state.route.value)

        state.onNewIntent(intent("mtforum://home"))
        assertEquals(Route.Main, state.route.value)
    }

    @Test
    fun `consume clears the route so it fires once`() {
        val state = ForumDeepLinkState()
        state.onNewIntent(intent("https://bbs.binmt.cc/thread-1-1-1.html"))
        assertEquals(Route.ForumThread(1, ""), state.route.value)

        state.consume()
        assertNull(state.route.value)
    }

    @Test
    fun `non forum intents leave the state untouched`() {
        val state = ForumDeepLinkState()
        state.onColdStart(intent("https://example.com/thread-1-1-1.html"), null)
        assertNull(state.route.value)

        state.onNewIntent(intent("https://bbs.binmt.cc/thread-1-1-1.html"))
        // A later unrelated intent (the launcher's MAIN intent) must not drop a pending link.
        state.onNewIntent(Intent(Intent.ACTION_MAIN))
        assertEquals(Route.ForumThread(1, ""), state.route.value)
    }

    private fun intent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
}
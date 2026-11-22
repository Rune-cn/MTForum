// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks against real captured pages.
 *
 * The site cannot be reached from CI and its anti-bot handshake is stateful, so the fixtures in
 * `src/test/resources/forum` are the regression baseline for the selectors.
 */
class ForumParserTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `board index yields the known fids`() {
        val forums = ForumHtmlParser.parseForums(fixture("index.html"))
        val names = forums.associate { it.fid to it.name }
        assertTrue("expected boards, got $names", forums.size >= 5)
        assertEquals("综合交流", names[44])
        assertEquals("逆向交流", names[41])
    }

    @Test
    fun `board listing parses threads and stats`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("board_44.html"), fid = 44, page = 1)
        assertTrue("expected threads", page.threads.isNotEmpty())
        val first = page.threads.first()
        assertEquals(177093, first.tid)
        assertEquals("论学习通刷课被回退三次的痛苦", first.title)
        assertEquals("wuti", first.authorName)
        assertEquals(150224, first.authorUid)
        assertTrue(first.replyCount > 0)
    }

    @Test
    fun `thread detail parses floors and reply credentials`() {
        val detail = ForumHtmlParser.parseThreadDetail(fixture("thread_177093.html"), tid = 177093, page = 1)
        assertTrue("expected posts", detail.posts.size >= 3)
        assertEquals(12177297L, detail.posts.first().pid)
        assertEquals("wuti", detail.posts.first().authorName)
        assertTrue(detail.posts.first().contentHtml.contains("DeepSeek"))
        assertEquals("5ed4cef8", detail.formhash)
        assertTrue(detail.noticeAuthor!!.startsWith("f2casQh"))
    }

    @Test
    fun `guide page parses through the board parser`() {
        // `view=hot` redirects to the portal home with no thread list; `view=newthread` is the
        // real 导读 page and shares the board listing's DOM. This pins that contract.
        val page = ForumHtmlParser.parseThreadPage(fixture("guide_newthread.html"), fid = 0, page = 1)
        assertTrue("guide must yield threads, got ${page.threads.size}", page.threads.size >= 10)
        assertTrue("guide threads need titles", page.threads.all { it.title.isNotBlank() })
        assertTrue("guide threads need tids", page.threads.all { it.tid > 0 })
    }
}

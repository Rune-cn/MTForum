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
    fun `pinned strip keeps tid title and the pinned flag`() {
        // On the live 综合交流 board the 置顶 strip is the four global announcements. That markup
        // is title-only (no byline), so the parser must at least keep the row identity and flag;
        // the full-row shape is covered by the fixture below.
        val page = ForumHtmlParser.parseThreadPage(fixture("board_44.html"), fid = 44, page = 1)
        assertTrue("expected pinned notices", page.notice.isNotEmpty())
        assertTrue("every notice row is pinned", page.notice.all { it.pinned })
        assertEquals(156696, page.notice.first().tid)
        assertEquals("【官方公告】论坛网盘使用推荐清单及管理规范更新公告", page.notice.first().title)
        assertTrue("pinned ids must be real", page.notice.all { it.tid > 0 })
    }

    @Test
    fun `pinned row with byline parses author avatar time and counts`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("board_pinned_row.html"), fid = 44, page = 1)
        val pinned = page.notice.single()
        assertEquals(177093, pinned.tid)
        assertEquals("论学习通刷课被回退三次的痛苦", pinned.title)
        assertTrue("pinned row must keep the pinned flag", pinned.pinned)
        assertEquals("wuti", pinned.authorName)
        assertTrue("author must not be blank", pinned.authorName.isNotBlank())
        assertEquals(150224, pinned.authorUid)
        val avatar = pinned.authorAvatarUrl
        assertTrue("avatar must be resolved, got $avatar", !avatar.isNullOrBlank())
        assertTrue("avatar must be absolute, got $avatar", avatar.orEmpty().startsWith("https://"))
        assertTrue("time must be parsed, got ${pinned.timeText}", pinned.timeText.orEmpty().contains("小时前"))
        assertEquals(4, pinned.replyCount)
        assertEquals(95, pinned.viewCount)
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
        // The mobile template prints the board in the `a.kmtit` header crumb, not in <title>
        // (`<title>标题 - MT论坛</title>` carries no board on this template).
        assertEquals("综合交流", detail.forumName)
    }

    @Test
    fun `guide page parses through the board parser`() {
        // `view=newthread` is the 最新 导读 list; it shares the board listing's DOM, which is why
        // the board parser can read every guide view (newthread/hot/digest/new/sofa) unchanged.
        val page = ForumHtmlParser.parseThreadPage(fixture("guide_newthread.html"), fid = 0, page = 1)
        assertTrue("guide must yield threads, got ${page.threads.size}", page.threads.size >= 10)
        assertTrue("guide threads need titles", page.threads.all { it.title.isNotBlank() })
        assertTrue("guide threads need tids", page.threads.all { it.tid > 0 })
    }
}

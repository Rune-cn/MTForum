// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.domain.forum.model.ForumNoticeKind
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks for the 提醒 (notice) inbox.
 *
 * Fixtures are trimmed copies of live pages captured on 2026-11-26 with a signed-in session
 * (`home.php?mod=space&do=notice&view=system|interactive|mypost&mobile=2`; the tab query values and
 * row markup were reverse-engineered the same way as the 私信/收藏 surfaces in
 * docs/REVERSE_SPEC.md §12–§16). `notices_mypost.html` keeps three of the thirteen real rows;
 * `notices_system.html` / `notices_interactive.html` are the real empty states, the latter with
 * the tab's `type=` sub-tabs kept in place so the test proves they are not read as rows.
 */
class ForumNoticesParserTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `tabs use the site view query values`() {
        // The live site switches tabs with `view=`; `filter=` (the desktop shape) answers with the
        // login form even for a signed-in session.
        assertEquals(
            listOf("system", "interactive", "mypost"),
            ForumNoticeTab.entries.map { it.value },
        )
        assertEquals(
            "each tab needs its own label resource",
            ForumNoticeTab.entries.size,
            ForumNoticeTab.entries.map { it.labelRes }.distinct().size,
        )
    }

    @Test
    fun `mypost notices parse author time and thread target`() {
        val page = ForumHtmlParser.parseNotices(
            fixture("notices_mypost.html"),
            tab = ForumNoticeTab.MyPost,
            page = 1,
        )
        assertEquals(3, page.notices.size)
        assertEquals(ForumNoticeTab.MyPost, page.tab)
        assertEquals(1, page.page)
        // The live page had no pager (13 rows, all of them on page one).
        assertEquals(1, page.totalPages)

        val first = page.notices.first()
        assertEquals("zhuibn", first.authorName)
        assertEquals(ForumNoticeKind.Reply, first.kind)
        assertEquals(7011967L, first.id)
        assertEquals(175701, first.tid)
        assertEquals("二改 RikkaHub 3.0.0", first.threadTitle)
        assertEquals("3 天前", first.timeText)
        assertTrue(
            "avatar must stay absolute for Coil: ${first.authorAvatarUrl}",
            first.authorAvatarUrl.orEmpty().startsWith("https://cdn.binmt.cc/"),
        )
        assertTrue("preview keeps the action phrase", first.contentHtml.contains("回复了您的帖子"))
        // The author is rendered on its own line and the whole row is the tap target, so the
        // preview must not repeat either.
        assertFalse("author link must be stripped", first.contentHtml.contains("space-uid-57676"))
        assertFalse("查看 anchor must be stripped", first.contentHtml.contains(">查看<"))

        assertTrue("every row needs a target tid", page.notices.all { (it.tid ?: 0) > 0 })
        assertTrue("every row needs an author", page.notices.all { it.authorName.isNotBlank() })
        assertTrue(
            "avatar of a real user must be parsed too",
            page.notices.drop(1).all { it.authorAvatarUrl.orEmpty().contains("/uc_server/data/avatar/") },
        )
    }

    @Test
    fun `unread stays false because the live page carries no marker`() {
        // Documented gap: all 13 live rows render as `<li class="b_b bg_f cl">` with no unread
        // marker, so the parser must not invent one. The UI already styles an unread row.
        val page = ForumHtmlParser.parseNotices(
            fixture("notices_mypost.html"),
            tab = ForumNoticeTab.MyPost,
            page = 1,
        )
        assertTrue(page.notices.all { !it.unread })
    }

    @Test
    fun `empty tabs parse to no rows and ignore the type sub-tabs`() {
        val system = ForumHtmlParser.parseNotices(
            fixture("notices_system.html"),
            tab = ForumNoticeTab.System,
            page = 1,
        )
        assertTrue("system tab is empty live", system.notices.isEmpty())

        val interactive = ForumHtmlParser.parseNotices(
            fixture("notices_interactive.html"),
            tab = ForumNoticeTab.Reply,
            page = 1,
        )
        assertTrue("interactive tab is empty live", interactive.notices.isEmpty())
        // 打招呼 / 好友 / 留言 … are `#comiis_sub li` sub-tabs, not notices.
        assertFalse(
            "sub-tabs must not become rows",
            interactive.notices.any { it.authorName in setOf("打招呼", "好友", "留言") },
        )
    }

    @Test
    fun `paging reads the site pager and echoes the requested page`() {
        // A `div.pg` block copied from the same mobile template (a live 导读 listing); the notice
        // tab pages print the identical markup once a tab has more than one page.
        val pager = """
            <div class="comiis_multi_box bg_f b_t" style="margin-top:10px;">
            <div class="pg"><strong>1</strong><a href="https://bbs.binmt.cc/home.php?mod=space&do=notice&view=mypost&page=2">2</a><a href="https://bbs.binmt.cc/home.php?mod=space&do=notice&view=mypost&page=3">3</a><a href="https://bbs.binmt.cc/home.php?mod=space&do=notice&view=mypost&page=12" class="last">.. 12</a><a href="https://bbs.binmt.cc/home.php?mod=space&do=notice&view=mypost&page=3" class="nxt">下一页</a></div></div>
        """.trimIndent()
        val page = ForumHtmlParser.parseNotices(
            fixture("notices_mypost.html") + pager,
            tab = ForumNoticeTab.MyPost,
            page = 3,
        )
        assertEquals(12, page.totalPages)
        assertEquals(3, page.page)
        assertEquals(3, page.notices.size)
    }

    @Test
    fun `login wall parses to no rows so the repository can report it`() {
        val loginPage = fixture("login_page.html")
        assertTrue(ForumHtmlParser.isLoginRequired(loginPage))
        val page = ForumHtmlParser.parseNotices(loginPage, tab = ForumNoticeTab.System, page = 1)
        assertTrue(page.notices.isEmpty())
        assertEquals(1, page.totalPages)
        assertNotNull(page.tab)
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.data.forum.network.ForumUrls
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks for the site's own draft feature (docs/REVERSE_SPEC.md §16.1).
 *
 * Fixtures are the pages captured while verifying that flow: the 草稿 list, a draft's edit form
 * (the page that carries the full hidden field set) and a draft's thread page (the 草稿 badge).
 * The point of these tests is the field harvest — the server rejects a hand-built partial field set,
 * so [ForumHtmlParser.parseDraftEdit] has to reproduce the form verbatim.
 */
class ForumDraftParserTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `draft list parses tid fid title excerpt board and time`() {
        val page = ForumHtmlParser.parseDraftPage(fixture("draft_draftlist_mobile.html"))

        assertTrue("expected drafts, got ${page.drafts.size}", page.drafts.size >= 2)
        val first = page.drafts.first()
        assertEquals(177367, first.tid)
        assertEquals("the board comes from forum-<fid>-1.html", 50, first.fid)
        assertEquals("FULLFORM-DRAFT-a71", first.title)
        assertEquals("full form draft", first.excerpt)
        assertEquals("休闲灌水", first.forumName)
        assertEquals("5 分钟前", first.timeText)
        assertEquals(
            "https://bbs.binmt.cc/thread-177367-1-1.html",
            first.threadUrl,
        )

        val second = page.drafts[1]
        assertEquals(177363, second.tid)
        assertEquals(44, second.fid)
        assertEquals("综合交流", second.forumName)
        // The excerpt keeps the site's own line break collapsed, not dropped.
        assertTrue(
            "excerpt should keep both lines, got ${second.excerpt}",
            second.excerpt?.contains("这是一条客户端草稿测试") == true,
        )
        assertTrue("rows must be de-duplicated by tid", page.drafts.map { it.tid }.distinct().size == page.drafts.size)
    }

    @Test
    fun `draft list carries no pager when the site prints none`() {
        val page = ForumHtmlParser.parseDraftPage(fixture("draft_draftlist_mobile.html"))
        // The captured list has no div.pg block, so it is a single page — inventing pages would
        // make the screen ask for lists the server never offered.
        assertEquals(1, page.totalPages)
        assertEquals(1, page.page)
    }

    @Test
    fun `edit form harvest keeps the whole hidden field set`() {
        val edit = ForumHtmlParser.parseDraftEdit(fixture("draft_edit_page.html"), tid = 0, fid = 0)

        // The ids the request itself needs.
        assertEquals(177367, edit.tid)
        assertEquals(50, edit.fid)
        assertEquals(12_205_913L, edit.pid)
        // ...and the body the composer restores.
        assertEquals("FULLFORM-DRAFT-a71", edit.subject)
        assertEquals("[b]full form draft[/b]", edit.message)

        val fields = edit.form.fields
        // Every field the edit POST needs, harvested — not a hand-built subset.
        listOf(
            "formhash",
            "posttime",
            "delete",
            "fid",
            "tid",
            "pid",
            "page",
            "editsubmit",
            "subject",
            "message",
            "htmlon",
            "usesig",
        ).forEach { name ->
            assertTrue("the harvested form must carry `$name`", fields.containsKey(name))
        }
        assertEquals("16385279", fields["formhash"])
        assertEquals("1795733897", fields["posttime"])
        assertEquals("0", fields["delete"])
        assertEquals("yes", fields["editsubmit"])
        assertEquals("0", fields["htmlon"])
        // The checkbox is checked on the live page, so a browser would submit it.
        assertEquals("1", fields["usesig"])
        // A disabled input is still sent by this client on purpose: Discuz expects `htmlon`.
        assertNotNull(fields["htmlon"])
        assertEquals("16385279", edit.form.formhash)
    }

    @Test
    fun `edit form action is normalised to a site-relative mobile path`() {
        val edit = ForumHtmlParser.parseDraftEdit(fixture("draft_edit_page.html"), tid = 0, fid = 0)
        val action = edit.form.action

        assertTrue("action must stay site-relative, got $action", !action.startsWith("http"))
        assertTrue("action must target the edit handler, got $action", action.contains("action=edit"))
        assertTrue("action must request the mobile template, got $action", action.contains("mobile=2"))
        assertTrue("action must be the edit submit, got $action", action.contains("editsubmit=yes"))
    }

    @Test
    fun `draft thread page exposes the post id and the draft badge`() {
        val html = fixture("draft_thread_view.html")

        assertEquals(12_205_913L, ForumHtmlParser.draftThreadPid(html))
        assertTrue("the 草稿 badge is what identifies a draft thread", ForumHtmlParser.isDraftThread(html))
    }

    @Test
    fun `a normal thread page is not reported as a draft`() {
        val html = fixture("thread_177093.html")
        assertTrue("a published thread must never read as a draft", !ForumHtmlParser.isDraftThread(html))
    }

    @Test
    fun `edit path drops an unknown pid instead of sending pid = 0`() {
        val withPid = ForumUrls.draftEditPath(fid = 50, tid = 177367, pid = 12_205_913L)
        assertTrue(withPid.contains("fid=50"))
        assertTrue(withPid.contains("tid=177367"))
        assertTrue(withPid.contains("pid=12205913"))
        assertTrue(withPid.contains("action=edit"))
        assertTrue(withPid.contains("mobile=2"))

        val withoutPid = ForumUrls.draftEditPath(fid = 50, tid = 177367, pid = null)
        assertTrue("a pending pid must be omitted, not sent as 0", !withoutPid.contains("pid="))
    }

    @Test
    fun `draft list path is the verified guide filter`() {
        val first = ForumUrls.draftListPath(1)
        assertEquals(
            "forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2",
            first,
        )
        // Page one is the bare verified URL; later pages append the page parameter.
        val second = ForumUrls.draftListPath(2)
        assertTrue(second.startsWith(first))
        assertTrue("paging must keep the mobile template", second.contains("page=2"))
        assertTrue(second.contains("filter=save"))
    }

    @Test
    fun `an empty draft list parses to an empty page rather than an error`() {
        val page = ForumHtmlParser.parseDraftPage(
            "<html><body><div class=\"comiis_tip\">暂时没有草稿</div></body></html>",
        )
        assertTrue(page.drafts.isEmpty())
        assertEquals(1, page.totalPages)
    }

    @Test
    fun `a page without an edit form falls back to the known action`() {
        val edit = ForumHtmlParser.parseDraftEdit(
            "<html><body><p>提示信息</p></body></html>",
            tid = 177367,
            fid = 50,
            pid = 12_205_913L,
        )
        // The request-side ids still identify the draft; nothing is invented from the markup.
        assertEquals(177367, edit.tid)
        assertEquals(50, edit.fid)
        assertEquals(12_205_913L, edit.pid)
        assertEquals("", edit.subject)
        assertEquals("", edit.message)
        assertTrue(edit.form.action.contains("action=edit"))
        assertTrue("an empty form carries no formhash", edit.form.formhash.isBlank())
        assertNull("a missing form must not report a fake field", edit.form.fields["formhash"])
    }
}
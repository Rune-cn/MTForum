// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.data.forum.network.ForumUrls
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the five 导读 views to the site, using real captured pages.
 *
 * All five views (最新/热门/精华/回复/抢沙发) render the same thread-list DOM as a board listing;
 * 热门 is a real list (not a portal redirect), and 抢沙发 has no pager on page 1.
 */
class ForumGuideViewsTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        return (File(path).takeIf { it.exists() } ?: File("app/$path")).readText()
    }

    private fun resource(relative: String): String {
        val path = "src/main/res/$relative"
        return (File(path).takeIf { it.exists() } ?: File("app/$path")).readText()
    }

    private fun stringValue(xml: String, name: String): String? =
        Regex("""<string name="$name">([^<]*)</string>""").find(xml)?.groupValues?.get(1)

    @Test
    fun `guide url builder covers all five views`() {
        assertEquals(
            "forum.php?mod=guide&view=newthread&index=1&page=1",
            ForumUrls.guidePath(ForumGuideView.Latest, 1),
        )
        assertEquals(
            "forum.php?mod=guide&view=hot&index=1&page=2",
            ForumUrls.guidePath(ForumGuideView.Hot, 2),
        )
        assertEquals(
            "forum.php?mod=guide&view=digest&index=1&page=1",
            ForumUrls.guidePath(ForumGuideView.Digest, 1),
        )
        assertEquals(
            "forum.php?mod=guide&view=new&index=1&page=3",
            ForumUrls.guidePath(ForumGuideView.Reply, 3),
        )
        assertEquals(
            "forum.php?mod=guide&view=sofa&index=1&page=1",
            ForumUrls.guidePath(ForumGuideView.Sofa, 1),
        )
    }

    @Test
    fun `guide views are the five site query values in site order`() {
        assertEquals(
            listOf("newthread", "hot", "digest", "new", "sofa"),
            ForumGuideView.entries.map { it.value },
        )
    }

    @Test
    fun `tab labels exist in all three languages`() {
        val en = resource("values/strings.xml")
        val zhCn = resource("values-zh-rCN/strings.xml")
        val zhTw = resource("values-zh-rTW/strings.xml")

        assertEquals(
            listOf(
                R.string.forum_guide_tab_latest,
                R.string.forum_guide_tab_hot,
                R.string.forum_guide_tab_digest,
                R.string.forum_guide_tab_reply,
                R.string.forum_guide_tab_sofa,
            ),
            ForumGuideView.entries.map { it.labelRes },
        )

        val expectedEn = listOf("Latest", "Hot", "Digest", "Replies", "Sofa")
        val expectedCn = listOf("最新", "热门", "精华", "回复", "抢沙发")
        val expectedTw = listOf("最新", "熱門", "精華", "回覆", "搶沙發")
        ForumGuideView.entries.forEachIndexed { index, view ->
            val key = when (view) {
                ForumGuideView.Latest -> "forum_guide_tab_latest"
                ForumGuideView.Hot -> "forum_guide_tab_hot"
                ForumGuideView.Digest -> "forum_guide_tab_digest"
                ForumGuideView.Reply -> "forum_guide_tab_reply"
                ForumGuideView.Sofa -> "forum_guide_tab_sofa"
            }
            assertEquals("en $key", expectedEn[index], stringValue(en, key))
            assertEquals("zh-rCN $key", expectedCn[index], stringValue(zhCn, key))
            assertEquals("zh-rTW $key", expectedTw[index], stringValue(zhTw, key))
        }

        // The home section title must match the site's own wording now that it is the same list.
        assertEquals("Hot threads", stringValue(en, "forum_hot_ranking"))
        assertEquals("热帖排行", stringValue(zhCn, "forum_hot_ranking"))
        assertEquals("熱帖排行", stringValue(zhTw, "forum_hot_ranking"))
    }

    @Test
    fun `hot guide page parses the shared thread list dom`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("guide_hot.html"), fid = 0, page = 1)
        assertEquals(3, page.threads.size)
        assertEquals(listOf(177065, 177326, 177302), page.threads.map { it.tid })
        assertEquals("汽水音乐优化模块", page.threads[0].title)
        assertEquals("让AI住进你的手机 全程离线，数据不出手机", page.threads[1].title)
        // The hot rows inline a heat-level icon-font glyph before the title; it must not leak.
        assertTrue(
            "titles must not contain icon-font codepoints",
            page.threads.none { thread -> thread.title.any { it in '\uE000'..'\uF8FF' } },
        )
        assertEquals(3, page.threads[0].recommendCount)
        assertEquals(87, page.threads[0].replyCount)
        assertEquals(578, page.threads[0].viewCount)
        // The real hot list is 12 pages; the pager is server-rendered (`pg` block).
        assertEquals(12, page.totalPages)
    }

    @Test
    fun `sofa guide page has no pager and holds unanswered threads`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("guide_sofa.html"), fid = 0, page = 1)
        assertEquals(3, page.threads.size)
        assertEquals(listOf(177335, 177334, 177332), page.threads.map { it.tid })
        assertEquals(
            "XPlayer 3.0.0 | 解锁会员 4K视频播放器，支持 4K/HDR 画质增强",
            page.threads[0].title,
        )
        // 抢沙发 lists threads with no replies yet; page 1 has 12 rows and no pager at all.
        assertTrue("sofa rows must have no replies", page.threads.all { it.replyCount == 0 })
        assertTrue("sofa rows must have no views", page.threads.all { it.viewCount == 0 })
        assertEquals(1, page.totalPages)
    }

    @Test
    fun `guide hot html keeps the five-tab bar`() {
        val html = fixture("guide_hot.html")
        ForumGuideView.entries.forEach { view ->
            assertNotNull(
                "guide sample must keep the ${view.value} tab link",
                Regex("""mod=guide&amp;view=${view.value}&amp;index=1""").find(html),
            )
        }
    }
}
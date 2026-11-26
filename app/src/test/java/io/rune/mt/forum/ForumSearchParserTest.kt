// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the in-forum search parser to real captured result pages (`search.php?mod=forum&searchid=…`).
 *
 * Both fixtures were captured from a signed-in session for the keyword 「MT管理器」; the site
 * rejects search for guests, which is why there is no anonymous capture.
 */
class ForumSearchParserTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        return (File(path).takeIf { it.exists() } ?: File("app/$path")).readText()
    }

    @Test
    fun `search page 1 parses rows, board fids and pager`() {
        val page = ForumHtmlParser.parseSearch(fixture("search_results.html"))

        assertEquals(1, page.page)
        assertEquals(50, page.totalPages)
        assertEquals(10, page.threads.size)

        val first = page.threads.first()
        assertEquals(177113, first.tid)
        // Search rows come from different boards: the fid is read from the row's forum link.
        assertEquals(2, first.fid)
        assertEquals("MT管理器2.26.12公测版", first.title)
        assertEquals("Bin", first.authorName)
        assertEquals(2, first.authorUid)
        assertEquals(15, first.recommendCount)
        assertEquals(38, first.replyCount)
        assertEquals(1248, first.viewCount)
        assertTrue(first.replyCount > 0)
    }

    @Test
    fun `search page 2 keeps the server page and can miss summaries`() {
        val page = ForumHtmlParser.parseSearch(fixture("search_results_p2.html"), page = 2)

        assertEquals(2, page.page)
        assertEquals(50, page.totalPages)
        assertEquals(10, page.threads.size)

        val first = page.threads.first()
        assertEquals(176450, first.tid)
        assertEquals("mt管理器大降价，填我推荐码多1个登录设备", first.title)
        assertEquals("哈哈改变", first.authorName)
        assertTrue("每行都应带板块 fid", (first.fid ?: 0) > 0)
    }
}
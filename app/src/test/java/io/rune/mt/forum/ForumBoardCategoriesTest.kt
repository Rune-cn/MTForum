// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks for the 主题分类 strip of a board listing.
 *
 * The Comiis template renders the strip in the same `#forumlist_time_li` swiper it uses for the
 * 全部/最新/热门/精华 sort switch, so the fixtures cover both shapes: a board that really has
 * categories (fid 38 建议反馈, five `typeid` tabs) and one that does not (fid 44 综合交流).
 */
class ForumBoardCategoriesTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `board with categories yields 全部 plus every typeid in site order`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("board_categories.html"), fid = 38, page = 1)
        assertEquals(
            listOf("全部", "BUG反馈", "意见反馈", "论坛问题", "已解决", "处理中"),
            page.categories.map { it.name },
        )
        assertEquals(listOf(null, 62, 63, 65, 64, 69), page.categories.map { it.typeId })
        assertTrue("the category strip must not disturb the row parse", page.threads.isNotEmpty())
        assertEquals(38, page.threads.first().fid)
    }

    @Test
    fun `board without categories keeps the unfiltered listing`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("board_44.html"), fid = 44, page = 1)
        assertTrue("fid 44 has no 主题分类, got ${page.categories}", page.categories.isEmpty())
        assertTrue("rows still parse", page.threads.isNotEmpty())
    }

    @Test
    fun `standalone strip parse agrees with the thread page parse`() {
        assertEquals(
            listOf(null, 62, 63, 65, 64, 69),
            ForumHtmlParser.parseCategories(fixture("board_categories.html")).map { it.typeId },
        )
        // 全部/最新/热门/精华 links carry no `typeid`, so they are never mistaken for categories.
        assertTrue(ForumHtmlParser.parseCategories(fixture("board_44.html")).isEmpty())
    }

    @Test
    fun `全部 is only emitted when the board really has categories`() {
        val categories = ForumHtmlParser.parseCategories(fixture("board_categories.html"))
        assertEquals(null, categories.first().typeId)
        assertEquals("全部", categories.first().name)
        assertEquals(categories.size, categories.map { it.typeId }.distinct().size)
    }
}
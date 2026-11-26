// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks for the board index (版块宫格).
 *
 * The home page (`forum.php`) only carries the eight 热版推荐 tiles plus the quick-post board
 * picker, so it never mentioned boards the user explicitly asked for (插件交流 / 官方公告 /
 * 申诉举报 …). `forum.php?forumlist=1` (the 社区 page) is the site's full list. Both fixtures are
 * the live pages, so the assertions pin down the real blocks instead of a guessed selector.
 */
class ForumBoardIndexTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `home page carries the grid then the boards only the quick-post picker lists`() {
        val forums = ForumHtmlParser.parseForums(fixture("index.html"))
        // Site order: the shortcut grid first (the site's own 热版推荐 order, never re-sorted),
        // then the boards the grid omits but the quick-post picker carries (38, 46, 53).
        assertEquals(listOf(42, 41, 44, 39, 50, 40, 2, 37, 38, 46, 53), forums.map { it.fid })
        assertEquals("boards must be de-duplicated by fid", forums.size, forums.map { it.fid }.distinct().size)
    }

    @Test
    fun `every board the user reported missing has its fid, name and icon`() {
        val forums = ForumHtmlParser.parseForums(fixture("index.html"))
        val expected = mapOf(
            44 to "综合交流",
            39 to "玩机交流",
            50 to "休闲灌水",
            40 to "求助问答",
            2 to "版本发布",
            37 to "插件交流",
            38 to "建议反馈",
            46 to "官方公告",
            53 to "申诉举报",
        )
        expected.forEach { (fid, name) ->
            val board = forums.firstOrNull { it.fid == fid }
                ?: error("board $fid ($name) missing, parsed fids=${forums.map { it.fid }}")
            assertEquals(name, board.name)
            val icon = board.iconUrl
            assertTrue("board $fid ($name) needs an icon URL, got $icon", !icon.isNullOrBlank())
            assertTrue("board $fid icon must be absolute, got $icon", icon!!.startsWith("https://"))
        }
    }

    @Test
    fun `full index lists every board including the one the home page omits`() {
        val forums = ForumHtmlParser.parseForums(fixture("forumlist.html"))
        // The 社区 page groups the boards under 专区 headings; the flattened order is the site's.
        assertEquals(
            listOf(2, 37, 38, 41, 39, 42, 40, 44, 50, 46, 53, 92),
            forums.map { it.fid },
        )
        assertEquals("站务专区", forums.last().name)
        forums.forEach { board ->
            assertTrue("board ${board.fid} needs a name, got '${board.name}'", board.name.isNotBlank())
            assertTrue(
                "board ${board.fid} needs an absolute icon, got ${board.iconUrl}",
                board.iconUrl?.startsWith("https://") == true,
            )
        }
    }

    @Test
    fun `home and full index merge into the complete site order`() {
        val merged = ForumHtmlParser.parseForums(
            listOf(fixture("index.html"), fixture("forumlist.html")),
        )
        assertEquals(
            listOf(42, 41, 44, 39, 50, 40, 2, 37, 38, 46, 53, 92),
            merged.map { it.fid },
        )
        assertEquals(merged.size, merged.map { it.fid }.distinct().size)
        assertTrue("the merged index must carry all boards, got ${merged.size}", merged.size == 12)
    }

    @Test
    fun `a single page still parses on its own`() {
        // Guards against the repository's second request becoming mandatory: when the full index
        // is unreachable, the home page alone still yields its 11 boards (no 92 站务专区).
        val homeOnly = ForumHtmlParser.parseForums(fixture("index.html"))
        assertEquals(11, homeOnly.size)
        assertTrue(homeOnly.none { it.fid == 92 })
    }
}
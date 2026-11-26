// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the home page's 热帖排行 block to the real server-rendered markup.
 *
 * The ranking is the site's own: rank numbers come from the `em` badges and the order must stay
 * exactly as served. The block carries only rank/title/link, so no reply/view data may be invented.
 */
class ForumHomeRankingTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        return (File(path).takeIf { it.exists() } ?: File("app/$path")).readText()
    }

    private val ranking by lazy { ForumHtmlParser.parseHotRanking(fixture("home_ranking.html")) }

    @Test
    fun `home ranking keeps the site order and badge numbers`() {
        assertEquals("home page must expose the ten site rows", 10, ranking.size)
        assertEquals((1..10).toList(), ranking.map { it.rank })
        assertEquals(
            listOf(177003, 176770, 176982, 176719, 177045, 176808, 176890, 176830, 176761, 176735),
            ranking.map { it.tid },
        )
    }

    @Test
    fun `home ranking row 1 matches the live site`() {
        val first = ranking.first()
        assertEquals(1, first.rank)
        assertEquals(177003, first.tid)
        assertEquals("破甲系统提示词 DeepSeek Flash 实测可用", first.title)
    }

    @Test
    fun `home ranking prefers the full title attribute over the truncated anchor text`() {
        // The anchor text of row 2 is cut at "我自己"; only the title attribute is complete.
        val second = ranking[1]
        assertEquals(
            "【开源】【安卓/IOS】花费60多亿Opus5.5.我终于Vibe了一个我自己满意的AI对话软件",
            second.title,
        )
        assertTrue("row 3 title must survive", ranking[2].title == "❤️逆向工坊正式发布！！！❤️")
        // No row may keep the leading rank digit that a.text() carries.
        assertTrue(ranking.none { it.title.firstOrNull()?.isDigit() == true })
    }
}
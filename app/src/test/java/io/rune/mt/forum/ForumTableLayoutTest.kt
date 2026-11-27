// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.compose.ui.text.AnnotatedString
import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import io.rune.mt.forum.ui.page.forum.tableColumnWeights
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the table column-sizing heuristic (`tableColumnWeights`).
 *
 * The layout bug is about *proportion*, not pixels: the old renderer gave every column the same
 * weight, so a five-column 网盘 table with one long 备注 column crushed all five to 1/5 width. The
 * heuristic is a pure function, so the regression is asserted directly on the weights instead of
 * on a bitmap.
 */
class ForumTableLayoutTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    private fun cell(text: String, span: Int = 1) =
        ForumBodyBlock.TableCell(text = AnnotatedString(text), span = span)

    /** The real 网盘 table: 备注 (last column) must be wider than 网盘名称 (first column). */
    @Test
    fun `netdisk table gives the prose column more weight than the short label column`() {
        val html = io.rune.mt.forum.data.forum.network.ForumHtmlParser
            .parseThreadDetail(fixture("rich_156696.html"), tid = 156696, page = 1)
            .posts.first { it.contentHtml.contains("<table") }
            .contentHtml
        val table = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Table>().first()
        val weights = tableColumnWeights(table.rows)

        assertEquals("the 网盘 table has five columns", 5, weights.size)
        assertTrue(
            "备注 column (${weights[4]}) must be wider than 网盘名称 column (${weights[0]})",
            weights[4] > weights[0],
        )
        // Short label columns still get a real minimum, so 「小飞机网盘」/「❌ 否」 stay on one line.
        assertTrue("the short column keeps a minimum width, got ${weights[0]}", weights[0] >= 64f)
        // The prose column is capped so it wraps over a few lines instead of hogging the whole row.
        assertTrue("the prose column is capped, got ${weights[4]}", weights[4] <= 200f)
    }

    /** Two short columns: both get the floor, so they read as two equal halves of the card. */
    @Test
    fun `two short columns get the minimum weight each`() {
        val weights = tableColumnWeights(
            listOf(
                listOf(cell("名"), cell("值")),
                listOf(cell("A"), cell("42")),
            ),
        )
        assertEquals(2, weights.size)
        assertEquals(weights[0], weights[1])
        assertEquals("short cells hit the minimum weight", 64f, weights[0])
    }

    /** Three columns with one prose column: only the prose column grows. */
    @Test
    fun `three column table widens only the long column`() {
        val prose = "这一列是很长很长的说明文字，会在窄屏上折成好几行，因此它应当占据更宽的空间"
        val weights = tableColumnWeights(
            listOf(
                listOf(cell("序号"), cell("网盘"), cell("说明")),
                listOf(cell("1"), cell("蓝奏云"), cell(prose)),
            ),
        )
        assertEquals(3, weights.size)
        assertTrue(
            "the prose column must be the widest, got $weights",
            weights[2] > weights[0] && weights[2] > weights[1],
        )
        assertTrue("short columns stay at the floor, got $weights", weights[0] == weights[1])
    }

    /** Every column is long: content no longer distinguishes them, so weights are equal. */
    @Test
    fun `all long columns fall back to equal weights`() {
        val long = "这是一段足够长的正文内容用于让每一列都被判定为长列"
        val weights = tableColumnWeights(
            listOf(
                listOf(cell(long), cell(long)),
                listOf(cell(long), cell(long)),
            ),
        )
        assertEquals(2, weights.size)
        assertEquals("all-long tables use equal weights", weights[0], weights[1])
    }

    /** A `colspan` cell is measured across the columns it covers, keeping the column count right. */
    @Test
    fun `colspan contributes to the column count and is shared across its columns`() {
        val weights = tableColumnWeights(
            listOf(
                listOf(cell("跨两列的表头", span = 2)),
                listOf(cell("左"), cell("右")),
            ),
        )
        assertEquals("the span-2 header defines two columns", 2, weights.size)
        assertEquals("the merged header is shared evenly", weights[0], weights[1])
    }
}

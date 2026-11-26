// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import java.io.File
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the richer Discuz constructs the body renderer must map onto dedicated blocks.
 *
 * Discuz renders posts to HTML server-side; a faithful client renderer therefore has to
 * recognise code panels, heading sizes, quotes, rules, lists, tables and attachment cards instead
 * of flattening everything into `<li>`-per-paragraph prose. Counts are always compared against the
 * DOM of the fixture, never invented.
 */
class ForumBodyRichParserTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    private fun posts(name: String): List<io.rune.mt.forum.domain.forum.model.ForumPost> =
        ForumHtmlParser.parseThreadDetail(fixture(name), tid = 166223, page = 1).posts

    private fun domCount(html: String, cssQuery: String): Int =
        Jsoup.parseBodyFragment(html).select(cssQuery).size

    @Test
    fun `op code blocks are one block each with preserved multi-line text`() {
        val posts = posts("rich_166223.html")
        val opHtml = posts.first().contentHtml
        val domCount = domCount(opHtml, "div.comiis_blockcode")
        val parsed = ForumBodyParser.parse(opHtml).filterIsInstance<ForumBodyBlock.CodeBlock>()

        assertTrue("the thread OP should carry many code panels, got $domCount", domCount > 10)
        assertEquals("every comiis_blockcode panel must become exactly one block", domCount, parsed.size)

        val colorLine = parsed.firstOrNull { it.text.contains("处理颜色和背景色") }
        assertTrue("expected a block holding the known code line, got ${parsed.size} blocks", colorLine != null)
        assertTrue("the block must preserve multiple lines", colorLine!!.text.contains("\n"))
        assertFalse("code lines must not be separated by blank lines, got: " + colorLine.text.replace("\n", "\\n"), colorLine.text.contains("\n\n"))
        // The escaped markup must survive as text, not be stripped as an unknown tag.
        assertTrue("code HTML entities should decode to literal text, got:\n${colorLine.text}", colorLine.text.contains("<font"))
    }

    @Test
    fun `size six text becomes a title heading`() {
        val opHtml = posts("rich_166223.html").first().contentHtml
        val headings = ForumBodyParser.parse(opHtml).filterIsInstance<ForumBodyBlock.Heading>()
        val title = headings.firstOrNull { it.level == ForumBodyBlock.HeadingLevel.TITLE }
        assertTrue("expected a TITLE heading, got $headings", title != null)
        assertTrue("the heading text must survive, got ${title!!.text.text}", title.text.text.contains("什么是bbcode"))
    }

    @Test
    fun `hr produces one divider per rule in the body`() {
        val html = posts("rich_166223.html").first { it.contentHtml.contains("hr class") }.contentHtml
        val domCount = domCount(html, "hr.l")
        val dividers = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Divider>().size
        assertTrue("fixture should contain rules, got $domCount", domCount > 0)
        assertEquals(domCount, dividers)
    }

    @Test
    fun `table cells are preserved as a grid`() {
        val html = posts("rich_bbcode_reply.html").first { it.contentHtml.contains("<table") }.contentHtml
        val tables = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Table>()
        assertTrue("expected a table block, got none", tables.isNotEmpty())
        val cells = tables.flatMap { it.rows }.flatten().map { it.text }
        assertTrue("cell A must survive, got $cells", cells.any { it.contains("单元格A") })
        assertTrue("cell B must survive, got $cells", cells.any { it.contains("单元格B") })
    }

    @Test
    fun `attachment becomes a card with name size line and download url`() {
        val html = posts("rich_166223.html").first { it.contentHtml.contains("comiis_attach") }.contentHtml
        val attachments = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Attachment>()
        assertEquals(domCount(html, "div.comiis_attach"), attachments.size)
        val attach = attachments.first()
        assertTrue("filename expected, got ${attach.name}", attach.name.contains("Converter-0.2"))
        assertTrue("size line expected, got ${attach.caption}", attach.caption?.contains("67.99 KB") == true)
        assertTrue("download url expected, got ${attach.url}", attach.url.contains("mod=attachment"))
        assertTrue("url must be absolute, got ${attach.url}", attach.url.startsWith("https://"))
    }

    @Test
    fun `quoted reply keeps a dimmed author header and its body`() {
        val html = posts("rich_166223.html")
            .first { it.contentHtml.contains("comiis_quote") && it.contentHtml.contains("发表于") }
            .contentHtml
        val quote = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Quote>().first()
        assertTrue("author header expected, got ${quote.author}", quote.author?.contains("发表于") == true)
        assertTrue("quote body expected, got ${quote.text.text}", quote.text.text.isNotBlank())
        assertFalse("the author line must not be duplicated into the body", quote.text.text.contains("发表于"))
    }

    @Test
    fun `bbcode code body is escaped so angle brackets stay literal`() {
        val blocks = ForumBodyParser.parse("[code]<app>[/code]")
        val code = blocks.filterIsInstance<ForumBodyBlock.CodeBlock>().firstOrNull()
        assertTrue("expected a code block, got $blocks", code != null)
        assertEquals("<app>", code!!.text.trim())
    }
}

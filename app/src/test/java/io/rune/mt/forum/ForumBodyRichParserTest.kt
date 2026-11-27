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
        val cells = tables.flatMap { it.rows }.flatten().map { it.text.text }
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
    // ---- Fidelity fixes, each pinned to the fixture that proves it -------------------------

    /**
     * `rich_156696.html` is the community's 「网盘」 comparison table: five columns, and its
     * *first row* is written as bold cells (`<td><div align="center"><strong>网盘名称</strong></div>`)
     * because Discuz's `[table]` has no `<th>`. A faithful renderer has to notice that idiom and
     * draw the row as a header, otherwise the table reads as five data rows with no headings.
     */
    @Test
    fun `table header idiom is promoted and cell alignment survives`() {
        val html = posts("rich_156696.html").first { it.contentHtml.contains("<table") }.contentHtml
        val table = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Table>().first()
        val header = table.rows.first()
        assertTrue(
            "the bold first row must be recognised as a header, got ${header.map { it.header }}",
            header.all { it.header },
        )
        assertTrue(
            "header cells must keep their centred alignment, got ${header.map { it.align }}",
            header.all { it.align == ForumBodyBlock.BlockAlign.CENTER },
        )
        assertFalse(
            "data rows must not be promoted to headers",
            table.rows.drop(1).any { row -> row.all { it.header } },
        )
        // Every cell's text must survive: no column may be dropped to keep the grid square.
        val texts = table.rows.flatten().map { it.text.text }
        assertTrue("expected 网盘名称, got $texts", texts.any { it.contains("网盘名称") })
        assertTrue("expected 蓝奏云, got $texts", texts.any { it.contains("蓝奏云") })
    }

    /**
     * `rich_bbcode_reply.html` (and its page-2 copy) is the reply that puts nearly every tag in
     * one post, including a bare `<div align="center">居中</div>`. That wrapper is block-level: it
     * must become an `Aligned` group rather than a paragraph that merely happens to be centred.
     */
    @Test
    fun `align wrapper becomes a block group`() {
        val html = posts("rich_bbcode_reply.html").first { it.contentHtml.contains("align=") }.contentHtml
        val aligned = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Aligned>()
        assertTrue("expected an Aligned block, got none", aligned.isNotEmpty())
        assertTrue(
            "the wrapped text must survive, got ${aligned.map { it.blocks }}",
            aligned.any { group ->
                group.blocks.any { it is ForumBodyBlock.Paragraph && it.text.text.contains("居中") }
            },
        )
        assertEquals(
            "centre-aligned wrapper expected, got ${aligned.map { it.align }}",
            ForumBodyBlock.BlockAlign.CENTER,
            aligned.first().align,
        )
    }

    /**
     * Only three `comiis_quote` blocks exist in the main fixture, and two of them are long enough
     * that Discuz truncates them server-side and appends a literal ` ...`. Showing those ASCII dots
     * as if the author typed them is wrong; the quote must report [ForumBodyBlock.Quote.truncated]
     * so the renderer can draw a real ellipsis row.
     */
    @Test
    fun `server-truncated quote reports truncation and drops the ascii dots`() {
        val quotes = posts("rich_166223.html")
            .flatMap { post -> ForumBodyParser.parse(post.contentHtml) }
            .filterIsInstance<ForumBodyBlock.Quote>()
        assertTrue("fixture should contain quotes, got ${quotes.size}", quotes.isNotEmpty())
        val truncated = quotes.filter { it.truncated }
        assertTrue(
            "the long quoted replies must be marked truncated, got ${quotes.map { it.truncated }}",
            truncated.isNotEmpty(),
        )
        assertTrue(
            "the literal ' ...' marker must not remain in the body, got: " +
                truncated.joinToString(" || ") { it.text.text.takeLast(20) },
            truncated.none { it.text.text.trimEnd().endsWith("...") },
        )
        assertTrue(
            "an untruncated quote must not be marked, got ${quotes.map { it.truncated }}",
            quotes.any { !it.truncated },
        )
    }

    /**
     * A quoted post can itself be formatted: `[quote]` around a list/table/code panel is the
     * natural combination, and the fixture set proves each piece exists in isolation. The quote
     * must keep those blocks as children instead of flattening them to one text run.
     */
    @Test
    fun `quote keeps nested structure as child blocks`() {
        val html = """
            <div class="comiis_quote bg_h f_c"><blockquote>
            <font size="2">回复</font> <font color="#999999">tester 发表于 2026-5-8 17:31</font><br />
            列表：<br />
            <ul><li>条目一<li>条目二</ul>
            <table><tr><td>单元格A</td><td>单元格B</td></tr></table>
            <hr class="l" />
            </blockquote></div>
        """.trimIndent()
        val quote = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Quote>().single()
        assertEquals("作者行必须解析出来", "tester 发表于 2026-5-8 17:31", quote.author)
        assertTrue(
            "the intro text must be the quote body, got: ${quote.text.text}",
            quote.text.text.contains("列表"),
        )
        assertTrue(
            "a list inside the quote must stay a list, got ${quote.children}",
            quote.children.any { it is ForumBodyBlock.BulletList },
        )
        assertTrue(
            "a table inside the quote must stay a table, got ${quote.children}",
            quote.children.any { it is ForumBodyBlock.Table },
        )
        assertTrue(
            "an hr inside the quote must stay a divider, got ${quote.children}",
            quote.children.any { it is ForumBodyBlock.Divider },
        )
        assertFalse(
            "the byline must not leak into the body, got: ${quote.text.text}",
            quote.text.text.contains("发表于"),
        )
    }

    /**
     * A `[code]` panel whose lines contain literal BBCode/markup must survive as code, not be
     * re-parsed. `rich_166223.html` is exactly this case: its panels quote `<font …>` and `[b]`
     * fragments. The `[code]` draft path is the other side of the same coin.
     */
    @Test
    fun `code panel keeps literal bbcode and markup`() {
        val html = posts("rich_166223.html")
            .first { it.contentHtml.contains("<font") && it.contentHtml.contains("comiis_blockcode") }
            .contentHtml
        val code = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.CodeBlock>()
        assertTrue(
            "the escaped <font literal must survive inside a code panel",
            code.any { it.text.contains("<font") },
        )
        assertTrue(
            "the literal [b] token from the panel must survive",
            code.any { it.text.contains("[b]") },
        )

        // A raw BBCode draft (what this client sends) takes the conversion path instead, and the
        // code body must still come out verbatim.
        val draft = ForumBodyParser.parse("[code][b]literal[/b]\n<app>[/code]")
            .filterIsInstance<ForumBodyBlock.CodeBlock>()
            .single()
        assertTrue("draft code must keep [b], got: ${draft.text}", draft.text.contains("[b]"))
        assertTrue("draft code must keep <app>, got: ${draft.text}", draft.text.contains("<app>"))
    }

    /**
     * Deeply nested lists (three levels) and an `li` that carries text *after* its nested list.
     *
     * The fixture set proves site posts nest `ul`/`ol` inside `li` (the board and guide samples do
     * it on every row); two levels were handled, but the recursion also has to keep the trailing
     * text of the parent item and must not invent an empty bullet for a pure container.
     */
    @Test
    fun `list nesting is not limited to two levels`() {
        val html = """
            <ul>
              <li>一级
                <ul>
                  <li>二级
                    <ol><li>三级</li></ol>
                    二级后的文字
                  </li>
                </ul>
              </li>
              <li>一级末尾</li>
            </ul>
        """.trimIndent()
        val list = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.BulletList>().single()
        assertEquals(
            "three depths expected, got ${list.items.map { it.depth to it.text.text }}",
            setOf(0, 1, 2),
            list.items.map { it.depth }.toSet(),
        )
        val deepest = list.items.first { it.depth == 2 }
        assertTrue("deepest item text expected, got ${deepest.text.text}", deepest.text.text.contains("三级"))
        assertTrue(
            "an ordered nested list must use numeric markers, got ${deepest.marker}",
            deepest.marker.endsWith("."),
        )
        assertTrue(
            "text after a nested list must stay on the parent row, got ${list.items.map { it.text.text }}",
            list.items.any { it.depth == 1 && it.text.text.contains("二级后的文字") },
        )
        assertTrue(
            "outer text must survive, got ${list.items.map { it.text.text }}",
            list.items.any { it.depth == 0 && it.text.text.contains("一级末尾") },
        )
    }

    /**
     * `<img>` inside a quote: the site renders it, so the client must not silently drop it. It is
     * exposed as [ForumBodyBlock.Quote.images] (a compact thumbnail) rather than a full-width
     * picture, because a full-bleed image inside an inset quote would dominate the reply.
     */
    @Test
    fun `images inside a quote are kept as thumbnails`() {
        val html = """
            <div class="comiis_quote bg_h f_c"><blockquote>
            <font color="#999999">tester 发表于 2026-5-8 17:31</font><br />
            看图：<img src="https://icdn.binmt.cc/2509/quote.jpg" border="0" alt="" />
            </blockquote></div>
        """.trimIndent()
        val quote = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Quote>().single()
        // The picture is an ordered child block, not a side list: that keeps its position in
        // the quoted body and prevents it from being rendered twice.
        val pictures = quote.children.filterIsInstance<ForumBodyBlock.Picture>().map { it.url }
        assertEquals(
            "the quoted picture must be a child block, got $pictures",
            listOf("https://icdn.binmt.cc/2509/quote.jpg"),
            pictures,
        )
    }

    /**
     * The attachment's file-type badge is an icon, never the attachment itself. It must be exposed
     * as [ForumBodyBlock.Attachment.iconUrl] so the card can draw it small instead of feeding a
     * `.gif` badge to the full-width picture block.
     */
    @Test
    fun `attachment exposes its file type icon separately from a preview`() {
        val html = posts("sample_attach_thread.html").first { it.contentHtml.contains("comiis_attach") }.contentHtml
        val attach = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Attachment>().first()
        assertTrue(
            "the filetype badge must be captured, got ${attach.iconUrl}",
            attach.iconUrl?.contains("static/image/filetype") == true,
        )
        assertTrue(
            "a .mtp attachment is not an image, got isImage=${attach.isImage}",
            !attach.isImage,
        )
        assertEquals("no preview expected for a non-image", null, attach.previewUrl)
    }
}

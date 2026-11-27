// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the inline formatting the post renderer must keep.
 *
 * Discuz bodies are HTML (not BBCode); the first renderer flattened them to plain text by
 * accident, which silently dropped bold/italic/links/colour. These assertions fail if that
 * regresses, and they double as the specification for the tag → [SpanStyle] mapping.
 */
class ForumRichTextTest {

    private fun parse(html: String) = ForumBodyParser.parse(html, linkColor = Color(0xFF0000FF))

    private fun firstParagraph(html: String): ForumBodyBlock.Paragraph =
        parse(html).filterIsInstance<ForumBodyBlock.Paragraph>().first()

    private fun stylesOf(html: String): List<SpanStyle> =
        firstParagraph(html).text.spanStyles.map { it.item }

    @Test
    fun `bold survives`() {
        val styles = stylesOf("<div>普通<strong>加粗</strong></div>")
        assertTrue("expected FontWeight.Bold, got $styles", styles.any { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `italic survives`() {
        val styles = stylesOf("<div>普通<em>斜体</em>和<i>也是斜体</i></div>")
        assertTrue("expected FontStyle.Italic, got $styles", styles.any { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `underline and strike survive`() {
        val styles = stylesOf("<div><u>下划线</u><s>删除线</s></div>")
        assertTrue(styles.any { it.textDecoration == TextDecoration.Underline })
        assertTrue(styles.any { it.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `links are coloured and underlined`() {
        val styles = stylesOf("""<div><a href="https://bbs.binmt.cc/x">链接</a></div>""")
        val link = styles.firstOrNull { it.textDecoration == TextDecoration.Underline }
        assertTrue("expected link style, got $styles", link != null)
        assertEquals(Color(0xFF0000FF), link!!.color)
    }

    @Test
    fun `font color survives`() {
        val styles = stylesOf("""<div><font color="#FF0000">红字</font></div>""")
        assertTrue("expected a coloured span, got $styles", styles.any { it.color == Color(0xFFFF0000) })
    }

    @Test
    fun `nested formatting does not leak past its run`() {
        val paragraph = firstParagraph("<div>甲<strong>乙</strong>丙</div>")
        val text = paragraph.text.text
        val bold = paragraph.text.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        // The bold span must cover exactly "乙", not the whole paragraph.
        assertEquals("乙", text.substring(bold.start, bold.end))
    }

    @Test
    fun `blockquote becomes its own block`() {
        val blocks = parse("<div>回复内容</div><blockquote>被引用的原文</blockquote>")
        val quote = blocks.filterIsInstance<ForumBodyBlock.Quote>().firstOrNull()
        assertTrue("expected a Quote block, got $blocks", quote != null)
        assertTrue(quote!!.text.text.contains("被引用的原文"))
    }

    @Test
    fun `real thread keeps its bold runs`() {
        val html = java.io.File("src/test/resources/forum/thread_177093.html")
            .takeIf { it.exists() }
            ?.readText()
            ?: java.io.File("app/src/test/resources/forum/thread_177093.html").readText()
        val posts = io.rune.mt.forum.data.forum.network.ForumHtmlParser
            .parseThreadDetail(html, tid = 177093, page = 1)
            .posts
        // The fixture thread is plain prose; what matters is that nothing is dropped.
        val text = posts.map { p -> ForumBodyParser.parse(p.contentHtml).filterIsInstance<ForumBodyBlock.Paragraph>() }
            .flatten()
            .joinToString("\n") { it.text.text }
        assertTrue(text.contains("DeepSeek"))
        assertFalse("raw tags must never leak", text.contains("<strong>"))
        assertFalse("html entities must be decoded", text.contains("&nbsp;"))
    }
    @Test
    fun `attachment images inside a post become picture blocks`() {
        val html = """<div class="comiis_a comiis_message_table cl">
            |文字<img src="https://icdn.binmt.cc/2509/x.jpg" border="0" alt="" />更多文字
            |</div>""".trimMargin()
        val blocks = ForumBodyParser.parse(html)
        val pictures = blocks.filterIsInstance<ForumBodyBlock.Picture>()
        assertTrue("expected a picture block, got $blocks", pictures.isNotEmpty())
        assertEquals("https://icdn.binmt.cc/2509/x.jpg", pictures.first().url)
    }

    @Test
    fun `real post bodies keep their attachment pictures`() {
        val html = java.io.File("src/test/resources/forum/rich_156696.html")
            .takeIf { it.exists() }
            ?.readText()
            ?: java.io.File("app/src/test/resources/forum/rich_156696.html").readText()
        val posts = io.rune.mt.forum.data.forum.network.ForumHtmlParser
            .parseThreadDetail(html, tid = 156696, page = 1)
            .posts
        val pictureCount = posts.sumOf { p ->
            ForumBodyParser.parse(p.contentHtml).filterIsInstance<ForumBodyBlock.Picture>().size
        }
        assertTrue("expected attachment pictures, got $pictureCount", pictureCount > 0)
    }

    @Test
    fun `bbcode quote and emphasis are converted`() {
        val html = "[quote][b]wuti[/b]\n原帖内容[/quote]\n我的回复"
        val blocks = ForumBodyParser.parse(html)
        val quote = blocks.filterIsInstance<ForumBodyBlock.Quote>().firstOrNull()
        assertTrue("expected a Quote, got $blocks", quote != null)
        assertTrue(quote!!.text.text.contains("wuti"))
        val rest = blocks.filterIsInstance<ForumBodyBlock.Paragraph>().joinToString { it.text.text }
        assertTrue("reply text must survive, got: $rest", rest.contains("我的回复"))
    }

    @Test
    fun `smiley token becomes an emoji run`() {
        val blocks = ForumBodyParser.parse("哈哈[#呵呵]真不错")
        val emoji = blocks.filterIsInstance<ForumBodyBlock.EmojiRow>().flatMap { it.urls }
        assertTrue("expected the smiley to be resolved, got $blocks", emoji.any { it.contains("/smiley/") && it.endsWith(".png") || it.endsWith(".gif") })
        val text = blocks.filterIsInstance<ForumBodyBlock.Paragraph>().joinToString { it.text.text }
        assertFalse("the raw token must not leak", text.contains("[#呵呵]"))
    }

    @Test
    fun `bbcode url becomes a link span`() {
        val html = """[url=https://bbs.binmt.cc/x]点我[/url]"""
        val paragraph = ForumBodyParser.parse(html, linkColor = Color(0xFF0000FF))
            .filterIsInstance<ForumBodyBlock.Paragraph>()
            .first()
        val link = paragraph.text.spanStyles.firstOrNull { it.item.color == Color(0xFF0000FF) }
        assertTrue("expected a link span, got ${paragraph.text.spanStyles}", link != null)
    }

    @Test
    fun `bbcode quote with author keeps the author and the quoted text`() {
        val blocks = ForumBodyParser.parse("[quote=wuti]原帖内容[/quote]我的回复")
        val quote = blocks.filterIsInstance<ForumBodyBlock.Quote>().firstOrNull()
        assertTrue("expected a Quote block, got $blocks", quote != null)
        assertTrue("author must survive", quote!!.text.text.contains("wuti"))
        assertTrue("quoted text must survive", quote.text.text.contains("原帖内容"))
    }

    @Test
    fun `bbcode list becomes list rows without markers`() {
        val blocks = ForumBodyParser.parse("[list][*]第一项[*]第二项[/list]")
        val list = blocks.filterIsInstance<ForumBodyBlock.BulletList>().firstOrNull()
        assertTrue("expected a list block, got $blocks", list != null)
        val text = list!!.items.joinToString("|") { it.text.text }
        assertTrue("both items must render, got: $text", text.contains("第一项") && text.contains("第二项"))
        assertFalse("list markers must not leak, got: $text", text.contains("[*]") || text.contains("[list]"))
    }

    @Test
    fun `hidden bbcode content is not revealed`() {
        val blocks = ForumBodyParser.parse("[hide]机密内容[/hide]")
        val text = blocks.joinToString("|") {
            when (it) {
                is ForumBodyBlock.Paragraph -> it.text.text
                is ForumBodyBlock.Quote -> it.text.text
                else -> ""
            }
        }
        assertFalse("gated content must not be exposed, got: $text", text.contains("机密内容"))
        assertTrue("a hidden-content placeholder must be shown, got: $text", text.contains("隐藏"))
    }

    @Test
    fun `attachment and media markers degrade to readable text`() {
        val text = ForumBodyParser.parse("[attach]123[/attach][media=x]https://example.com/a.mp4[/media]")
            .filterIsInstance<ForumBodyBlock.Paragraph>()
            .joinToString("|") { it.text.text }
        assertTrue("attachment placeholder expected, got: $text", text.contains("附件"))
        assertTrue("media URL should stay readable, got: $text", text.contains("https://example.com/a.mp4"))
        assertFalse("raw markers must not leak, got: $text", text.contains("[attach]") || text.contains("[media]"))
    }

    @Test
    fun `unbalanced bbcode markers do not leak`() {
        val text = ForumBodyParser.parse("普通[b]粗体没有闭合，[color=#ff0000]红色[/color]")
            .filterIsInstance<ForumBodyBlock.Paragraph>()
            .joinToString { it.text.text }
        assertFalse("an unclosed [b] must be scrubbed, got: $text", text.contains("[b]"))
        assertFalse("a colour marker must be scrubbed, got: $text", text.contains("[color="))
    }

    @Test
    fun `bbcode size align and table markers are stripped`() {
        val blocks = ForumBodyParser.parse("[align=center][size=4]标题[/size][/align][table][tr][td]单元格[/td][/tr][/table]")
        // `[align]` is a block container now, so the heading it wraps is a *child* of the
        // aligned group rather than a sibling; collecting only top-level blocks would miss it.
        val all = recursiveText(blocks)
        assertTrue("text must survive, got: $all", all.contains("标题") && all.contains("单元格"))
        assertFalse(
            "markers must not leak, got: $all",
            all.contains("[size=") || all.contains("[td]") || all.contains("[align="),
        )
        assertTrue(
            "the align wrapper must become a real block group, got: $blocks",
            blocks.any { it is ForumBodyBlock.Aligned },
        )
    }
    /** Flattens every text run, descending into `[align]` groups and nested quote blocks. */
    private fun recursiveText(blocks: List<ForumBodyBlock>): String = blocks.joinToString("|") { block ->
        when (block) {
            is ForumBodyBlock.Aligned -> recursiveText(block.blocks)
            is ForumBodyBlock.Quote -> block.text.text + "|" + recursiveText(block.children)
            is ForumBodyBlock.Paragraph -> block.text.text
            is ForumBodyBlock.Heading -> block.text.text
            is ForumBodyBlock.Table -> block.rows.flatten().joinToString("|") { it.text.text }
            is ForumBodyBlock.BulletList -> block.items.joinToString("|") { it.text.text }
            is ForumBodyBlock.CodeBlock -> block.text
            is ForumBodyBlock.Attachment -> block.name
            else -> ""
        }
    }
    /**
     * Inline `<a href>` must be **tappable**, not merely coloured.
     *
     * Rendering a link as a SpanStyle only produced blue underlined text; nothing happened on tap,
     * which made every reference link in the fixture threads dead. The parser now attaches a
     * [androidx.compose.ui.text.LinkAnnotation.Url] (optionally with the host's own handler), so
     * this pins both halves: the annotation exists, and its destination is the normalised URL.
     */
    @Test
    fun `inline links carry a link annotation for tapping`() {
        val html = """<div>见 <a href="https://bbs.binmt.cc/thread-161119-1-1.html">论坛教程</a> 一节</div>"""
        val paragraph = ForumBodyParser.parse(html, linkColor = Color(0xFF0000FF))
            .filterIsInstance<ForumBodyBlock.Paragraph>()
            .first()
        val links = paragraph.text.getLinkAnnotations(0, paragraph.text.length)
        assertEquals("expected exactly one link annotation, got $links", 1, links.size)
        val destination = (links.first().item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url
        assertEquals("https://bbs.binmt.cc/thread-161119-1-1.html", destination)
        // The styled run must still be coloured+underlined, so the visual cue does not regress.
        assertTrue(
            "the link run must stay coloured and underlined, got ${paragraph.text.spanStyles}",
            paragraph.text.spanStyles.any {
                it.item.color == Color(0xFF0000FF) &&
                    it.item.textDecoration == TextDecoration.Underline
            },
        )
    }

    @Test
    fun `non-navigable hrefs are not turned into links`() {
        // The template uses `javascript:;` for image buttons; those must not become tappable URLs.
        val paragraph = ForumBodyParser.parse("""<div><a href="javascript:;">按钮</a></div>""")
            .filterIsInstance<ForumBodyBlock.Paragraph>()
            .first()
        assertTrue(
            "a javascript: href must not produce a link annotation",
            paragraph.text.getLinkAnnotations(0, paragraph.text.length).isEmpty(),
        )
    }

    /**
     * The real research thread's body is full of `<a href>` references; this pins that they all
     * survive as links (the fixture is the proof, not a hand-written sample).
     */
    @Test
    fun `real thread links are all tappable`() {
        val posts = io.rune.mt.forum.data.forum.network.ForumHtmlParser
            .parseThreadDetail(
                java.io.File("src/test/resources/forum/rich_166223.html")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?: java.io.File("app/src/test/resources/forum/rich_166223.html").readText(),
                tid = 166223,
                page = 1,
            )
            .posts
        val links = posts.flatMap { post ->
            ForumBodyParser.parse(post.contentHtml)
                .filterIsInstance<ForumBodyBlock.Paragraph>()
                .flatMap { it.text.getLinkAnnotations(0, it.text.length) }
        }
        assertTrue("the fixture should carry inline links, got ${links.size}", links.isNotEmpty())
        assertTrue(
            "every link destination must be absolute http(s)",
            links.all { it.item is androidx.compose.ui.text.LinkAnnotation.Url },
        )
    }

    // ---- Offsets through normalize(): span/link ranges must follow the rewrite --------------

    /**
     * "有些 bbcode 蓝色解析错位置了": the link colour/underline landed on neighbouring characters.
     *
     * `normalize()` rebuilds the string after collapsing template whitespace. It used to shift
     * every range by the *leading trim only*, so an interior collapse (two spaces → one, or the
     * indentation a line break swallows) moved all later offsets and the span painted the wrong
     * text. This case isolates it: the two spaces between the links are real (the template leaves
     * `<span></span>` between the whitespace runs), so the second link starts one character
     * earlier in the collapsed string than it did in the raw one.
     */
    @Test
    fun `interior space collapse keeps each link on its own word`() {
        val html = """<div><a href="https://bbs.binmt.cc/a">甲</a> <span></span> <a href="https://bbs.binmt.cc/b">乙</a> 后文</div>"""
        val paragraph = parse(html).filterIsInstance<ForumBodyBlock.Paragraph>().first()
        assertEquals("甲 乙 后文", paragraph.text.text)
        val links = paragraph.text.getLinkAnnotations(0, paragraph.text.length)
        assertEquals("both links must survive normalize, got $links", 2, links.size)
        assertEquals(
            "甲",
            paragraph.text.text.substring(links[0].start, links[0].end),
        )
        assertEquals(
            "the second link must stay on 乙, not drift onto the space, got " +
                "'${paragraph.text.text.substring(links[1].start, links[1].end)}'",
            "乙",
            paragraph.text.text.substring(links[1].start, links[1].end),
        )
        // The colour/underline SpanStyle is what the user actually sees; it must be remapped too.
        val painted = paragraph.text.spanStyles
            .filter { it.item.color == Color(0xFF0000FF) }
            .map { paragraph.text.text.substring(it.start, it.end) }
        assertEquals(setOf("甲", "乙"), painted.toSet())
    }

    /**
     * The realistic shape: the template pretty-prints, so after a `<br />` the source indentation
     * arrives as a text node that jsoup normalises to a single space — and `normalize()` swallows
     * that space next to the line break, shifting every later offset by one. This is the markup
     * pattern of the captured threads (`rich_166223.html`, `album_images_177269.html`).
     */
    @Test
    fun `a link after a collapsed indentation run stays on its own text`() {
        val html = "<div>见 <a href=\"https://bbs.binmt.cc/a\">第一</a><br />\n             " +
            "<a href=\"https://bbs.binmt.cc/b\">第二</a> 说明</div>"
        val paragraph = parse(html).filterIsInstance<ForumBodyBlock.Paragraph>().first()
        assertEquals("见 第一\n第二 说明", paragraph.text.text)
        val links = paragraph.text.getLinkAnnotations(0, paragraph.text.length)
        assertEquals(2, links.size)
        assertEquals("第一", paragraph.text.text.substring(links[0].start, links[0].end))
        assertEquals(
            "the link must stay on 第二, got '${paragraph.text.text.substring(links[1].start, links[1].end)}'",
            "第二",
            paragraph.text.text.substring(links[1].start, links[1].end),
        )
    }

    /** Whole-string case: nothing to collapse, the range must stay exactly the anchor run. */
    @Test
    fun `a link covering the whole paragraph keeps its range`() {
        val paragraph = parse("""<div><a href="https://bbs.binmt.cc/whole">整段链接</a></div>""")
            .filterIsInstance<ForumBodyBlock.Paragraph>()
            .first()
        assertEquals("整段链接", paragraph.text.text)
        val link = paragraph.text.getLinkAnnotations(0, paragraph.text.length).single()
        assertEquals(0, link.start)
        assertEquals(paragraph.text.length, link.end)
        assertEquals("整段链接", paragraph.text.text.substring(link.start, link.end))
    }

    /** Leading-trim case: the previous fix's scenario must keep working. */
    @Test
    fun `a link after the leading trim keeps its range`() {
        val html = "<div>\n            <a href=\"https://bbs.binmt.cc/lead\">前导</a>后文\n        </div>"
        val paragraph = parse(html).filterIsInstance<ForumBodyBlock.Paragraph>().first()
        assertEquals("前导后文", paragraph.text.text)
        val link = paragraph.text.getLinkAnnotations(0, paragraph.text.length).single()
        assertEquals("前导", paragraph.text.text.substring(link.start, link.end))
    }

    /**
     * The real fixtures prove the remap: a link's destination identifies the anchor, so the run it
     * covers must be exactly that anchor's text. A drift of even one character breaks the equality.
     */
    @Test
    fun `real fixture link runs cover the anchor text of their url`() {
        val fixtures = listOf("album_images_177269.html" to 177269, "rich_166223.html" to 166223)
        var checked = 0
        fixtures.forEach { (name, tid) ->
            val path = "src/test/resources/forum/$name"
            val file = java.io.File(path).takeIf { it.exists() } ?: java.io.File("app/$path")
            val posts = io.rune.mt.forum.data.forum.network.ForumHtmlParser
                .parseThreadDetail(file.readText(), tid = tid, page = 1)
                .posts
            posts.forEach { post ->
                val anchors: Map<String, List<String>> = org.jsoup.Jsoup.parseBodyFragment(post.contentHtml)
                    .select("a[href]")
                    .mapNotNull { anchor ->
                        val destination = io.rune.mt.forum.data.forum.network.ForumUrls
                            .absolute(anchor.attr("href")) ?: return@mapNotNull null
                        val text = anchor.text().trim()
                        if (text.isEmpty()) null else destination to text
                    }
                    .groupBy({ it.first }, { it.second })
                paragraphsOf(ForumBodyParser.parse(post.contentHtml)).forEach { paragraph ->
                    val annotations = paragraph.text.getLinkAnnotations(0, paragraph.text.length)
                    annotations.forEach link@{ link ->
                        val url = (link.item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url
                            ?: return@link
                        val covered = paragraph.text.text.substring(link.start, link.end).trim()
                        val expected = anchors[url]
                        assertTrue("$name: no anchor text captured for $url", expected != null)
                        assertTrue(
                            "$name: link $url covers '$covered' but its anchor text is $expected",
                            expected!!.any { it == covered },
                        )
                        checked++
                    }
                }
            }
        }
        assertTrue("expected real links to check, got $checked", checked > 0)
    }

    /** Paragraphs at any depth: quotes and `[align]` groups hold their own text runs. */
    private fun paragraphsOf(blocks: List<ForumBodyBlock>): List<ForumBodyBlock.Paragraph> =
        blocks.flatMap { block ->
            when (block) {
                is ForumBodyBlock.Paragraph -> listOf(block)
                is ForumBodyBlock.Quote -> paragraphsOf(block.children)
                is ForumBodyBlock.Aligned -> paragraphsOf(block.blocks)
                else -> emptyList()
            }
        }
}

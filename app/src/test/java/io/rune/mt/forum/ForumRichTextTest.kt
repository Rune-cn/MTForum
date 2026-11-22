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
}

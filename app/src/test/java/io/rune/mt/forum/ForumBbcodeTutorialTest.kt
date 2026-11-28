// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.compose.ui.text.LinkAnnotation
import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import java.io.File
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests driven by the forum's **own BBCode tutorial** (thread 161119, captured as
 * `bbcode_tutorial_161119.html`).
 *
 * The tutorial documents the exact HTML the Comiis template renders each tag into, so every test
 * below pulls the real rendered fragment out of that fixture and asserts the native renderer maps
 * it faithfully — no invented markup. Cases the tutorial does not render directly (nested lists,
 * `[size]` wrapping a block) are built as synthetic fragments that quote the tutorial's own example
 * markup.
 */
class ForumBbcodeTutorialTest {

    private fun fixture(): String {
        val path = "src/test/resources/forum/bbcode_tutorial_161119.html"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    private fun opHtml(): String {
        val document = Jsoup.parse(fixture())
        // The OP's message body is the first comiis_a.comiis_message_table; later posts use the
        // same class, so taking the first keeps the tutorial's own examples.
        return document.selectFirst("div.comiis_postli div.comiis_a.comiis_message_table")!!.outerHtml()
            .let { "$it" }
            .also { body ->
                // Sanity: the first body must be the tutorial (it carries the tag catalogue).
                require(body.contains("[b]加粗文本")) { "first post is not the BBCode tutorial" }
            }
    }

    private fun element(css: String): Element {
        val document = Jsoup.parse(fixture())
        return document.selectFirst(css) ?: error("fixture has no element matching $css")
    }

    private fun parseElement(css: String): List<ForumBodyBlock> =
        ForumBodyParser.parse(element(css).outerHtml())

    // ---- fix group 1: ordered lists, email, backcolor, size-on-block ----------------------

    /**
     * `[list=1]` renders `<ul type="1" class="litype_1">`, not `<ol>`. The old walker treated every
     * `<ul>` as bulleted and silently turned the tutorial's 「第一步 / 第二步」 into dots.
     */
    @Test
    fun `numbered list is a ul type 1 and keeps numeric markers`() {
        val list = parseElement("ul.litype_1").filterIsInstance<ForumBodyBlock.BulletList>().single()
        assertEquals(
            "the three tutorial steps must keep numeric markers, got ${list.items.map { it.marker to it.text.text }}",
            listOf("1.", "2.", "3."),
            list.items.map { it.marker },
        )
        assertTrue("第一步 must survive", list.items.any { it.text.text.contains("第一步") })
    }

    /** `[list=a]` renders `<ul type="a" class="litype_2">`; its markers are letters, not dots. */
    @Test
    fun `alpha list is a ul type a and keeps letter markers`() {
        val list = parseElement("ul.litype_2").filterIsInstance<ForumBodyBlock.BulletList>().single()
        assertEquals(
            "the three tutorial options must keep letter markers, got ${list.items.map { it.marker }}",
            listOf("a.", "b.", "c."),
            list.items.map { it.marker },
        )
        assertTrue("选项A must survive", list.items.any { it.text.text.contains("选项A") })
    }

    /** A nested ordered list inside an unordered one keeps its own numbering (synthetic). */
    @Test
    fun `nested ordered list inside an unordered one keeps numbering`() {
        // Discuz renders a nested [list=1] inside a [list] exactly this way (ul inside li).
        val html = """
            <ul><li>外层
              <ul type="1" class="litype_1"><li>内一</li><li>内二</li></ul>
            </li></ul>
        """.trimIndent()
        val list = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.BulletList>().single()
        val outer = list.items.first { it.depth == 0 }
        assertEquals("outer bullet stays a dot, got ${outer.marker}", "•", outer.marker)
        val inner = list.items.filter { it.depth == 1 }
        assertEquals(
            "nested ordered rows number themselves, got ${inner.map { it.marker }}",
            listOf("1.", "2."),
            inner.map { it.marker },
        )
    }

    /** `[email]` renders `<a href="mailto:…">`; it must stay a tappable link, not plain colour. */
    @Test
    fun `email tag renders a tappable mailto link`() {
        val paragraph = parseElement("a[href^=mailto:]")
            .filterIsInstance<ForumBodyBlock.Paragraph>()
            .first { it.text.text.contains("电子邮箱专用") }
        val links = paragraph.text.getLinkAnnotations(0, paragraph.text.length)
        assertTrue("the email link must carry a LinkAnnotation, got $links", links.isNotEmpty())
        assertTrue(
            "the link must be a mailto: URL, got $links",
            links.any { (it.item as? LinkAnnotation.Url)?.url?.startsWith("mailto:") == true },
        )
    }

    /** `[backcolor=green]` renders an inline `background-color`; it used to be dropped entirely. */
    @Test
    fun `backcolor renders a background span`() {
        val paragraph = parseElement("font[style*=background-color]")
            .filterIsInstance<ForumBodyBlock.Paragraph>()
            .first { it.text.text.contains("字体背景色") }
        val backgrounds = paragraph.text.spanStyles.mapNotNull { it.item.background.takeIf { c -> c != androidx.compose.ui.graphics.Color.Unspecified } }
        assertTrue("expected a background colour span, got ${paragraph.text.spanStyles}", backgrounds.isNotEmpty())
    }

    /** `[size]` wrapping a whole block must keep the block, not flatten it into a heading. */
    @Test
    fun `size on a block element keeps the inner list structure`() {
        val html = """<font size="4"><ul><li>一</li><li>二</li></ul></font>"""
        val blocks = ForumBodyParser.parse(html)
        assertTrue(
            "a list wrapped in [size] must stay a BulletList, got $blocks",
            blocks.any { it is ForumBodyBlock.BulletList },
        )
        assertFalse(
            "the wrapped list must not be promoted to a Heading, got $blocks",
            blocks.any { it is ForumBodyBlock.Heading },
        )
    }

    // ---- fix group 2: media / audio / flash / qq / attach / free -------------------------

    /** `[media]` becomes an `<iframe>` injected by a script; the card must carry the URL. */
    @Test
    fun `media tag becomes an honest card carrying the bilibili url`() {
        val bilibiliOp = Jsoup.parse(fixture()).select("ignore_js_op").first {
            it.selectFirst("script")?.data()?.contains("player.bilibili.com") == true
        } ?: error("fixture has no bilibili media shell")
        val media = ForumBodyParser.parse(bilibiliOp.outerHtml())
            .filterIsInstance<ForumBodyBlock.Media>().single()
        assertTrue(
            "the bilibili player must be rewritten to its watch page, got ${media.url}",
            media.url == "https://www.bilibili.com/video/1NL411M7Jr",
        )
        assertEquals("a video embed is labelled as video", "视频", media.label)
    }

    /** `[audio]` becomes a `div.media` with a 「如果无法播放」 fallback link; keep that URL. */
    @Test
    fun `audio tag becomes a media card carrying the audio url`() {
        val media = parseElement("div.media").filterIsInstance<ForumBodyBlock.Media>().single()
        assertTrue("the audio url must survive, got ${media.url}", media.url.contains("163_music"))
        assertEquals("an audio embed is labelled as audio", "音频", media.label)
    }

    /** The draft path (`[media]…[/media]`) must reach the same card, not leak raw text. */
    @Test
    fun `media bbcode draft reaches the media card`() {
        val media = ForumBodyParser.parse("[media=x,500,375]https://www.bilibili.com/video/BV1NL411M7Jr[/media]")
            .filterIsInstance<ForumBodyBlock.Media>()
            .single()
        assertTrue("draft media url must survive, got ${media.url}", media.url.contains("bilibili.com"))
        assertFalse(
            "the raw [media] marker must not leak into the body",
            ForumBodyParser.parse("[media=x,500]https://e.com/a.mp4[/media]")
                .any { it is ForumBodyBlock.Paragraph && it.text.text.contains("[media") },
        )
    }

    /** `[qq]` renders a bare image link; it must be a chat link, not a stray QQ icon picture. */
    @Test
    fun `qq tag renders a chat link instead of a stray icon`() {
        val blocks = parseElement("a[href*=wpa.qq.com]")
        assertFalse("the QQ icon must not become a Picture block, got $blocks", blocks.any { it is ForumBodyBlock.Picture })
        val paragraph = blocks.filterIsInstance<ForumBodyBlock.Paragraph>().first()
        assertTrue("the QQ account must be shown, got ${paragraph.text.text}", paragraph.text.text.contains("123456789"))
        val links = paragraph.text.getLinkAnnotations(0, paragraph.text.length)
        assertTrue(
            "the QQ line must link to the chat URL, got $links",
            links.any { (it.item as? LinkAnnotation.Url)?.url?.contains("wpa.qq.com") == true },
        )
    }

    /** The tutorial's `[attach]` renders one block-level card, never inline prose. */
    @Test
    fun `attach renders one block card in the tutorial body`() {
        val attachments = ForumBodyParser.parse(opHtml()).filterIsInstance<ForumBodyBlock.Attachment>()
        assertEquals("the tutorial carries one attachment card", 1, attachments.size)
        assertTrue("the attachment keeps its download url", attachments.first().url.contains("mod=attachment"))
    }

    /** `[free]` renders as a quote block (the template's 「免费内容」 wrapper). */
    @Test
    fun `free content renders as a quote`() {
        val quote = ForumBodyParser.parse(element("blockquote:contains(当设置了付费主题)").outerHtml())
            .filterIsInstance<ForumBodyBlock.Quote>()
            .singleOrNull()
        assertNotNull("the free block must be a quote", quote)
        assertTrue("the free text must survive", quote!!.text.text.contains("免费资源"))
    }
}

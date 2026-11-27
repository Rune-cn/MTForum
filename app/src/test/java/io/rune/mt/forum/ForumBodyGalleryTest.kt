// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import java.io.File
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the **album gallery** shape of a post body: `ul.comiis_img_list` is a list of pictures,
 * not a bullet list.
 *
 * The real page (`album_images_177269.html`, thread 177269) puts no image into
 * `span.comiis_postimg` at all — the OP's four screenshots live in the album list at the end of
 * the message. Because `ul` is a block tag and `collectListItems` only harvests text, those
 * pictures were silently dropped and the post rendered as prose with no images. An image-only
 * list must emit one [ForumBodyBlock.Picture] per image, in document order, and never a
 * [ForumBodyBlock.BulletList].
 *
 * The last test is the regression guard for the whole class of bug: it counts every real content
 * image in the captured bodies and requires exactly that many rendered pictures.
 */
class ForumBodyGalleryTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    private fun posts(name: String, tid: Int) =
        ForumHtmlParser.parseThreadDetail(fixture(name), tid = tid, page = 1).posts

    private fun opBody(): String = posts("album_images_177269.html", tid = 177269).first().contentHtml

    @Test
    fun `album gallery becomes one picture per image in document order`() {
        val body = opBody()
        // The DOM is the source of truth for what the album holds; never invent the count.
        val albumImages = Jsoup.parseBodyFragment(body).select("ul.comiis_img_list li img")
        assertEquals("the fixture must carry the album list, got ${albumImages.size}", 4, albumImages.size)
        val expected = albumImages.map { it.attr("comiis_loadimages") }
        assertTrue(
            "album images must be the real oos files, got $expected",
            expected.all { it.contains("oos.binmt.cc/forum/") },
        )

        val blocks = ForumBodyParser.parse(body)
        val pictures = blocks.filterIsInstance<ForumBodyBlock.Picture>().map { it.url }
        assertEquals("one Picture per album image, in document order", expected, pictures)
        assertTrue(
            "the lazy-load placeholder must never win over comiis_loadimages, got $pictures",
            pictures.none { it.contains("none.png") },
        )
        assertFalse(
            "an image-only list is a gallery, not a bullet list, got " +
                "${blocks.filterIsInstance<ForumBodyBlock.BulletList>()}",
            blocks.any { it is ForumBodyBlock.BulletList },
        )

        // The prose that precedes the gallery must still be there, and in one piece.
        val text = blocks.filterIsInstance<ForumBodyBlock.Paragraph>().joinToString("\n") { it.text.text }
        assertTrue("the OP text must survive, got: $text", text.contains("Hypic支持多国语言"))
        assertTrue(
            "the download link must survive, got: $text",
            text.contains("pan.baidu.com/s/1COOFi0ZFFTE22Ytj51AI_g"),
        )
    }

    /** Defensive half of the rule: a class-less list of pictures is a gallery too. */
    @Test
    fun `an image-only list without the gallery class is still a gallery`() {
        val html = """
            <div><ul>
              <li><span onclick="go()"><img src="https://icdn.binmt.cc/2509/a.jpg" /></span></li>
              <li><img src="https://icdn.binmt.cc/2509/b.jpg" /></li>
            </ul></div>
        """.trimIndent()
        val blocks = ForumBodyParser.parse(html)
        assertEquals(
            "both images must render as pictures",
            listOf("https://icdn.binmt.cc/2509/a.jpg", "https://icdn.binmt.cc/2509/b.jpg"),
            blocks.filterIsInstance<ForumBodyBlock.Picture>().map { it.url },
        )
        assertTrue("no bullet list may be invented", blocks.none { it is ForumBodyBlock.BulletList })
    }

    /** The ordinary case must not regress: a text list is still a marker + row per item. */
    @Test
    fun `an ordinary bullet list is still a bullet list`() {
        val blocks = ForumBodyParser.parse("<ul><li>条目一</li><li>条目二</li></ul>")
        val list = blocks.filterIsInstance<ForumBodyBlock.BulletList>().single()
        assertEquals(listOf("条目一", "条目二"), list.items.map { it.text.text })
        assertTrue(
            "a text list must not produce pictures, got ${blocks.filterIsInstance<ForumBodyBlock.Picture>()}",
            blocks.none { it is ForumBodyBlock.Picture },
        )
    }

    /**
     * A list mixing text and images keeps its text rows and appends the pictures *after* the rows
     * (documented behaviour: a [ForumBodyBlock.BulletList] row is text-only and cannot carry a
     * block). The image-only row must not leave an empty bullet behind.
     */
    @Test
    fun `a mixed list keeps its text rows and appends the pictures`() {
        val html = """
            <ul>
              <li>条目一</li>
              <li><img src="https://icdn.binmt.cc/2509/mixed.jpg" /></li>
              <li>条目二</li>
            </ul>
        """.trimIndent()
        val blocks = ForumBodyParser.parse(html)
        val list = blocks.filterIsInstance<ForumBodyBlock.BulletList>().single()
        assertEquals(
            "the image-only row must not become an empty bullet, got ${list.items.map { it.text.text }}",
            listOf("条目一", "条目二"),
            list.items.map { it.text.text },
        )
        val pictures = blocks.filterIsInstance<ForumBodyBlock.Picture>().map { it.url }
        assertEquals(
            "the image must still render, got $pictures",
            listOf("https://icdn.binmt.cc/2509/mixed.jpg"),
            pictures,
        )
        assertTrue(
            "the pictures must follow the rows",
            blocks.indexOfFirst { it is ForumBodyBlock.Picture } >
                blocks.indexOfFirst { it is ForumBodyBlock.BulletList },
        )
    }

    /** A nested image-only list must not be dropped either (it is a gallery inside a bullet). */
    @Test
    fun `an image-only list nested in a bullet list still renders its picture`() {
        val html = """
            <ul>
              <li>文字
                <ul><li><img src="https://icdn.binmt.cc/2509/nested.jpg" /></li></ul>
              </li>
            </ul>
        """.trimIndent()
        val blocks = ForumBodyParser.parse(html)
        val list = blocks.filterIsInstance<ForumBodyBlock.BulletList>().single()
        assertEquals(
            "only the text row belongs to the list, got ${list.items.map { it.text.text }}",
            listOf("文字"),
            list.items.map { it.text.text },
        )
        assertEquals(
            "the nested picture must survive",
            listOf("https://icdn.binmt.cc/2509/nested.jpg"),
            blocks.filterIsInstance<ForumBodyBlock.Picture>().map { it.url },
        )
    }

    /**
     * Regression guard for the whole class "an image container nobody taught the walker about".
     *
     * Every captured body is scanned for `<img>` elements that are *content* — not smilies, not
     * attachment file-type badges, not attachment previews — and the renderer must produce exactly
     * that many pictures (counting pictures nested in quotes and `[align]` groups).
     */
    @Test
    fun `every content image in the captured bodies renders as a picture`() {
        val fixtures = listOf(
            "album_images_177269.html" to 177269,
            "rich_166223.html" to 166223,
            "sample_attach_thread.html" to 166223,
        )
        var checked = 0
        fixtures.forEach { (name, tid) ->
            val posts = posts(name, tid)
            assertTrue("$name should contain posts", posts.isNotEmpty())
            posts.forEach { post ->
                val imageTags = Jsoup.parseBodyFragment(post.contentHtml).select("img")
                val contentImages = imageTags.count { it.isContentImage() }
                val rendered = countPictures(ForumBodyParser.parse(post.contentHtml))
                assertEquals(
                    "$name pid=${post.pid}: rendered pictures must match the body's content images " +
                        "(images: ${imageTags.map { it.describeImage() }})",
                    contentImages,
                    rendered,
                )
                checked += contentImages
            }
        }
        assertTrue("the audit must actually see content images, got $checked", checked > 0)
        println("GALLERY AUDIT: $checked content images across ${fixtures.size} captured threads")
    }

    /** Counts pictures at any depth, because a quote keeps them as ordered children. */
    private fun countPictures(blocks: List<ForumBodyBlock>): Int = blocks.sumOf { block ->
        when (block) {
            is ForumBodyBlock.Picture -> 1
            is ForumBodyBlock.Quote -> countPictures(block.children)
            is ForumBodyBlock.Aligned -> countPictures(block.blocks)
            else -> 0
        }
    }

    /** The real source of an `<img>`, following the same lazy-load attribute order as the app. */
    private fun Element.imageSource(): String {
        val lazy = attr("comiis_loadimages")
            .ifBlank { attr("file") }
            .ifBlank { attr("zoomfile") }
            .ifBlank { attr("data-src") }
        val raw = attr("src")
        return when {
            raw.isBlank() -> lazy
            raw.startsWith("file:") || raw.startsWith("data:") || raw.startsWith("javascript:") -> lazy
            raw.contains("none.png") || raw.contains("imageloading.gif") -> lazy
            else -> raw
        }
    }

    /** A body picture: not a smiley, not a file-type badge, not an attachment preview. */
    private fun Element.isContentImage(): Boolean {
        if (hasAttr("smilieid")) return false
        val source = imageSource()
        if (source.isBlank()) return false
        if (source.contains("static/image/smiley")) return false
        if (source.contains("static/image/filetype")) return false
        if (parents().any { it.hasClass("comiis_attach") }) return false
        return true
    }

    private fun Element.describeImage(): String =
        "${attr("src")} | lazy=${attr("comiis_loadimages")} | class=${className()}"
}
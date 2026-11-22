// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks for the post-body renderer against a real captured thread.
 *
 * The renderer exists so a post body never needs a WebView; these assertions pin the three
 * element kinds that decision has to keep: text, smilies and attachment pictures.
 */
class ForumHtmlTextTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    private fun firstPostHtml(): String =
        ForumHtmlParser.parseThreadDetail(fixture("thread_177093.html"), tid = 177093, page = 1)
            .posts
            .first()
            .contentHtml

    @Test
    fun `body text survives flattening`() {
        val blocks = ForumBodyParser.parse(firstPostHtml())
        val text = blocks.filterIsInstance<ForumBodyBlock.Paragraph>().joinToString("\n") { it.text }
        assertTrue("expected the OP sentence, got: $text", text.contains("DeepSeek"))
        assertFalse("template whitespace should be collapsed", text.contains("\n\n\n"))
    }

    @Test
    fun `smilies become an emoji row with absolute urls`() {
        val blocks = ForumBodyParser.parse(firstPostHtml())
        val emoji = blocks.filterIsInstance<ForumBodyBlock.EmojiRow>().flatMap { it.urls }
        assertTrue("expected smilies, got $blocks", emoji.isNotEmpty())
        assertTrue("smiley urls must be absolute: $emoji", emoji.all { it.startsWith("https://") })
    }

    @Test
    fun `plain text preview drops markup and images`() {
        val preview = ForumBodyParser.plainText(firstPostHtml())
        assertTrue(preview.contains("DeepSeek"))
        assertFalse(preview.contains("<img"))
        assertFalse(preview.contains("&nbsp;"))
    }

    @Test
    fun `protocol relative and root relative sources are normalised`() {
        val blocks = ForumBodyParser.parse(
            """<div>看图<img src="//cdn.binmt.cc/a.png">然后<img src="/data/b.png"></div>""",
        )
        val urls = blocks.filterIsInstance<ForumBodyBlock.Picture>().map { it.url }
        assertEquals(listOf("https://cdn.binmt.cc/a.png", "https://bbs.binmt.cc/data/b.png"), urls)
    }
}
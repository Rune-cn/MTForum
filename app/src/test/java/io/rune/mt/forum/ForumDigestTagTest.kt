// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 精华 (digest) marker on list rows.
 *
 * The Comiis list inlines two badges inside the title anchor of a digest row:
 * `<span class="bg_c f_f">精</span>` and the 热度 `<span class="bg_a f_f">` with an icon-font
 * glyph. Both must be read as metadata (digest flag) and stripped from the title, otherwise the
 * card shows a stray `精` prefix and the model claims no digest threads exist.
 *
 * The fixture is trimmed from live pages (`forum.php?mod=guide&view=digest` + a non-digest row
 * from `view=hot`), so the DOM shape is the site's, not a hand-written guess.
 */
class ForumDigestTagTest {

    private val html: String = java.io.File("src/test/resources/forum/board_digest.html")
        .takeIf { it.exists() }
        ?.readText()
        ?: java.io.File("app/src/test/resources/forum/board_digest.html").readText()

    @Test
    fun `digest row carries the flag and a clean title`() {
        val threads = ForumHtmlParser.parseThreadPage(html, fid = 0, page = 1).threads
        assertEquals("fixture must parse two rows", 2, threads.size)

        val digest = threads[0]
        assertTrue("span.bg_c 精 must set digest=true", digest.digest)
        assertEquals("【原创分享】AI聚合网关", digest.title)
        assertFalse("the badge text must not prefix the title", digest.title.startsWith("精"))
        assertFalse(
            "icon-font glyphs must be stripped",
            digest.title.any { it.code in 0xE000..0xF8FF },
        )
    }

    @Test
    fun `plain row stays non-digest`() {
        val threads = ForumHtmlParser.parseThreadPage(html, fid = 0, page = 1).threads
        val plain = threads[1]
        assertFalse(plain.digest)
        assertEquals("劲爆，最新出炉好软件", plain.title)
    }
}
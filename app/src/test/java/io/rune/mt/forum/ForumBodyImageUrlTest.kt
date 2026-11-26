// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Post-body image resolution.
 *
 * The Comiis template hides the real upload behind a lazy attribute while `src` holds a transparent
 * stub. Reading `src` first shipped a blank picture block for every image hosted by the forum's own
 * image host, which is exactly what testers reported on device.
 */
class ForumBodyImageUrlTest {

    private fun pictures(html: String): List<String> =
        ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Picture>().map { it.url }

    @Test
    fun `image host upload behind a comiis placeholder resolves to the real url`() {
        val html = """
            <div>
              <img src="https://cdn.binmt.cc/template/comiis_app/pic/none.png"
                   comiis_loadimages="https://oos.binmt.cc/image/000/38/55/50_500_480.jpg?v=28652fe9540d"
                   class="comiis_noloadimage comiis_loadimages">
            </div>
        """.trimIndent()
        assertEquals(
            listOf("https://oos.binmt.cc/image/000/38/55/50_500_480.jpg?v=28652fe9540d"),
            pictures(html),
        )
    }

    @Test
    fun `file attribute wins over an imageloading stub`() {
        val html = """
            <div><img src="https://cdn.binmt.cc/template/comiis_app/comiis/img/imageloading.gif"
                      file="https://cdn.binmt.cc/data/attachment/forum/202511/26/abc.jpg"></div>
        """.trimIndent()
        assertEquals(
            listOf("https://cdn.binmt.cc/data/attachment/forum/202511/26/abc.jpg"),
            pictures(html),
        )
    }

    @Test
    fun `plain absolute src is untouched`() {
        val html = """<div><img src="https://icdn.binmt.cc/2509/68d26c23c386d.gif"></div>"""
        assertEquals(listOf("https://icdn.binmt.cc/2509/68d26c23c386d.gif"), pictures(html))
    }

    @Test
    fun `javascript placeholder without a lazy url produces no picture block`() {
        val html = """<div><img src="javascript:;" class="comiis_noloadimage"></div>"""
        assertTrue(pictures(html).isEmpty())
    }

    @Test
    fun `relative lazy url is normalised to the forum host`() {
        val html = """<div><img src="none.png" comiis_loadimages="data/attachment/forum/202511/26/x.gif"></div>"""
        assertEquals(
            listOf("https://bbs.binmt.cc/data/attachment/forum/202511/26/x.gif"),
            pictures(html),
        )
    }
}
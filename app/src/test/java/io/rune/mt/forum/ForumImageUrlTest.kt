// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import java.io.File
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every image/avatar URL the parsers hand to Coil must be an absolute http(s) URL.
 *
 * Coil resolves a model with no scheme as a key, not a URL, and fails the request with
 * "no scheme"; the site's mobile template mixes absolute CDN URLs with root-relative
 * (`/data/attachment/...`) and document-relative (`static/image/...`) paths, so the parsers
 * have to normalise all of them. Before this test existed `attr("abs:src")` was used against a
 * document parsed without a base URI, which silently yields the empty string for every avatar.
 */
class ForumImageUrlTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        return (File(path).takeIf { it.exists() } ?: File("app/$path")).readText()
    }

    private fun assertAbsolute(label: String, url: String?) {
        assertNotNull("$label is null", url)
        val value = url!!
        assertFalse("$label is blank", value.isBlank())
        assertTrue("$label is not an absolute http(s) URL: [$value]", value.startsWith("https://") || value.startsWith("http://"))
        assertNotNull("$label has no host: [$value]", URI(value).host)
    }

    private fun pictures(detail: ForumThreadDetail): List<Pair<Long, String>> = detail.posts.flatMap { post ->
        ForumBodyParser.parse(post.contentHtml).filterIsInstance<ForumBodyBlock.Picture>().map { post.pid to it.url }
    }

    // ---- Real captured pages ------------------------------------------------

    @Test
    fun `board listing threads carry absolute avatars`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("board_44.html"), fid = 44, page = 1)
        assertTrue("expected threads", page.threads.isNotEmpty())
        println(
            "URL-DIAG board_44 avatars=" + page.threads.map { it.authorAvatarUrl },
        )
        page.threads.forEach { thread ->
            assertAbsolute("board thread ${thread.tid} (${thread.authorName}) avatar", thread.authorAvatarUrl)
        }
    }

    @Test
    fun `board listing thumbnails are absolute`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("board_44.html"), fid = 44, page = 1)
        val thumbs = page.threads.flatMap { it.imageUrls }
        assertTrue("expected thumbnails, got $thumbs", thumbs.isNotEmpty())
        thumbs.forEachIndexed { index, url -> assertAbsolute("board thumbnail[$index]", url) }
    }

    @Test
    fun `guide page threads carry absolute avatars`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("guide_newthread.html"), fid = 0, page = 1)
        assertTrue("expected threads", page.threads.size >= 10)
        page.threads.forEach { thread ->
            assertAbsolute("guide thread ${thread.tid} (${thread.authorName}) avatar", thread.authorAvatarUrl)
        }
    }

    @Test
    fun `thread detail avatars include the default placeholder gif`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("board_44.html"), fid = 44, page = 1)
        assertTrue(
            "the noavatar placeholder must come through as an absolute GIF URL, got " +
                page.threads.map { it.authorAvatarUrl },
            page.threads.any { it.authorAvatarUrl == "https://cdn.binmt.cc/uc_server/images/noavatar_middle.gif" },
        )
    }

    @Test
    fun `thread detail pictures are absolute gifs from the CDN`() {
        // rich_156696 is the captured thread whose body holds real post pictures: a JPEG and the
        // GIF that the bug report named as "some pictures never load".
        val detail = ForumHtmlParser.parseThreadDetail(fixture("rich_156696.html"), tid = 156696, page = 1)
        val pictures = pictures(detail)
        assertTrue("expected pictures in the captured thread, got $pictures", pictures.isNotEmpty())
        pictures.forEach { (pid, url) -> assertAbsolute("post $pid picture", url) }
        assertTrue(
            "the real GIF must survive parsing, got ${pictures.map { it.second }}",
            pictures.any { it.second == "https://icdn.binmt.cc/2509/68d26c23c386d.gif" },
        )
    }

    @Test
    fun `thread detail posts carry absolute avatars`() {
        val detail = ForumHtmlParser.parseThreadDetail(fixture("thread_177093.html"), tid = 177093, page = 1)
        assertTrue("expected posts", detail.posts.isNotEmpty())
        detail.posts.forEach { post ->
            assertAbsolute("post ${post.pid} (${post.authorName}) avatar", post.authorAvatarUrl)
        }
    }

    @Test
    fun `rich thread pictures are absolute`() {
        val detail = ForumHtmlParser.parseThreadDetail(fixture("rich_156696.html"), tid = 156696, page = 1)
        val pictures = pictures(detail)
        assertTrue("expected pictures, got $pictures", pictures.isNotEmpty())
        pictures.forEach { (pid, url) -> assertAbsolute("rich post $pid picture", url) }
    }

    @Test
    fun `board index icons are absolute when present`() {
        val forums = ForumHtmlParser.parseForums(fixture("index.html"))
        assertTrue("expected boards", forums.isNotEmpty())
        forums.mapNotNull { it.iconUrl }.forEach { assertAbsolute("board icon", it) }
    }

    @Test
    fun `private message avatars are absolute`() {
        val conversations: List<ForumPrivateConversation> =
            ForumHtmlParser.parseConversations(fixture("pm_list.html"))
        assertTrue("expected conversations", conversations.isNotEmpty())
        conversations.forEach { assertAbsolute("pm conversation ${it.uid} avatar", it.avatarUrl) }
        val detail = ForumHtmlParser.parseConversation(fixture("pm_conversation.html"), uid = 1)
        detail.messages.forEachIndexed { index, message ->
            assertAbsolute("pm message[$index] avatar", message.avatarUrl)
        }
    }

    // ---- Relative URLs (synthetic, mirrors what the template can emit) ------

    private val relativeListing = """
        <div class="comiis_threadlist"><ul>
          <li class="forumlist_li">
            <div class="mmlist_li_box"><h2><a href="thread-123-1-1.html">相对头像</a>
              <a class="top_user" href="home.php?mod=space&uid=9">relative</a></h2></div>
            <img class="top_tximg" src="uc_server/data/avatar/000/00/00/09_avatar_middle.gif">
            <div class="comiis_pyqlist_imgs">
              <img src="template/comiis_app/pic/none.png" comiis_loadimages="data/attachment/forum/202509/01/x.gif">
              <img src="template/comiis_app/pic/none.png" comiis_loadimages="//oos.binmt.cc/image/000/38/49/1_500_480.jpg?v=abc">
            </div>
          </li>
        </ul></div>
    """.trimIndent()

    @Test
    fun `document relative avatar in a listing is normalised`() {
        val page = ForumHtmlParser.parseThreadPage(relativeListing, fid = 44, page = 1)
        assertEquals("expected one thread", 1, page.threads.size)
        assertAbsolute("relative listing avatar", page.threads.single().authorAvatarUrl)
        assertEquals(
            "https://bbs.binmt.cc/uc_server/data/avatar/000/00/00/09_avatar_middle.gif",
            page.threads.single().authorAvatarUrl,
        )
    }

    @Test
    fun `relative thumbnails in a listing are normalised`() {
        val page = ForumHtmlParser.parseThreadPage(relativeListing, fid = 44, page = 1)
        val thumbs = page.threads.single().imageUrls
        assertEquals("expected two thumbnails, got $thumbs", 2, thumbs.size)
        thumbs.forEachIndexed { index, url -> assertAbsolute("relative thumbnail[$index]", url) }
        assertEquals("https://bbs.binmt.cc/data/attachment/forum/202509/01/x.gif", thumbs[0])
        assertEquals("https://oos.binmt.cc/image/000/38/49/1_500_480.jpg?v=abc", thumbs[1])
    }

    private val relativeThread = """
        <div class="comiis_postli" id="pid9001">
          <h2><a class="top_user" href="home.php?mod=space&uid=9">relative</a></h2>
          <img class="top_tximg" src="/uc_server/data/avatar/000/00/00/09_avatar_middle.gif">
          <div class="comiis_messages"><div>图：<img src="data/attachment/forum/202509/01/p.gif"></div></div>
        </div>
    """.trimIndent()

    @Test
    fun `root relative avatar inside a post is normalised`() {
        val detail = ForumHtmlParser.parseThreadDetail(relativeThread, tid = 9001, page = 1)
        assertEquals("expected one post", 1, detail.posts.size)
        assertEquals(
            "https://bbs.binmt.cc/uc_server/data/avatar/000/00/00/09_avatar_middle.gif",
            detail.posts.single().authorAvatarUrl,
        )
    }

    @Test
    fun `document relative picture inside a post is normalised`() {
        val detail = ForumHtmlParser.parseThreadDetail(relativeThread, tid = 9001, page = 1)
        val pictures = pictures(detail)
        assertEquals("expected one picture, got $pictures", 1, pictures.size)
        assertEquals(
            "https://bbs.binmt.cc/data/attachment/forum/202509/01/p.gif",
            pictures.single().second,
        )
    }

    @Test
    fun `non loadable schemes are not treated as site relative paths`() {
        // `javascript:` / `file:` placeholders must never be welded onto the site host.
        val html = """
            <div class="comiis_postli" id="pid9002">
              <div class="comiis_messages"><div><img src="javascript:;"></div></div>
            </div>
        """.trimIndent()
        val detail = ForumHtmlParser.parseThreadDetail(html, tid = 9002, page = 1)
        val pictures = pictures(detail)
        assertTrue(
            "javascript: must not become a site URL, got $pictures",
            pictures.none { it.second.contains("javascript") },
        )
    }

    @Test
    fun `parsers never hand the none png placeholder to coil`() {
        // The list template puts a shared transparent `none.png` in `src` and the real file in
        // `comiis_loadimages`; a placeholder reaching Coil renders as an empty picture block,
        // which is indistinguishable from "the image did not load".
        val page = ForumHtmlParser.parseThreadPage(fixture("board_44.html"), fid = 44, page = 1)
        val thumbs = page.threads.flatMap { it.imageUrls }
        assertTrue("expected thumbnails", thumbs.isNotEmpty())
        assertTrue("thumbnails must never be the placeholder: $thumbs", thumbs.none { it.contains("pic/none.png") })
        page.threads.forEach { thread ->
            assertFalse(
                "thread ${thread.tid} avatar is the placeholder",
                thread.authorAvatarUrl.orEmpty().contains("pic/none.png"),
            )
        }
        val guide = ForumHtmlParser.parseThreadPage(fixture("guide_newthread.html"), fid = 0, page = 1)
        assertTrue(
            "guide thumbnails must never be the placeholder",
            guide.threads.flatMap { it.imageUrls }.none { it.contains("pic/none.png") },
        )
    }

    @Test
    fun `a row with only the placeholder yields no thumbnail`() {
        val html = """
            <div class="comiis_threadlist"><ul>
              <li class="forumlist_li">
                <div class="mmlist_li_box"><h2><a href="thread-321-1-1.html">占位图</a></h2></div>
                <div class="comiis_pyqlist_imgs">
                  <img src="https://cdn.binmt.cc/template/comiis_app/pic/none.png">
                </div>
              </li>
            </ul></div>
        """.trimIndent()
        val thread = ForumHtmlParser.parseThreadPage(html, fid = 44, page = 1).threads.single()
        assertTrue("placeholder must not become a thumbnail: ${thread.imageUrls}", thread.imageUrls.isEmpty())
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks for the surfaces added after the first milestone: private messages, favourites
 * and the new-thread form.
 *
 * Fixtures are trimmed copies of live pages captured on 2026-11-26 (see the reverse-engineering
 * notes in docs/REVERSE_SPEC.md §12–§16); message bodies in `pm_conversation.html` were replaced
 * with a placeholder so no private text is committed to the repository.
 */
class ForumMessagingParserTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `conversation list parses peer uid name and preview`() {
        val list = ForumHtmlParser.parseConversations(fixture("pm_list.html"))
        assertTrue("expected conversations, got ${list.size}", list.size >= 3)
        val first = list.first()
        assertTrue("uid must be parsed from touid=", first.uid > 0)
        assertTrue("peer name must not be blank", first.name.isNotBlank())
        // The date is rendered inside the h2 before the name; it must not leak into the name.
        first.lastTimeText?.let { date ->
            assertTrue("date must not be part of the name", !first.name.startsWith(date))
        }
        assertTrue("preview expected", list.any { !it.lastPreview.isNullOrBlank() })
        assertEquals("rows must be de-duplicated by uid", list.size, list.distinctBy { it.uid }.size)
    }

    @Test
    fun `conversation parses bubbles sides and reply credentials`() {
        val detail = ForumHtmlParser.parseConversation(fixture("pm_conversation.html"), uid = 144189)
        assertTrue("expected message bubbles, got ${detail.messages.size}", detail.messages.size >= 2)
        assertTrue("self bubbles must be flagged", detail.messages.any { it.isMine })
        assertTrue("peer bubbles must be flagged", detail.messages.any { !it.isMine })
        assertTrue("bodies must be non-blank", detail.messages.all { it.contentHtml.isNotBlank() })
        assertNotNull("formhash is required to reply", detail.formhash)
        assertNotNull("pmid is required by the reply form", detail.pmid)
    }

    @Test
    fun `favourites list parses favid and thread id`() {
        val favorites = ForumHtmlParser.parseFavorites(fixture("favorites.html"))
        assertTrue("expected favourites, got ${favorites.size}", favorites.size >= 2)
        assertTrue("every row needs a favid for deletion", favorites.all { it.favid > 0 })
        assertTrue("every row needs a tid", favorites.all { (it.tid ?: 0) > 0 })
        assertTrue("every row needs a title", favorites.all { it.title.isNotBlank() })
    }

    @Test
    fun `new thread form exposes formhash and no captcha for this board`() {
        val form = ForumHtmlParser.parseNewThreadForm(fixture("newthread_form.html"), fid = 44)
        assertNotNull("formhash is required to post", form.formhash)
        assertNotNull("posttime is part of the signature", form.posttime)
        // Pinned so a future captcha rollout surfaces here instead of as a confusing submit failure.
        assertEquals(false, form.needsCaptcha)
    }

    @Test
    fun `login redirect is detected so writes can report an expired session`() {
        val loginPage = fixture("login_page.html")
        assertTrue(
            "the login page must be recognised as a login bounce",
            ForumHtmlParser.isLoginRequired(loginPage),
        )
        val realPage = fixture("thread_177093.html")
        assertTrue(
            "a normal thread page must not look like a login bounce",
            !ForumHtmlParser.isLoginRequired(realPage),
        )
    }
}

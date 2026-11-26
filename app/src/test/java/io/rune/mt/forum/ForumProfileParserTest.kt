// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks for the space profile and the authenticated favourites page.
 *
 * Fixtures are trimmed live captures (2026-11-26) taken with a signed-in cookie via the recon
 * scripts. `space_profile.html` is another member's page while its sidebar still belongs to the
 * visitor — the same trap that once made `getUserProfile` treat a visitor as the profile owner.
 */
class ForumProfileParserTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `other user profile parses header counters and level`() {
        val profile = ForumHtmlParser.parseUserProfile(fixture("space_profile.html"), uid = 132694)
        assertEquals("厌生客", profile.name)
        assertEquals(132694, profile.uid)
        assertNotNull("avatar is required for the header", profile.avatarUrl)
        assertTrue("avatar must be the member's own", profile.avatarUrl!!.contains("13/26/94"))
        assertEquals("Lv.4", profile.levelText)
        assertEquals("高中生", profile.levelTitle)
        assertEquals(1298, profile.credits)
        assertEquals(161, profile.popularity)
        assertEquals(3, profile.followingCount)
        assertEquals(15, profile.followerCount)
        assertEquals(7, profile.threadCount)
        assertEquals(321, profile.replyCount)
        assertEquals(2, profile.friendCount)
        // The visitor's sidebar says "Rune" — the target member must win.
        assertTrue("the sidebar visitor must not be reported as the owner", profile.name != "Rune")
    }

    @Test
    fun `profile credit block and detail fields follow the site order`() {
        val profile = ForumHtmlParser.parseUserProfile(fixture("space_profile.html"), uid = 132694)
        assertEquals(listOf("积分", "好评", "金币", "信誉"), profile.creditStats.map { it.label })
        assertEquals(listOf("1298", "2", "57", "100"), profile.creditStats.map { it.value })
        val fields = profile.fields.associate { it.label to it.value }
        assertEquals("132694", fields["用户ID"])
        assertEquals("保密", fields["性别"])
        assertEquals("193 小时", fields["在线时间"])
        assertEquals("2023-7-23 00:34", fields["注册时间"])
        assertTrue("last visit must be parsed", fields.containsKey("最后访问"))
        // The placeholder signature block must not be shown as a real bio.
        assertNull(profile.signature)
        assertFalse("the follow button offers 关注, not 取消关注", profile.isFollowing == true)
    }

    @Test
    fun `own profile exposes more fields and no follow button`() {
        val profile = ForumHtmlParser.parseUserProfile(fixture("space_profile_own.html"), uid = 153466)
        assertEquals("Rune", profile.name)
        assertEquals("Lv.6", profile.levelText)
        assertEquals("硕士生", profile.levelTitle)
        assertEquals(5339, profile.credits)
        assertEquals(1076, profile.popularity)
        assertEquals(41, profile.followingCount)
        assertEquals(79, profile.followerCount)
        assertEquals(117, profile.threadCount)
        assertEquals(1418, profile.replyCount)
        assertEquals(10, profile.friendCount)
        val fields = profile.fields.associate { it.label to it.value }
        assertEquals("学生", fields["职业"])
        assertEquals("男", fields["性别"])
        assertEquals("627 小时", fields["在线时间"])
        // One's own profile has no follow control at all, which is not "not following".
        assertNull(profile.isFollowing)
        assertNotNull("the signature is real on this page", profile.signature)
    }

    @Test
    fun `recent thread listing reuses the board row parser`() {
        val page = ForumHtmlParser.parseThreadPage(fixture("space_threads.html"), fid = 0, page = 1)
        assertEquals(2, page.threads.size)
        val first = page.threads.first()
        assertEquals(123832, first.tid)
        assertTrue("title must not be blank", first.title.isNotBlank())
        assertEquals("厌生客", first.authorName)
        assertEquals(132694, first.authorUid)
    }

    @Test
    fun `favourites page exposes formhash and the server pager`() {
        val page = ForumHtmlParser.parseFavoritesPage(fixture("favorites_page.html"), page = 1)
        assertEquals("16385279", page.formhash)
        assertEquals("the site prints 5 pages for this account", 5, page.totalPages)
        assertEquals(2, page.favorites.size)
        assertEquals(712378L, page.favorites[0].favid)
        assertEquals(177295, page.favorites[0].tid)
        assertTrue(page.favorites[0].title.isNotBlank())
    }

    @Test
    fun `formhash is also read from the logout link`() {
        val html = fixture("favorites_page.html")
        assertEquals("16385279", ForumHtmlParser.parseFormhash(html))
        // A page whose only hash is in an href must keep working for the older quoted form too.
        assertEquals(
            "deadbeef",
            ForumHtmlParser.parseFormhash("<script>formhash = 'deadbeef';</script>"),
        )
    }
}
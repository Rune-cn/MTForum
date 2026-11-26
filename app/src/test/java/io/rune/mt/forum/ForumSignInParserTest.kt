// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks for the 每日签到 page (`k_misign-sign.html`) and the plugin's CDATA answer.
 *
 * Both fixtures are live captures (2026-11-27): `signin_misign.html` was taken while the day's
 * sign-in was still available, `signin_misign_after.html` after signing in — the two states the
 * card has to tell apart. The XML wrapper is reproduced byte-for-byte, CRLF included, because the
 * endpoint is not JSON and the payload is the only result.
 */
class ForumSignInParserTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `pre sign-in page parses the member level and the three counters`() {
        val state = ForumHtmlParser.parseSignInState(fixture("signin_misign.html"))

        assertEquals("Rune", state.nickname)
        assertEquals("Lv.8", state.signInLevel)
        assertEquals("rank 0 is the template's placeholder before signing in", 0, state.todayRank)
        assertEquals(2, state.streakDays)
        assertEquals(271, state.totalDays)
        assertFalse("the page still offers the 签到 button", state.alreadySignedToday)
        assertNotNull("the avatar comes from div.user_img img", state.avatarUrl)
        assertTrue(state.avatarUrl!!.startsWith("https://"))
    }

    @Test
    fun `post sign-in page reports the signed state and the refreshed counters`() {
        val state = ForumHtmlParser.parseSignInState(fixture("signin_misign_after.html"))

        assertTrue("the button became 已签到 and lost its id", state.alreadySignedToday)
        assertEquals(1033, state.todayRank)
        assertEquals(3, state.streakDays)
        assertEquals(272, state.totalDays)
        assertEquals("Lv.8", state.signInLevel)
        assertEquals("Rune", state.nickname)
    }

    @Test
    fun `formhash is read through the logout-link fallback`() {
        // The sign-in page prints no `<input name=formhash>`; the only hash is the logout href,
        // which is why parseFormhash needs its `formhash=` fallback.
        assertEquals("16385279", ForumHtmlParser.parseFormhash(fixture("signin_misign.html")))
        assertEquals("16385279", ForumHtmlParser.parseSignInState(fixture("signin_misign.html")).formhash)
        assertEquals(
            "deadbeef",
            ForumHtmlParser.parseFormhash("<script>formhash = 'deadbeef';</script>"),
        )
    }

    @Test
    fun `cdata wrapper is unwrapped with the xml declaration and crlf`() {
        val raw = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<root><![CDATA[已签到]]></root>"
        assertEquals("已签到", ForumHtmlParser.parseCdataMessage(raw))
    }

    @Test
    fun `observed already-signed reply maps to already signed`() {
        val raw = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<root><![CDATA[已签到]]></root>"

        val outcome = ForumHtmlParser.parseSignInOutcome(raw)

        assertTrue("已签到 is the observed reply of a second attempt", outcome is ForumSignInOutcome.AlreadySigned)
        assertEquals("已签到", outcome.message)
    }

    @Test
    fun `fresh success text is kept verbatim`() {
        // The live fresh-success wording could not be captured (the account had signed in
        // already), so the mapping is tolerant on purpose: any non-error, non-已签到 payload is the
        // success text and is shown to the user exactly as the server wrote it.
        val raw = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
            "<root><![CDATA[签到成功，获得 2 金币]]></root>"

        val outcome = ForumHtmlParser.parseSignInOutcome(raw)

        assertTrue(outcome is ForumSignInOutcome.Success)
        assertEquals("签到成功，获得 2 金币", outcome.message)
    }

    @Test
    fun `rejection text is a failure and never a success`() {
        val forbidden = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
            "<root><![CDATA[抱歉，您所在的用户组(游客)无法进行此操作]]></root>"
        val flood = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
            "<root><![CDATA[对不起，您两次签到间隔少于 1 秒，请稍候再试]]></root>"

        assertTrue(ForumHtmlParser.parseSignInOutcome(forbidden) is ForumSignInOutcome.Failure)
        assertTrue(ForumHtmlParser.parseSignInOutcome(flood) is ForumSignInOutcome.Failure)
        assertEquals(
            "the server's own wording reaches the user",
            "抱歉，您所在的用户组(游客)无法进行此操作",
            ForumHtmlParser.parseSignInOutcome(forbidden).message,
        )
    }

    @Test
    fun `empty or html answer degrades to a failure instead of a success`() {
        assertTrue(ForumHtmlParser.parseSignInOutcome("") is ForumSignInOutcome.Failure)
        assertTrue(
            "a login form is not a sign-in result",
            ForumHtmlParser.parseSignInOutcome("<html><body>login</body></html>") is ForumSignInOutcome.Failure,
        )
    }
}

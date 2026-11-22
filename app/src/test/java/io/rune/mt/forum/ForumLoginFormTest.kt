// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two server responses the password login path depends on.
 *
 * Both fixtures are verbatim captures from the live site: the form page the credential POST is
 * built from, and the `inajax` payload a rejected password produces. The second one matters
 * because a bad password is an HTTP 200, so the only signal is the text.
 */
class ForumLoginFormTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `login page exposes formhash, loginhash and the credential fields`() {
        val html = fixture("login_page.html")
        val formhash = Regex("""name="formhash"[^>]*value='([0-9a-f]+)'""").find(html)
        assertNotNull("formhash missing", formhash)
        assertTrue(formhash!!.groupValues[1].length == 8)

        val loginhash = Regex("""loginhash=([A-Za-z0-9]+)""").find(html)
        assertNotNull("loginhash missing in form action", loginhash)

        assertTrue(html.contains("""name="username""""))
        assertTrue(html.contains("""name="password""""))
        assertTrue(html.contains("""name="cookietime""""))
    }

    @Test
    fun `rejected password is an http 200 carrying the attempt budget`() {
        val body = fixture("login_rejected.xml")
        val message = Regex("""<p>([^<]{2,200})<""").find(body)!!.groupValues[1].trim()
        assertEquals("登录失败，您还可以尝试 4 次", message)
        val remaining = Regex("""还可以尝试\s*(\d+)\s*次""").find(message)!!.groupValues[1].toInt()
        assertEquals(4, remaining)
    }

    @Test
    fun `accepted login answers with a redirect snippet`() {
        val body = fixture("login_accepted.xml")
        val redirect = Regex("""window\.location\.href='([^']+)'""").find(body)
        assertNotNull("accepted login must carry a redirect", redirect)
    }
}

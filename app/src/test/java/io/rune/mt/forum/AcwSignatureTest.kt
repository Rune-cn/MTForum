// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.AcwSignature
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `acw_sc__v2` implementation to a real challenger page captured from the site.
 *
 * The expected signature was produced by an independent JavaScript port of the same algorithm
 * (see docs/REVERSE_SPEC.md), so this test fails if either implementation drifts.
 */
class AcwSignatureTest {

    private fun challenger(): String {
        val path = "src/test/resources/forum/challenger.html"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `challenger page is detected`() {
        val body = challenger()
        assertTrue(AcwSignature.isChallenger(body))
    }

    @Test
    fun `signature matches the javascript reference implementation`() {
        val signature = AcwSignature.solve(challenger())
        assertNotNull(signature)
        assertEquals(40, signature!!.length)
        assertEquals("6b01ffb6ebaa2847ad1eae9554562dbe3fcaefce", signature)
    }

    @Test
    fun `regular page is not a challenger`() {
        val page = File("src/test/resources/forum/board_44.html")
            .takeIf { it.exists() }
            ?: File("app/src/test/resources/forum/board_44.html")
        assertFalse(AcwSignature.isChallenger(page.readText()))
    }
}

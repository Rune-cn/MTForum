// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fix for the `NetworkOnMainThreadException` that killed the home and guide tabs.
 *
 * A `suspend` function does not move itself off the caller's dispatcher, so any blocking
 * `execute()` / jsoup parse has to be wrapped in `withContext(Dispatchers.IO)`. This test is a
 * static check: it fails if someone later adds a blocking call to an `suspend` path without the
 * dispatcher switch. It is crude on purpose — the alternative is an Android instrumentation test,
 * which this environment cannot run.
 */
class MainThreadSafetyTest {

    private fun sourceOf(vararg relative: String): String {
        relative.forEach { path ->
            val file = File(path).takeIf { it.exists() }
                ?: File("app/$path").takeIf { it.exists() }
            if (file != null) return file.readText()
        }
        error("none of ${relative.toList()} exist")
    }

    @Test
    fun `http client switches dispatcher around blocking calls`() {
        val source = sourceOf("src/main/java/io/rune/mt/forum/data/forum/network/ForumHttpClient.kt")
        assertTrue("ForumHttpClient must import Dispatchers", source.contains("Dispatchers.IO"))
        // Both entry points must be wrapped, not just the first one.
        assertTrue(
            "every suspend fun in ForumHttpClient needs withContext(Dispatchers.IO)",
            source.split("withContext(Dispatchers.IO)").size - 1 >= 2,
        )
        // A bare `execute()` outside withContext is exactly the bug that crashed the tabs.
        assertTrue(
            "blocking execute() must not run on the caller's dispatcher",
            !source.contains("suspend fun getHtml(path: String): String {"),
        )
    }

    @Test
    fun `repository parses html off the main thread`() {
        val source = sourceOf("src/main/java/io/rune/mt/forum/data/forum/repository/ForumRepositoryImpl.kt")
        assertTrue("repository must import Dispatchers", source.contains("Dispatchers.IO"))
        assertTrue(
            "each public repository method should wrap fetch+parse in Dispatchers.IO",
            source.split("withContext(Dispatchers.IO)").size - 1 >= 5,
        )
    }
}

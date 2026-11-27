// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.content.Intent
import android.net.Uri
import io.rune.mt.forum.ui.activity.SettingsActivity
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.navigation.forumDeepLinkFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards the manifest side of the feature: every URL shape the parser accepts must also be
 * declared on [SettingsActivity], otherwise the system never hands the link to the app.
 *
 * The query uses the same categories a browser's VIEW intent carries (DEFAULT + BROWSABLE) and
 * resolves through Robolectric's package manager, i.e. through the real intent filters of the
 * merged manifest (pathPattern included).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class ForumDeepLinkManifestTest {

    private val settingsActivity = SettingsActivity::class.java.name

    @Test
    fun `every supported url resolves to the single activity`() {
        listOf(
            "https://bbs.binmt.cc/thread-171354-1-1.html",
            "https://bbs.binmt.cc/thread-171354-3-2.html",
            "https://bbs.binmt.cc/forum-41-1.html",
            "https://bbs.binmt.cc/forum.php?mod=viewthread&tid=171354",
            "https://bbs.binmt.cc/forum.php?mod=forumdisplay&fid=41",
            "https://bbs.binmt.cc/forum.php",
            "mtforum://thread/171354",
            "mtforum://forum/41",
            "mtforum://home",
        ).forEach { url ->
            val resolved = resolve(url)
            assertTrue(
                "$url must resolve to $settingsActivity, got $resolved",
                resolved.any { it.activityInfo.name == settingsActivity },
            )
        }
    }

    @Test
    fun `unrelated urls are not captured`() {
        listOf(
            "https://example.com/thread-171354-1-1.html",
            "https://bbs.binmt.cc/space-uid-1.html",
            "mtforum://other/1",
        ).forEach { url ->
            assertTrue("$url must stay outside the app", resolve(url).isEmpty())
        }
    }

    @Test
    fun `forum php is captured whatever its query`() {
        // The /forum.php filter cannot express "only mod=viewthread|forumdisplay", so every
        // forum.php URL (guide views, search, ...) reaches the app. That is acceptable because
        // the parser rejects the unknown mod: the app opens on the shell and does not navigate.
        val guideUrl = "https://bbs.binmt.cc/forum.php?mod=guide&view=hot&index=1&page=1"
        assertTrue(
            "$guideUrl must still be captured by the forum.php filter",
            resolve(guideUrl).any { it.activityInfo.name == settingsActivity },
        )
        assertNull(forumDeepLinkFrom(Uri.parse(guideUrl)))
        // The bare board index is the home link and parses to the shell.
        assertEquals(Route.Main, forumDeepLinkFrom(Uri.parse("https://bbs.binmt.cc/forum.php")))
    }

    private fun resolve(url: String) = RuntimeEnvironment.getApplication().packageManager
        .queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
            },
            0,
        )
}
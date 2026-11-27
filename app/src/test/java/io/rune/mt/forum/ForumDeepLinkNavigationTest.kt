// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.ui.navigation.Navigator
import io.rune.mt.forum.ui.navigation.Route
import kotlin.test.assertEquals
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

/**
 * The deep-link jump is `[Main, route]`: back from a link always lands on the forum shell, never
 * on a page that happened to be open when the link arrived. This exercises the real [Navigator]
 * over a real nav back stack, without Compose, Koin or the site.
 */
class ForumDeepLinkNavigationTest {

    private fun navigator(vararg routes: Route): Navigator =
        Navigator(navBackStackOf(*routes))

    @Test
    fun `deep link from the bare shell lands on top of main`() {
        val navigator = navigator(Route.Main)

        navigator.navigateToDeepLink(Route.ForumThread(171354, ""))

        assertEquals(listOf(Route.Main, Route.ForumThread(171354, "")), navigator.backStack.toList())
        assertEquals(Route.ForumThread(171354, ""), navigator.current())
    }

    @Test
    fun `deep link collapses an arbitrary stack back to the shell`() {
        val navigator = navigator(Route.Main, Route.ForumList(41, ""))

        navigator.navigateToDeepLink(Route.ForumThread(7, ""))

        assertEquals(listOf(Route.Main, Route.ForumThread(7, "")), navigator.backStack.toList())
    }

    @Test
    fun `deep link replaces the previous deep link`() {
        val navigator = navigator(Route.Main)

        navigator.navigateToDeepLink(Route.ForumThread(1, ""))
        navigator.navigateToDeepLink(Route.ForumList(41, ""))

        assertEquals(listOf(Route.Main, Route.ForumList(41, "")), navigator.backStack.toList())
    }

    @Test
    fun `repeated identical deep link does not grow the stack`() {
        val navigator = navigator(Route.Main, Route.ForumThread(1, ""))

        navigator.navigateToDeepLink(Route.ForumThread(1, ""))

        assertEquals(listOf(Route.Main, Route.ForumThread(1, "")), navigator.backStack.toList())
        assertEquals(2, navigator.backStackSize())
    }

    @Test
    fun `home deep link collapses a deep stack to the bare shell`() {
        val navigator = navigator(Route.Main, Route.ForumList(41, ""), Route.ForumThread(7, ""))

        navigator.navigateToDeepLink(Route.Main)

        // The home link's destination *is* the shell: no duplicate Main is pushed, and back from
        // here exits the app instead of returning to the pages the link replaced.
        assertEquals(listOf(Route.Main), navigator.backStack.toList())
        assertEquals(Route.Main, navigator.current())
    }

    @Test
    fun `home deep link on the bare shell is a no op`() {
        val navigator = navigator(Route.Main)

        navigator.navigateToDeepLink(Route.Main)

        assertEquals(listOf(Route.Main), navigator.backStack.toList())
        assertEquals(1, navigator.backStackSize())
    }
}
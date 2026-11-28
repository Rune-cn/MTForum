// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.ui.navigation.Navigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.navigation.isSameDestinationAs
import io.rune.mt.forum.ui.navigation.navigationInstanceId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

/**
 * Every back-stack entry needs a unique content key.
 *
 * `NavDisplay` derives an entry's identity from its content key and throws
 * `IllegalArgumentException: Duplicate contentKey on the back stack: ForumThread(tid=…)` when two
 * entries resolve to the same one — the 26.11.28 crash: a thread reachable twice (a quote link,
 * 浏览历史, a reply author's profile) was pushed while an equal copy was still on the stack.
 *
 * `Navigator` is the single writer of back-stack keys, so these tests pin the three rules it
 * enforces: the visible page is never pushed twice, a singleton page is revealed instead of
 * duplicated, and a repeatable page (thread / board / profile / conversation / search / composer)
 * gets a per-entry `instanceId`. Content keys are the route values themselves, hence the
 * `toString()` uniqueness assertions. Runs on the real back stack, without Compose, Koin or site.
 */
class NavigatorBackStackTest {

    private fun navigatorOf(vararg routes: Route): Navigator = Navigator(navBackStackOf(*routes))

    /** What NavDisplay uses as the entry identity: the default content key is the route itself. */
    private fun Navigator.contentKeys(): List<String> = backStack.map { it.toString() }

    private fun Navigator.assertUniqueContentKeys() {
        val keys = contentKeys()
        assertEquals(keys.size, keys.toSet().size, "content keys must be unique: $keys")
    }

    private fun Navigator.threadInstances(): List<Long> = backStack
        .filterIsInstance<Route.ForumThread>()
        .map { it.navigationInstanceId }

    @Test
    fun `opening a thread that is still in the stack keeps both entries distinct`() {
        val nav = navigatorOf(Route.Main)
        nav.push(Route.ForumThread(177487, "崩溃帖"))
        nav.push(Route.UserProfile(12, "作者"))
        nav.push(Route.ForumThread(177487, "崩溃帖"))

        assertEquals(4, nav.backStackSize())
        nav.assertUniqueContentKeys()
        assertEquals(listOf(0L, 1L), nav.threadInstances())
    }

    @Test
    fun `pushing the page that is already on screen is ignored`() {
        val nav = navigatorOf(Route.Main)
        nav.push(Route.ForumThread(7, "帖"))
        nav.push(Route.ForumThread(7, "帖"))

        assertEquals(2, nav.backStackSize())
        nav.assertUniqueContentKeys()
    }

    @Test
    fun `a refreshed title is the same page, not a new one`() {
        val nav = navigatorOf(Route.Main, Route.ForumThread(7, "旧标题"))
        nav.push(Route.ForumThread(7, "新标题"))

        assertEquals(2, nav.backStackSize())
        assertEquals("旧标题", (nav.current() as Route.ForumThread).title)
    }

    @Test
    fun `a singleton page that is already open is revealed instead of stacked`() {
        val nav = navigatorOf(Route.Main, Route.Favorites, Route.ForumThread(7, "帖"))
        nav.push(Route.Favorites)

        assertEquals(listOf(Route.Main, Route.Favorites), nav.backStack.toList())
        assertEquals(Route.Favorites, nav.current())
    }

    @Test
    fun `a duplicated thread survives the saved back stack`() {
        // Process-death restore goes through kotlinx.serialization (`rememberNavBackStack`); the
        // instance id has to round-trip, or a restored stack would come back with duplicate keys.
        val stack = listOf<Route>(
            Route.Main,
            Route.ForumThread(7, "帖"),
            Route.ForumThread(7, "帖", instanceId = 1),
        )
        val json = Json { ignoreUnknownKeys = true }

        val restored = json.decodeFromString(
            serializer<List<Route>>(),
            json.encodeToString(serializer<List<Route>>(), stack),
        )

        assertEquals(stack, restored)
    }

    @Test
    fun `a restored stack with a duplicated destination is normalised`() {
        val nav = navigatorOf(Route.Main, Route.ForumThread(7, "帖"))
        // A stack saved by a build without per-entry instance ids can hold the same page twice;
        // NavDisplay would reject those duplicate content keys while reconciling.
        nav.backStack.add(Route.ForumThread(7, "帖"))

        nav.dropDuplicateDestinations()

        assertEquals(listOf(Route.Main, Route.ForumThread(7, "帖")), nav.backStack.toList())
    }

    @Test
    fun `re-opening after a pop never reuses an instance id`() {
        val nav = navigatorOf(Route.Main)
        nav.push(Route.ForumThread(7, "帖"))
        nav.push(Route.UserProfile(3, "作者"))
        nav.push(Route.ForumThread(7, "帖"))
        nav.pop()

        nav.push(Route.ForumThread(7, "帖"))

        val instances = nav.threadInstances()
        assertEquals(2, instances.size)
        assertEquals(instances.size, instances.toSet().size, "instance ids must not be reused: $instances")
        nav.assertUniqueContentKeys()
    }

    @Test
    fun `content keys stay unique across repeated board-thread-profile rounds`() {
        val nav = navigatorOf(Route.Main)
        repeat(3) {
            nav.push(Route.ForumList(41, "版块"))
            nav.push(Route.ForumThread(7, "帖"))
            nav.push(Route.UserProfile(3, "作者"))
        }

        assertEquals(10, nav.backStackSize())
        nav.assertUniqueContentKeys()
    }

    @Test
    fun `destination identity ignores cached text and the instance id`() {
        assertTrue(Route.ForumThread(7, "a").isSameDestinationAs(Route.ForumThread(7, "b")))
        assertTrue(
            Route.ForumThread(7, "a", instanceId = 3)
                .isSameDestinationAs(Route.ForumThread(7, "a", instanceId = 0)),
        )
        assertFalse(Route.ForumThread(7, "a").isSameDestinationAs(Route.ForumThread(8, "a")))
        assertFalse(Route.ForumThread(7, "a").isSameDestinationAs(Route.ForumList(7, "a")))
        assertTrue(Route.Favorites.isSameDestinationAs(Route.Favorites))
        assertFalse(Route.Favorites.isSameDestinationAs(Route.Drafts))
    }
}

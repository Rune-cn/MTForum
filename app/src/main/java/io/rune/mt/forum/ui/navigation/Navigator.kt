// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey

// kang from KernelSU manager
// with some modify (anti reenter page) from ReSukiSU manager
class Navigator(val backStack: NavBackStack) {
    private val resultBus = mutableMapOf<String, MutableSharedFlow<Any>>()

    /**
     * Highest [RepeatableRoute.instanceId] handed out so far, seeded from a restored back stack.
     *
     * `NavDisplay` folds two entries that resolve to equal content keys into one, so a page popped
     * and re-opened must not reuse the id of an entry that is still animating out. Ids are
     * therefore monotonically increasing for this [Navigator] and only ever grow past the stack's
     * own ids, which also keeps a process-death restore collision free.
     */
    private var instanceHighWater: Long = backStack.maxOfOrNull { (it as? Route)?.navigationInstanceId ?: 0L } ?: 0L

    /**
     * Push a key onto the back stack.
     *
     * This is the single place that turns a caller's [Route] into a back-stack key, and it is
     * what keeps every entry's content key unique (`NavDisplay` rejects duplicates with
     * `IllegalArgumentException: Duplicate contentKey on the back stack`):
     *
     *  - the page that is already on screen is not pushed a second time (a double tap, a row that
     *    fires twice);
     *  - a singleton destination that is still open further down is *revealed* — the pages above
     *    it are popped — instead of stacking a second copy;
     *  - a repeatable destination ([RepeatableRoute]) that is still open further down is pushed
     *    as a new instance with its own [RepeatableRoute.instanceId], so back returns to the page
     *    it was opened from.
     */
    fun push(key: NavKey) {
        val top = backStack.lastOrNull()
        if (top != null && top.isSameDestinationAs(key)) {
            Timber.tag("Navigator").i("Trying push current page to backStack again, ignore!")
            return
        }
        val route = key as? Route
        if (route == null) {
            backStack.add(key)
            return
        }
        if (!route.repeatsOnBackStack && backStack.any { it.isSameDestinationAs(route) }) {
            // Singleton page (设置/收藏/提醒/…): one copy is enough, show the open one. Pushing a
            // second copy would give two entries the same content key and crash the NavDisplay.
            Timber.tag("Navigator").i("Page already on the back stack, reveal it: %s", route)
            popUntil { it.isSameDestinationAs(route) }
            return
        }
        backStack.add(route.stampedFor(backStack))
    }

    // Replace the top key, or push if the stack is empty.
    fun replace(key: NavKey) {
        if (backStack.isEmpty()) {
            push(key)
            return
        }
        backStack.removeAt(backStack.lastIndex)
        backStack.add((key as? Route)?.stampedFor(backStack) ?: key)
    }

    // Replace the backstack with a new list of keys if the stack is not empty.
    fun replaceAll(keys: List<NavKey>) {
        if (keys.isEmpty()) {
            return
        }
        if (backStack.isNotEmpty()) {
            backStack.clear()
            // Stamp against the keys already re-added, so even a caller that passes the same
            // destination twice ends up with two distinct content keys.
            for (key in keys.toList()) {
                backStack.add((key as? Route)?.stampedFor(backStack) ?: key)
            }
        }
    }

    /**
     * [this] ready for the back stack: the first copy of a page keeps the caller's value
     * (`instanceId = 0`), a copy of a destination that is already open gets a fresh instance id.
     */
    private fun Route.stampedFor(stack: List<NavKey>): Route {
        if (!repeatsOnBackStack) return this
        if (stack.none { it.isSameDestinationAs(this) }) {
            return if (navigationInstanceId == 0L) this else withNavigationInstance(0L)
        }
        val highest = stack.maxOfOrNull { (it as? Route)?.navigationInstanceId ?: 0L } ?: 0L
        val instance = maxOf(instanceHighWater, highest) + 1
        instanceHighWater = instance
        return withNavigationInstance(instance)
    }

    /**
     * Drops second copies of a destination from a **restored** back stack.
     *
     * `rememberNavBackStack` brings back exactly what the previous process saved, and a stack
     * saved before the per-instance keys above existed can hold the same destination twice.
     * `NavDisplay` rejects duplicate content keys while it reconciles, so such a restore would
     * crash on every relaunch. The topmost copy wins: that is the page the user was looking at.
     */
    fun dropDuplicateDestinations() {
        val kept = ArrayList<NavKey>(backStack.size)
        for (index in backStack.indices.reversed()) {
            val key = backStack[index]
            if (kept.none { it.isSameDestinationAs(key) }) kept.add(key)
        }
        if (kept.size == backStack.size) return
        kept.reverse()
        Timber.tag("Navigator").i("Dropped %d duplicate back stack entr(ies)", backStack.size - kept.size)
        backStack.clear()
        backStack.addAll(kept)
    }

    /**
     * Jumps to a deep-linked destination on top of the shell.
     *
     * Collapsing to `[Main, route]` keeps back predictable: from a link it lands on the forum
     * home rather than a page that happened to be open when the link was tapped. When the stack
     * is already the bare shell a plain push produces the same stack without a clear+refill,
     * which would make the NavDisplay drop the entry it is currently showing.
     *
     * A home link (`Route.Main`) collapses to the bare shell — not `[Main, Main]` — and does not
     * push anything: the shell itself is the destination, and the caller selects the 论坛 pager
     * page through `SettingsSharedViewModel.requestMainPage`.
     */
    fun navigateToDeepLink(route: Route) {
        if (route == Route.Main) {
            if (backStack.size == 1 && backStack.lastOrNull() == Route.Main) return
            replaceAll(listOf(Route.Main))
            return
        }
        if (backStack.size == 1 && backStack.lastOrNull() == Route.Main) {
            push(route)
        } else {
            replaceAll(listOf(Route.Main, route))
        }
    }

    private var lastPopTime = 0L

    // Pop the top key if present.
    fun pop() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPopTime < 100) {
            Timber.tag("Navigator").i("pop call more than 1 times in 100ms, ignore!")
            return
        }

        if (backStackSize() <= 1) return

        lastPopTime = System.currentTimeMillis()
        backStack.removeLastOrNull()
    }

    // Pop until predicate matches the top key.
    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.isNotEmpty() && !predicate(backStack.last())) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    // Navigate expecting a result. Caller should subscribe via observeResult(requestKey).
    fun navigateForResult(route: Route, requestKey: String) {
        ensureChannel(requestKey)
        push(route)
    }

    // Set a result for the given request and then pop.
    fun <T : Any> setResult(requestKey: String, value: T) {
        ensureChannel(requestKey).tryEmit(value)
        pop()
    }

    // Observe results for a given request key as a SharedFlow.
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observeResult(requestKey: String): SharedFlow<T> = ensureChannel(requestKey) as SharedFlow<T>

    // Clear the last emitted result for the request key.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearResult(requestKey: String) {
        ensureChannel(requestKey).resetReplayCache()
    }

    // Get current NavKey on the back stack.
    fun current(): NavKey? = backStack.lastOrNull()

    // Get current size of back stack.
    fun backStackSize(): Int = backStack.size

    private fun ensureChannel(key: String): MutableSharedFlow<Any> = resultBus.getOrPut(key) {
        MutableSharedFlow(replay = 1, extraBufferCapacity = 0)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.plugin

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * The one channel a plugin (or its card) uses to ask the app to open a page.
 *
 * A Lua script runs on an IO thread with no composition and no navigator, and the plugin page's
 * "打开" button must behave exactly like the script's `ctx.ui.open(id)` — so both publish a page
 * id here and `AppNavContainer` is the single subscriber that turns it into a route
 * (`pluginBuiltinRoute`). Keeping the navigator out of the data layer is also why this is a plain
 * flow of ids rather than a route type: routes stay in `ui/navigation`.
 *
 * The buffer is one-shot with `DROP_OLDEST`: a tap that arrives while the last one is still being
 * collected must not open two pages or throw.
 */
class PluginUiBridge {

    private val _requests = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Page ids requested by plugins, oldest dropped when the consumer is slow. */
    val requests: SharedFlow<String> = _requests.asSharedFlow()

    /**
     * Requests the built-in page [builtinId].
     *
     * Unknown ids are logged and dropped: a stale script must not push a route that no longer
     * exists (and `AppNavContainer` would ignore it anyway).
     */
    fun open(builtinId: String) {
        val page = builtinId.trim().lowercase()
        if (page !in BuiltinPlugins.PAGE_IDS) {
            Timber.tag(TAG).w("ui.open ignored unknown page id=%s", page)
            return
        }
        if (!_requests.tryEmit(page)) {
            Timber.tag(TAG).w("ui.open dropped, no collector ready id=%s", page)
        }
    }

    private companion object {
        const val TAG = "PluginUi"
    }
}

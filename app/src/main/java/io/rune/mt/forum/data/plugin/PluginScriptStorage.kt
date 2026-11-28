// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.plugin

import android.content.Context

/**
 * The per-plugin key/value store behind `ctx.storage.get/set`.
 *
 * A plain SharedPreferences file, one for all plugins, with the plugin id as the key prefix: a
 * script's values must be tiny (a preference, a cursor, a cache key) and synchronous — the Lua
 * host calls this from inside a blocking script, where a DataStore transaction would need its own
 * dispatcher and could still be cancelled with the plugin's timeout.
 *
 * Values are strings. Lua hands over numbers/booleans through `tostring`, which is what the host
 * documents to script authors; a script that wants structure stores its own JSON.
 */
class PluginScriptStorage(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored value of [key] for [pluginId], or `null` when nothing was ever written. */
    fun get(pluginId: String, key: String): String? =
        prefs.getString(keyOf(pluginId, key), null)?.takeIf { it.isNotEmpty() }

    /**
     * Stores [value] for [key]. Blank keys, over-long keys and over-long values are ignored: the
     * store is a convenience, so a runaway script must not be able to bloat the preference file.
     */
    fun set(pluginId: String, key: String, value: String) {
        val name = key.trim()
        if (name.isEmpty() || name.length > MAX_KEY_LENGTH) return
        prefs.edit()
            .putString(keyOf(pluginId, name), value.take(MAX_VALUE_LENGTH))
            .apply()
    }

    /** Drops every value of [pluginId]; called when the plugin is uninstalled. */
    fun clear(pluginId: String) {
        val prefix = prefixOf(pluginId)
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun keyOf(pluginId: String, key: String): String = prefixOf(pluginId) + key

    private fun prefixOf(pluginId: String): String = "$pluginId::"

    private companion object {
        const val PREFS_NAME = "plugin_storage"
        const val MAX_KEY_LENGTH = 120
        const val MAX_VALUE_LENGTH = 8_192
    }
}

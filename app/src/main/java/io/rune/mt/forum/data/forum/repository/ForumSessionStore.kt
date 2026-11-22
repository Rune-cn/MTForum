// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.forumStore by preferencesDataStore(name = "mt_forum_session")

/** Persists the Discuz cookie set so the login survives process death. */
class ForumSessionStore(private val context: Context) {

    private val cookiesKey = stringPreferencesKey("cookies_json")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Map<String, String> {
        val raw = context.forumStore.data.first()[cookiesKey] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    suspend fun save(cookies: Map<String, String>) {
        val encoded = json.encodeToString(cookies)
        context.forumStore.edit { it[cookiesKey] = encoded }
    }

    suspend fun clear() {
        context.forumStore.edit { it.remove(cookiesKey) }
    }
}

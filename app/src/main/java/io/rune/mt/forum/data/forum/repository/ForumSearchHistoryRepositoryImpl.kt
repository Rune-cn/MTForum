// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.rune.mt.forum.domain.forum.repository.FORUM_SEARCH_HISTORY_LIMIT
import io.rune.mt.forum.domain.forum.repository.ForumSearchHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * [ForumSearchHistoryRepository] backed by its own Preferences DataStore
 * (`files/datastore/forum_search_history`) so the recent keywords survive process death without
 * sharing a schema with the drafts or the app settings.
 *
 * The list is one JSON string under [KEY]: recording a keyword is a single atomic `edit`, so two
 * fast searches cannot interleave into a half-written list. A document that does not decode (a
 * partial write, a future schema) degrades to an empty list, which the overlay renders as "no
 * history" instead of crashing the home page.
 */
class ForumSearchHistoryRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) : ForumSearchHistoryRepository {

    override val recent: Flow<List<String>> = dataStore.data.map { prefs -> decode(prefs[KEY]) }

    override suspend fun record(keyword: String) = withContext(Dispatchers.IO) {
        val entry = keyword.trim()
        if (entry.isEmpty()) return@withContext
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            // Same word (any case) moves to the front instead of being stored twice.
            val next = (listOf(entry) + current.filterNot { it.equals(entry, ignoreCase = true) })
                .take(FORUM_SEARCH_HISTORY_LIMIT)
            prefs[KEY] = json.encodeToString(SERIALIZER, next)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> prefs.remove(KEY) }
        Unit
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(SERIALIZER, raw) }
            .onFailure { Timber.tag(TAG).w(it, "search history unreadable; treating as empty") }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .take(FORUM_SEARCH_HISTORY_LIMIT)
    }

    private companion object {
        const val TAG = "ForumSearchHistory"
        const val KEY_NAME = "forum_search_history_v1"
        val KEY = stringPreferencesKey(KEY_NAME)
        val SERIALIZER = ListSerializer(String.serializer())
    }
}
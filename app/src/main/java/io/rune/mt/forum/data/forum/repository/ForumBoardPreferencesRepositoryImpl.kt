// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.rune.mt.forum.domain.forum.model.ForumBoardPreferences
import io.rune.mt.forum.domain.forum.repository.ForumBoardPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * [ForumBoardPreferencesRepository] backed by the app settings DataStore
 * (`forum_board_prefs_v1`).
 *
 * One JSON document holds both the user's order and their hidden boards, so a reorder or a hide is
 * a single atomic write. A damaged document degrades to "no customisation" (server order, nothing
 * hidden) rather than breaking the home grid.
 */
class ForumBoardPreferencesRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) : ForumBoardPreferencesRepository {

    override val preferences: Flow<ForumBoardPreferences> = dataStore.data.map { prefs ->
        decode(prefs[KEY])
    }

    override suspend fun update(transform: (ForumBoardPreferences) -> ForumBoardPreferences) =
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val current = sanitize(decode(prefs[KEY]))
                val next = sanitize(transform(current))
                prefs[KEY] = json.encodeToString(ForumBoardPreferences.serializer(), next)
            }
            Unit
        }

    private fun decode(raw: String?): ForumBoardPreferences {
        if (raw.isNullOrBlank()) return ForumBoardPreferences()
        return runCatching { json.decodeFromString(ForumBoardPreferences.serializer(), raw) }
            .onFailure { Timber.tag(TAG).w(it, "board preferences unreadable; treating as default") }
            .getOrDefault(ForumBoardPreferences())
    }

    private fun sanitize(prefs: ForumBoardPreferences): ForumBoardPreferences =
        prefs.copy(
            orderedFids = prefs.orderedFids.distinct(),
            hiddenFids = prefs.hiddenFids.distinct(),
        )

    private companion object {
        const val TAG = "BoardPrefsRepo"
        const val KEY_NAME = "forum_board_prefs_v1"
        val KEY = stringPreferencesKey(KEY_NAME)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.rune.mt.forum.domain.forum.model.ForumThreadHistoryEntry
import io.rune.mt.forum.domain.forum.repository.FORUM_THREAD_HISTORY_LIMIT
import io.rune.mt.forum.domain.forum.repository.ForumThreadHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * [ForumThreadHistoryRepository] backed by its own Preferences DataStore
 * (`files/datastore/forum_thread_history`) so the visits survive process death without sharing a
 * schema with drafts, the recent searches or the app settings — clearing the history therefore
 * cannot touch any of them.
 *
 * The list is one JSON string under [KEY] (the same shape as the recent-search list in
 * `ForumSearchHistoryRepositoryImpl`): recording a visit is a single atomic `edit`, so two fast
 * opens cannot interleave into a half-written document. The entry list is kept newest-first and
 * capped at [FORUM_THREAD_HISTORY_LIMIT], one row per tid.
 *
 * A document that does not decode (a partial write, a future schema) degrades to an empty list,
 * which the page renders as "还没有浏览记录" instead of failing the screen.
 */
class ForumThreadHistoryRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    /** Injectable clock for deterministic eviction tests; the default is wall-clock time. */
    private val now: () -> Long = System::currentTimeMillis,
) : ForumThreadHistoryRepository {

    override val entries: Flow<List<ForumThreadHistoryEntry>> =
        dataStore.data.map { prefs -> decode(prefs[KEY]) }

    override suspend fun record(tid: Int, title: String, forumName: String?) =
        withContext(Dispatchers.IO) {
            if (tid <= 0) return@withContext
            val entry = ForumThreadHistoryEntry(
                tid = tid,
                title = title.trim(),
                forumName = forumName?.trim()?.takeIf { it.isNotEmpty() },
                visitedAt = now(),
            )
            dataStore.edit { prefs ->
                // Same tid -> replaced, so a repeated visit only refreshes the timestamp.
                val current = decode(prefs[KEY]).filterNot { it.tid == tid }
                val next = (listOf(entry) + current)
                    .sortedByDescending { it.visitedAt }
                    .take(FORUM_THREAD_HISTORY_LIMIT)
                prefs[KEY] = json.encodeToString(SERIALIZER, next)
            }
        }

    override suspend fun remove(tid: Int) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            if (current.any { it.tid == tid }) {
                prefs[KEY] = json.encodeToString(SERIALIZER, current.filterNot { it.tid == tid })
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> prefs.remove(KEY) }
        Unit
    }

    private fun decode(raw: String?): List<ForumThreadHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(SERIALIZER, raw) }
            .onFailure { Timber.tag(TAG).w(it, "thread history unreadable; treating as empty") }
            .getOrDefault(emptyList())
            .sortedByDescending { it.visitedAt }
            .filter { it.tid > 0 }
            // A corrupted document may repeat a tid; keep its newest visit only.
            .distinctBy { it.tid }
            .take(FORUM_THREAD_HISTORY_LIMIT)
    }

    private companion object {
        const val TAG = "ForumThreadHistory"
        const val KEY_NAME = "forum_thread_history_v1"
        val KEY = stringPreferencesKey(KEY_NAME)
        val SERIALIZER = ListSerializer(ForumThreadHistoryEntry.serializer())
    }
}

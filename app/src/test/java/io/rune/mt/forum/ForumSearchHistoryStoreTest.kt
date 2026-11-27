// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.rune.mt.forum.data.forum.repository.ForumSearchHistoryRepositoryImpl
import io.rune.mt.forum.domain.forum.repository.FORUM_SEARCH_HISTORY_LIMIT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The recent-search list behind the home magnifier: newest first, de-duplicated, capped, clearable
 * and safe to read after a damaged document.
 *
 * The repository is driven through [`edit`]/[DataStore] exactly like production, but the backing
 * `DataStore<Preferences>` is the in-memory double used by `ForumDraftStoreTest` (a real
 * file-backed DataStore cannot replace its file on Windows). What is under test is our own logic:
 * ordering, de-duplication, the cap and graceful degradation.
 */
class ForumSearchHistoryStoreTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: ForumSearchHistoryRepositoryImpl

    @Before
    fun setUp() {
        dataStore = SearchHistoryMemoryDataStore()
        repository = ForumSearchHistoryRepositoryImpl(
            dataStore = dataStore,
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @Test
    fun `recording puts the newest keyword first`() = runBlocking {
        repository.record("逆向")
        repository.record("MT管理器")

        assertEquals(listOf("MT管理器", "逆向"), repository.recent.first())
    }

    @Test
    fun `recording the same keyword moves it to the front instead of duplicating`() = runBlocking {
        repository.record("挂机")
        repository.record("逆向")
        repository.record("挂机")

        assertEquals(listOf("挂机", "逆向"), repository.recent.first())
    }

    @Test
    fun `de-duplication is case-insensitive and trims whitespace`() = runBlocking {
        repository.record("DeepSeek")
        repository.record("  deepseek  ")

        assertEquals(listOf("deepseek"), repository.recent.first())
    }

    @Test
    fun `blank keywords are ignored`() = runBlocking {
        repository.record("  ")
        repository.record("")

        assertTrue(repository.recent.first().isEmpty())
    }

    @Test
    fun `history is capped and the oldest entries are evicted`() = runBlocking {
        val overflow = FORUM_SEARCH_HISTORY_LIMIT + 2
        repeat(overflow) { index -> repository.record("关键词$index") }

        val stored = repository.recent.first()
        assertEquals(FORUM_SEARCH_HISTORY_LIMIT, stored.size)
        // Newest first: the two oldest entries fell off the end.
        assertEquals("关键词${overflow - 1}", stored.first())
        assertEquals("关键词2", stored.last())
    }

    @Test
    fun `clear removes every stored keyword`() = runBlocking {
        repository.record("逆向")
        repository.record("MT管理器")
        repository.clear()

        assertTrue(repository.recent.first().isEmpty())
    }

    @Test
    fun `a corrupted document degrades to an empty list and recovers`() = runBlocking {
        repository.record("保留了？")
        dataStore.edit { it[stringPreferencesKey("forum_search_history_v1")] = "{not json" }

        assertTrue(repository.recent.first().isEmpty())
        // The next record overwrites the garbage instead of failing forever.
        repository.record("fresh")
        assertEquals(listOf("fresh"), repository.recent.first())
    }
}

/** In-memory [DataStore] of [Preferences]; see the class KDoc for why the real one is unusable. */
private class SearchHistoryMemoryDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
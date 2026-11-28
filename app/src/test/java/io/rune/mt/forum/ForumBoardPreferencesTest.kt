// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.rune.mt.forum.data.forum.repository.ForumBoardPreferencesRepositoryImpl
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumBoardPreferences
import io.rune.mt.forum.domain.forum.model.arrangeForums
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

private fun board(fid: Int) = Forum(fid = fid, name = "b$fid")

/** The board order/hide merge rule and its persistence. */
class ForumBoardPreferencesTest {

    @Test
    fun `empty preferences keep the server order`() {
        val server = listOf(board(1), board(2), board(3))

        assertEquals(listOf(1, 2, 3), arrangeForums(server, ForumBoardPreferences()).map { it.fid })
    }

    @Test
    fun `hidden boards leave the list`() {
        val server = listOf(board(1), board(2), board(3))

        val arranged = arrangeForums(server, ForumBoardPreferences(hiddenFids = listOf(2)))

        assertEquals(listOf(1, 3), arranged.map { it.fid })
    }

    @Test
    fun `includeHidden keeps the hidden boards for the settings page`() {
        val server = listOf(board(1), board(2), board(3))

        val arranged = arrangeForums(
            server,
            ForumBoardPreferences(hiddenFids = listOf(2)),
            includeHidden = true,
        )

        assertEquals(listOf(1, 2, 3), arranged.map { it.fid })
    }

    @Test
    fun `a user reorder wins for known boards`() {
        val server = listOf(board(1), board(2), board(3))

        val arranged = arrangeForums(server, ForumBoardPreferences(orderedFids = listOf(3, 1, 2)))

        assertEquals(listOf(3, 1, 2), arranged.map { it.fid })
    }

    @Test
    fun `a board the server adds later keeps its server slot after the known region`() {
        // 1 and 2 were reordered; 3 and 4 are new (never seen), so they keep their server position
        // after the reordered pair instead of being dumped at the end.
        val server = listOf(board(1), board(2), board(3), board(4))

        val arranged = arrangeForums(server, ForumBoardPreferences(orderedFids = listOf(2, 1)))

        assertEquals(listOf(2, 1, 3, 4), arranged.map { it.fid })
    }

    @Test
    fun `a new board in front keeps the front server slot`() {
        // 4 is new and first on the server; the known boards keep the user order after it.
        val server = listOf(board(4), board(1), board(2), board(3))

        val arranged = arrangeForums(server, ForumBoardPreferences(orderedFids = listOf(3, 1, 2)))

        assertEquals(listOf(4, 3, 1, 2), arranged.map { it.fid })
    }

    @Test
    fun `fids the server no longer returns are ignored`() {
        val server = listOf(board(1), board(2), board(3))

        val arranged = arrangeForums(server, ForumBoardPreferences(orderedFids = listOf(9, 2, 1)))

        assertEquals(listOf(2, 1, 3), arranged.map { it.fid })
    }

    @Test
    fun `order and hidden persist across a fresh repository`() = runBlocking {
        val dataStore = BoardPrefsMemoryDataStore()
        val repo = ForumBoardPreferencesRepositoryImpl(dataStore, Json { ignoreUnknownKeys = true })

        repo.update { it.copy(orderedFids = listOf(3, 1, 2), hiddenFids = listOf(4)) }

        val reopened = ForumBoardPreferencesRepositoryImpl(dataStore, Json { ignoreUnknownKeys = true })
        val stored = reopened.preferences.first()
        assertEquals(listOf(3, 1, 2), stored.orderedFids)
        assertEquals(listOf(4), stored.hiddenFids)
    }

    @Test
    fun `a corrupted document degrades to no customisation`() = runBlocking {
        val dataStore = BoardPrefsMemoryDataStore()
        val repo = ForumBoardPreferencesRepositoryImpl(dataStore, Json { ignoreUnknownKeys = true })
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[androidx.datastore.preferences.core.stringPreferencesKey("forum_board_prefs_v1")] = "{bad"
            }
        }

        val stored = repo.preferences.first()
        assertEquals(emptyList<Int>(), stored.orderedFids)
        assertEquals(emptyList<Int>(), stored.hiddenFids)
    }
}

private class BoardPrefsMemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

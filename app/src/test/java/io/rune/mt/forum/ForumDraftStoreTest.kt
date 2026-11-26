// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.rune.mt.forum.data.forum.repository.ForumDraftRepositoryImpl
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumReplyDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Offline checks for the client-local draft store.
 *
 * The repository is driven through [`edit`]/[DataStore] exactly like production, but the backing
 * `DataStore<Preferences>` is an in-memory double: the real file-backed DataStore cannot rename a
 * temp file over an existing `.preferences_pb` on Windows (`File.renameTo` refuses to replace), so
 * every second write throws in this JVM test environment — on Android the rename is atomic and
 * replaces. What this file covers is the part that is ours: JSON round-trip of every field,
 * same-context replacement, deletion, the eviction cap and graceful degradation of corrupt data.
 */
class ForumDraftStoreTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: ForumDraftRepositoryImpl

    private var clock = 1_000L

    @Before
    fun setUp() {
        dataStore = InMemoryPreferencesDataStore()
        store = ForumDraftRepositoryImpl(
            dataStore = dataStore,
            json = Json { ignoreUnknownKeys = true },
            now = { clock },
        )
    }

    @Test
    fun `new thread draft round-trips every field including the image URI list`() = runBlocking {
        clock = 4_242L
        val draft = ForumNewThreadDraft(
            fid = 44,
            forumName = "综合交流",
            subject = "标题 with English",
            message = "[b]正文[/b]\n第二行",
            imageUris = listOf(
                "content://media/picker/0/com.android.providers.media.photopicker/media/1001",
                "content://com.android.externalstorage.documents/document/primary%3ADCIM%2FIMG_0002.jpg",
            ),
        )
        store.saveNewThreadDraft(draft)

        val restored = store.newThreadDraft(44)
        assertNotNull(restored)
        assertEquals(draft.fid, restored!!.fid)
        assertEquals(draft.forumName, restored.forumName)
        assertEquals(draft.subject, restored.subject)
        assertEquals(draft.message, restored.message)
        assertEquals(draft.imageUris, restored.imageUris)
        assertEquals("the store stamps savedAt from its own clock", 4_242L, restored.savedAt)
        assertEquals(1, store.draftCount())
    }

    @Test
    fun `saving the same board replaces the previous draft instead of appending`() = runBlocking {
        clock = 10L
        store.saveNewThreadDraft(ForumNewThreadDraft(fid = 38, subject = "first", message = "a"))
        clock = 20L
        store.saveNewThreadDraft(ForumNewThreadDraft(fid = 38, subject = "second", message = "b"))

        val stored = store.newThreadDraft(38)
        assertEquals("second", stored?.subject)
        assertEquals("b", stored?.message)
        assertEquals(20L, stored?.savedAt)
        assertEquals(1, store.draftCount())
    }

    @Test
    fun `reply draft keeps the quoted floor and is removed by delete`() = runBlocking {
        store.saveReplyDraft(
            ForumReplyDraft(
                tid = 177_093,
                quoteAuthor = "Rune",
                quoteText = "被引用的楼层",
                text = "回复内容",
            ),
        )

        val restored = store.replyDraft(177_093)
        assertEquals("Rune", restored?.quoteAuthor)
        assertEquals("被引用的楼层", restored?.quoteText)
        assertEquals("回复内容", restored?.text)

        store.deleteReplyDraft(177_093)
        assertNull(store.replyDraft(177_093))
        assertEquals(0, store.draftCount())
        // Deleting a thread with no draft is a harmless no-op.
        store.deleteReplyDraft(177_093)
        store.deleteNewThreadDraft(999)
        assertEquals(0, store.draftCount())
    }

    @Test
    fun `drafts beyond the cap are evicted oldest first across both composers`() = runBlocking {
        val cap = ForumDraftRepositoryImpl.MAX_DRAFTS
        // 12 new-thread drafts, then 12 reply drafts: 24 entries for a 20-entry store.
        repeat(12) { index ->
            clock = 1_000L + index
            store.saveNewThreadDraft(ForumNewThreadDraft(fid = 100 + index, subject = "board $index"))
        }
        repeat(12) { index ->
            clock = 2_000L + index
            store.saveReplyDraft(ForumReplyDraft(tid = 7_000 + index, text = "reply $index"))
        }

        assertEquals(cap, store.draftCount())
        // The four oldest new-thread drafts are gone...
        (0 until 4).forEach { index ->
            assertNull("fid ${100 + index} should have been evicted", store.newThreadDraft(100 + index))
        }
        // ...the newer ones and every reply survive.
        (4 until 12).forEach { index ->
            assertNotNull("fid ${100 + index} should survive", store.newThreadDraft(100 + index))
        }
        (0 until 12).forEach { index ->
            assertNotNull("tid ${7_000 + index} should survive", store.replyDraft(7_000 + index))
        }
        // Re-saving an evicted board brings it back and evicts the current oldest entry.
        clock = 9_000L
        store.saveNewThreadDraft(ForumNewThreadDraft(fid = 100, subject = "back again"))
        assertNotNull(store.newThreadDraft(100))
        assertEquals(cap, store.draftCount())
    }

    @Test
    fun `a corrupted document degrades to an empty store instead of crashing`() = runBlocking {
        store.saveNewThreadDraft(ForumNewThreadDraft(fid = 38, subject = "kept?"))
        // Simulate a partial write / schema damage from a future version.
        dataStore.edit { it[stringPreferencesKey("forum_drafts_v1")] = "{not json" }

        assertNull(store.newThreadDraft(38))
        assertEquals(0, store.draftCount())
        // And the store recovers: a new save overwrites the garbage.
        store.saveNewThreadDraft(ForumNewThreadDraft(fid = 38, subject = "fresh"))
        assertEquals("fresh", store.newThreadDraft(38)?.subject)
        assertTrue(store.draftCount() == 1)
    }
}

/**
 * Minimal in-memory [DataStore] of [Preferences]: `edit` goes through [updateData] exactly like the
 * production DataStore, only without the Windows-hostile temp-file rename (see the class KDoc).
 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
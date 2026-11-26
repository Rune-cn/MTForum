// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.rune.mt.forum.domain.forum.model.ForumDraftIndex
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumReplyDraft
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * [ForumDraftRepository] backed by its own Preferences DataStore (`files/datastore/forum_drafts`)
 * so drafts survive process death and app restarts without mixing with the settings schema.
 *
 * The whole draft index is one JSON string under [KEY]: a save is a single atomic `edit`, and the
 * total-length cap can be applied across both composers. The cap is [MAX_DRAFTS]; when it is
 * exceeded the drafts with the oldest `savedAt` are evicted, so recently touched boards/threads
 * survive. One draft per `fid`/`tid` is kept by replacing same-context entries in place.
 *
 * A corrupted document degrades to "no drafts" instead of crashing the composer.
 */
class ForumDraftRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    /** Injectable clock for deterministic eviction tests; overridden by [saveNewThreadDraft]. */
    private val now: () -> Long = System::currentTimeMillis,
) : ForumDraftRepository {

    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = withContext(Dispatchers.IO) {
        readIndex().newThreads.firstOrNull { it.fid == fid }
    }

    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) = withContext(Dispatchers.IO) {
        val stamped = draft.copy(savedAt = now())
        val index = readIndex().let { current ->
            current.copy(newThreads = current.newThreads.filterNot { it.fid == stamped.fid } + stamped)
        }
        writeIndex(cap(index))
    }

    override suspend fun deleteNewThreadDraft(fid: Int) = withContext(Dispatchers.IO) {
        val index = readIndex()
        if (index.newThreads.none { it.fid == fid }) return@withContext
        writeIndex(index.copy(newThreads = index.newThreads.filterNot { it.fid == fid }))
    }

    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = withContext(Dispatchers.IO) {
        readIndex().replies.firstOrNull { it.tid == tid }
    }

    override suspend fun saveReplyDraft(draft: ForumReplyDraft) = withContext(Dispatchers.IO) {
        val stamped = draft.copy(savedAt = now())
        val index = readIndex().let { current ->
            current.copy(replies = current.replies.filterNot { it.tid == stamped.tid } + stamped)
        }
        writeIndex(cap(index))
    }

    override suspend fun deleteReplyDraft(tid: Int) = withContext(Dispatchers.IO) {
        val index = readIndex()
        if (index.replies.none { it.tid == tid }) return@withContext
        writeIndex(index.copy(replies = index.replies.filterNot { it.tid == tid }))
    }

    override suspend fun draftCount(): Int = withContext(Dispatchers.IO) { readIndex().size }

    private suspend fun readIndex(): ForumDraftIndex {
        val raw = dataStore.data.first()[KEY] ?: return ForumDraftIndex()
        return runCatching { json.decodeFromString(ForumDraftIndex.serializer(), raw) }
            .onFailure { Timber.tag(TAG).w(it, "draft store unreadable; treating as empty") }
            .getOrDefault(ForumDraftIndex())
    }

    private suspend fun writeIndex(index: ForumDraftIndex) {
        val encoded = json.encodeToString(ForumDraftIndex.serializer(), index)
        dataStore.edit { it[KEY] = encoded }
    }

    /**
     * Evicts the oldest drafts once the store holds more than [MAX_DRAFTS] entries.
     *
     * Both composers share the cap: a user drafting in many boards and threads keeps their 20 most
     * recent drafts, no more. `savedAt` is stamped by this repository on every save, so the order
     * is real usage order and not caller-controlled.
     */
    private fun cap(index: ForumDraftIndex): ForumDraftIndex {
        if (index.size <= MAX_DRAFTS) return index
        val entries = index.newThreads.map { entry ->
            Triple(entry.savedAt, entry, null as ForumReplyDraft?)
        } + index.replies.map { entry ->
            Triple(entry.savedAt, null as ForumNewThreadDraft?, entry)
        }
        val kept = entries.sortedByDescending { it.first }.take(MAX_DRAFTS)
        return ForumDraftIndex(
            newThreads = kept.mapNotNull { it.second },
            replies = kept.mapNotNull { it.third },
        )
    }

    companion object {
        private const val TAG = "ForumDraftRepo"

        /** Maximum drafts kept on the device across both composers; oldest are evicted first. */
        const val MAX_DRAFTS = 20

        private val KEY = stringPreferencesKey("forum_drafts_v1")
    }
}
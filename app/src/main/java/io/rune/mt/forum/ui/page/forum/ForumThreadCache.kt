// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import io.rune.mt.forum.domain.forum.model.ForumPost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 帖子缓存 — the device-local copy of a thread the user already opened.
 *
 * Scope is deliberately small (the brief's "最小可用"): the **text and floors** of page 1 are
 * written to `filesDir/thread_cache/<tid>.json` after a successful load, and read back only when
 * the same thread fails to load. The point is "the thread I read five minutes ago still opens on
 * the subway", not a second content pipeline:
 *
 *  - images are **not** cached (Coil has its own disk cache and the forum's images are
 *    cookie-gated; copying the HTML keeps the picture URLs and they simply fail offline);
 *  - only page 1 is written (the OP + the first page of floors), so a 40-page thread cannot fill
 *    the directory;
 *  - the whole feature has one switch ([ThreadCacheIndex.enabled], default **on**) that both the
 *    read and the write path honour, so turning it off is immediate and reversible.
 *
 * Eviction is LRU with a hard cap of [MAX_CACHED_THREADS] documents: the index keeps the entries
 * newest-first, a write moves its own tid to the front and a read touches it, and anything pushed
 * off the end has its file deleted in the same step. `index.json` also holds the switch, so there
 * is exactly one document to decode for the page's "占用 / 条目数" readout.
 *
 * A corrupted document is never fatal: an unreadable thread file is treated as "not cached" (and
 * deleted), an unreadable index falls back to "enabled, no entries". The thread page's own failure
 * path stays in charge of what the user sees.
 */
@Serializable
data class CachedThreadPost(
    val pid: Long,
    val tid: Int = 0,
    val authorName: String = "",
    val authorUid: Int = 0,
    val authorAvatarUrl: String? = null,
    val authorLevel: String? = null,
    val timeText: String? = null,
    val location: String? = null,
    val contentHtml: String = "",
    val floor: Int? = null,
    val isOp: Boolean = false,
)

/** One cached thread: what the thread page needs to render page 1 again. */
@Serializable
data class CachedThread(
    val tid: Int,
    val title: String = "",
    val forumName: String? = null,
    /** Wall-clock time of the write; the banner shows it as "来自缓存 · 时间". */
    val savedAt: Long = 0L,
    val posts: List<CachedThreadPost> = emptyList(),
)

/** [CachedThread] mapped back to the domain model the thread page already renders. */
data class CachedThreadSnapshot(
    val tid: Int,
    val title: String,
    val forumName: String?,
    val savedAt: Long,
    val posts: List<ForumPost>,
)

/** One row of the LRU index, newest first. */
@Serializable
data class ThreadCacheIndexEntry(
    val tid: Int,
    val title: String = "",
    val savedAt: Long = 0L,
    val bytes: Long = 0L,
)

/** The cache's own document: the switch plus the LRU order. */
@Serializable
data class ThreadCacheIndex(
    /** Default on, as the brief asks; the switch lives here so there is one document to read. */
    val enabled: Boolean = true,
    val entries: List<ThreadCacheIndexEntry> = emptyList(),
)

/** What 设置 → 插件 → 帖子缓存 shows: the switch, the entry count and the on-disk size. */
data class ThreadCacheStats(
    val enabled: Boolean = true,
    val count: Int = 0,
    val bytes: Long = 0L,
)

/**
 * The cache's storage seam.
 *
 * The thread page's ViewModel writes and falls back to it; the 帖子缓存 page reads and clears it.
 * A Noop default keeps the ViewModel constructible in tests and previews without a Context.
 */
interface ForumThreadCacheRepository {

    /** Current switch / count / size; never throws (a broken store reads as empty). */
    suspend fun stats(): ThreadCacheStats

    /** Flips the cache on/off. Off means no write **and** no read from now on. */
    suspend fun setEnabled(enabled: Boolean)

    /** Deletes every cached document; the switch is left as it is. */
    suspend fun clear()

    /** Stores page 1 of [tid]; a no-op while the cache is off or the list is empty. */
    suspend fun write(tid: Int, title: String, forumName: String?, posts: List<ForumPost>)

    /** Reads back a cached thread (and touches its LRU position), or `null` when absent/off. */
    suspend fun read(tid: Int): CachedThreadSnapshot?

    companion object {
        /** Hard cap of cached threads, evicted LRU. */
        const val MAX_CACHED_THREADS = 200
    }
}

/** The "off" implementation: used by previews/tests and whenever the store is unavailable. */
object NoopForumThreadCacheRepository : ForumThreadCacheRepository {

    override suspend fun stats(): ThreadCacheStats = ThreadCacheStats(enabled = false)

    override suspend fun setEnabled(enabled: Boolean) = Unit

    override suspend fun clear() = Unit

    override suspend fun write(tid: Int, title: String, forumName: String?, posts: List<ForumPost>) =
        Unit

    override suspend fun read(tid: Int): CachedThreadSnapshot? = null
}

/**
 * Files-backed [ForumThreadCacheRepository].
 *
 * @param root `filesDir/thread_cache`; created on first write.
 * @param limit kept as a parameter so a test can pin eviction with three entries instead of 200.
 * @param clock injected so `savedAt` assertions do not depend on wall-clock time.
 */
class ForumThreadCacheRepositoryImpl(
    private val root: File,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limit: Int = ForumThreadCacheRepository.MAX_CACHED_THREADS,
) : ForumThreadCacheRepository {

    /** One process, one writer: the index is a read-modify-write document. */
    private val lock = Any()

    private val indexFile: File get() = File(root, INDEX_FILE_NAME)

    override suspend fun stats(): ThreadCacheStats = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val index = readIndex()
            ThreadCacheStats(
                enabled = index.enabled,
                count = index.entries.count { fileFor(it.tid).isFile },
                bytes = index.entries.sumOf { entry ->
                    fileFor(entry.tid).takeIf { it.isFile }?.length() ?: 0L
                },
            )
        }
    }

    override suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val index = readIndex()
            if (index.enabled != enabled) writeIndex(index.copy(enabled = enabled))
        }
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val index = readIndex()
            // Only our own documents are deleted: the directory is ours, but a future format could
            // add a file the cache does not own.
            root.listFiles()
                ?.filter { it.isFile && it.name != INDEX_FILE_NAME && it.name.endsWith(".json") }
                ?.forEach { it.delete() }
            writeIndex(index.copy(entries = emptyList()))
        }
        Unit
    }

    override suspend fun write(
        tid: Int,
        title: String,
        forumName: String?,
        posts: List<ForumPost>,
    ) = withContext(Dispatchers.IO) {
        if (tid <= 0 || posts.isEmpty()) return@withContext
        synchronized(lock) {
            val index = readIndex()
            // Off = no write. The switch is checked on the same document the read path uses.
            if (!index.enabled) return@synchronized
            val document = CachedThread(
                tid = tid,
                title = title.trim(),
                forumName = forumName?.trim()?.takeIf { it.isNotEmpty() },
                savedAt = clock(),
                posts = posts.map { it.toCached() },
            )
            val text = runCatching {
                json.encodeToString(CachedThread.serializer(), document)
            }.getOrElse { error ->
                Timber.tag(TAG).w(error, "thread %d not cacheable", tid)
                return@synchronized
            }
            root.mkdirs()
            runCatching { fileFor(tid).writeText(text) }.onFailure { error ->
                Timber.tag(TAG).w(error, "thread %d cache write failed", tid)
                return@synchronized
            }
            val entry = ThreadCacheIndexEntry(
                tid = tid,
                title = document.title,
                savedAt = document.savedAt,
                bytes = text.toByteArray().size.toLong(),
            )
            val previous = index.entries
            val kept = (listOf(entry) + previous.filterNot { it.tid == tid })
                .distinctBy { it.tid }
                .take(limit)
            // Anything that fell off the end loses its document in the same step, so the directory
            // can never grow past the cap.
            previous.filter { old -> kept.none { it.tid == old.tid } }
                .forEach { dropped -> fileFor(dropped.tid).delete() }
            writeIndex(index.copy(entries = kept))
        }
        Unit
    }

    override suspend fun read(tid: Int): CachedThreadSnapshot? = withContext(Dispatchers.IO) {
        if (tid <= 0) return@withContext null
        synchronized(lock) {
            val index = readIndex()
            if (!index.enabled) return@synchronized null
            val file = fileFor(tid)
            if (!file.isFile) return@synchronized null
            val document = runCatching {
                json.decodeFromString(CachedThread.serializer(), file.readText())
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "thread %d cache unreadable; dropping it", tid)
                file.delete()
            }.getOrNull() ?: return@synchronized null
            if (document.posts.isEmpty()) return@synchronized null
            // LRU touch: a thread the user keeps opening must not be the next one evicted.
            val touched = index.entries.firstOrNull { it.tid == tid }
            if (touched != null && index.entries.firstOrNull()?.tid != tid) {
                writeIndex(
                    index.copy(entries = listOf(touched) + index.entries.filterNot { it.tid == tid }),
                )
            }
            CachedThreadSnapshot(
                tid = document.tid.takeIf { it > 0 } ?: tid,
                title = document.title,
                forumName = document.forumName,
                savedAt = document.savedAt,
                posts = document.posts.map { it.toPost() },
            )
        }
    }

    private fun fileFor(tid: Int): File = File(root, "$tid.json")

    private fun readIndex(): ThreadCacheIndex {
        val file = indexFile
        if (!file.isFile) return ThreadCacheIndex()
        return runCatching {
            json.decodeFromString(ThreadCacheIndex.serializer(), file.readText())
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "thread cache index unreadable; resetting it")
        }.getOrDefault(ThreadCacheIndex())
    }

    private fun writeIndex(index: ThreadCacheIndex) {
        runCatching {
            root.mkdirs()
            indexFile.writeText(json.encodeToString(ThreadCacheIndex.serializer(), index))
        }.onFailure { error -> Timber.tag(TAG).w(error, "thread cache index write failed") }
    }

    private companion object {
        const val TAG = "ThreadCache"
        const val INDEX_FILE_NAME = "index.json"
    }
}

/** Domain → stored shape (the JSON is a local schema; the domain model stays non-serializable). */
private fun ForumPost.toCached(): CachedThreadPost = CachedThreadPost(
    pid = pid,
    tid = tid,
    authorName = authorName,
    authorUid = authorUid,
    authorAvatarUrl = authorAvatarUrl,
    authorLevel = authorLevel,
    timeText = timeText,
    location = location,
    contentHtml = contentHtml,
    floor = floor,
    isOp = isOp,
)

/** Stored shape → domain, so the page renders cached floors through the same cards. */
private fun CachedThreadPost.toPost(): ForumPost = ForumPost(
    pid = pid,
    tid = tid,
    authorName = authorName,
    authorUid = authorUid,
    authorAvatarUrl = authorAvatarUrl,
    authorLevel = authorLevel,
    timeText = timeText,
    location = location,
    contentHtml = contentHtml,
    floor = floor,
    isOp = isOp,
)

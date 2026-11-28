// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.rune.mt.forum.data.forum.repository.CommentFilterRepositoryImpl
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleType
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleError
import io.rune.mt.forum.domain.forum.model.InvalidCommentFilterRuleException
import io.rune.mt.forum.domain.forum.model.MAX_COMMENT_FILTER_PATTERN_LENGTH
import io.rune.mt.forum.domain.forum.model.MAX_COMMENT_FILTER_RULES
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The comment-filter rule store: add/remove/persist, the rule cap, save-time rejection of a bad
 * regex, and graceful degradation of a damaged document.
 *
 * Drives the production [CommentFilterRepositoryImpl] over an in-memory `DataStore` (a real
 * file-backed one cannot replace its file on Windows; see `ForumDraftStoreTest`).
 */
class CommentFilterStoreTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: CommentFilterRepositoryImpl

    @Before
    fun setUp() {
        dataStore = FilterMemoryDataStore()
        repository = CommentFilterRepositoryImpl(
            dataStore = dataStore,
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @Test
    fun `add persists a keyword rule`() = runBlocking {
        repository.addRule("广告", CommentFilterRuleType.KEYWORD)

        val settings = repository.settings.first()
        assertEquals(1, settings.rules.size)
        assertEquals("广告", settings.rules.single().pattern)
        assertEquals(CommentFilterRuleType.KEYWORD, settings.rules.single().type)
        assertTrue(settings.rules.single().enabled)
    }

    @Test
    fun `rules survive a fresh repository over the same store`() = runBlocking {
        repository.addRule("spam", CommentFilterRuleType.KEYWORD)
        val reopened = CommentFilterRepositoryImpl(
            dataStore = dataStore,
            json = Json { ignoreUnknownKeys = true },
        )

        assertEquals(listOf("spam"), reopened.settings.first().rules.map { it.pattern })
    }

    @Test
    fun `delete removes only the targeted rule`() = runBlocking {
        val keep = repository.addRule("keep", CommentFilterRuleType.KEYWORD)
        val gone = repository.addRule("gone", CommentFilterRuleType.KEYWORD)

        repository.deleteRule(gone.id)

        assertEquals(listOf(keep.id), repository.settings.first().rules.map { it.id })
    }

    @Test
    fun `update replaces the pattern and type in place`() = runBlocking {
        val rule = repository.addRule("before", CommentFilterRuleType.KEYWORD)

        repository.updateRule(rule.id, "^after$", CommentFilterRuleType.REGEX)

        val stored = repository.settings.first().rules.single()
        assertEquals(rule.id, stored.id)
        assertEquals("^after$", stored.pattern)
        assertEquals(CommentFilterRuleType.REGEX, stored.type)
    }

    @Test
    fun `the rule list is capped`() = runBlocking {
        repeat(MAX_COMMENT_FILTER_RULES) { index ->
            repository.addRule("keyword$index", CommentFilterRuleType.KEYWORD)
        }
        val error = runCatching {
            repository.addRule("one-too-many", CommentFilterRuleType.KEYWORD)
        }.exceptionOrNull()

        assertTrue(error is InvalidCommentFilterRuleException)
        assertEquals(CommentFilterRuleError.TOO_MANY_RULES, (error as InvalidCommentFilterRuleException).reason)
        assertEquals(MAX_COMMENT_FILTER_RULES, repository.settings.first().rules.size)
    }

    @Test
    fun `an invalid regex is rejected at save time`() = runBlocking {
        val error = runCatching {
            repository.addRule("(", CommentFilterRuleType.REGEX)
        }.exceptionOrNull()

        assertTrue(error is InvalidCommentFilterRuleException)
        assertEquals(CommentFilterRuleError.INVALID_REGEX, (error as InvalidCommentFilterRuleException).reason)
        assertTrue(repository.settings.first().rules.isEmpty())
    }

    @Test
    fun `blank and over-long patterns are rejected at save time`() = runBlocking {
        val blank = runCatching { repository.addRule("   ", CommentFilterRuleType.KEYWORD) }.exceptionOrNull()
        val long = runCatching {
            repository.addRule("a".repeat(MAX_COMMENT_FILTER_PATTERN_LENGTH + 1), CommentFilterRuleType.KEYWORD)
        }.exceptionOrNull()

        assertEquals(CommentFilterRuleError.BLANK_PATTERN, (blank as InvalidCommentFilterRuleException).reason)
        assertEquals(CommentFilterRuleError.PATTERN_TOO_LONG, (long as InvalidCommentFilterRuleException).reason)
        assertTrue(repository.settings.first().rules.isEmpty())
    }

    @Test
    fun `master switch and filter-op persist`() = runBlocking {
        assertFalse(repository.settings.first().masterEnabled)
        assertFalse(repository.settings.first().filterOp)

        repository.setMasterEnabled(true)
        repository.setFilterOp(true)

        val settings = repository.settings.first()
        assertTrue(settings.masterEnabled)
        assertTrue(settings.filterOp)
    }

    @Test
    fun `a corrupted document degrades to empty and recovers`() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("thread_comment_filter_v1")] = "{not json" }

        assertTrue(repository.settings.first().rules.isEmpty())
        assertFalse(repository.settings.first().masterEnabled)

        repository.addRule("fresh", CommentFilterRuleType.KEYWORD)
        assertEquals(listOf("fresh"), repository.settings.first().rules.map { it.pattern })
    }

    @Test
    fun `a bad stored pattern is skipped at load, not fatal`() = runBlocking {
        // Hand-write a document mixing a valid rule with an invalid regex, an over-long pattern and
        // a blank id; the repository must keep only the valid one.
        val raw = """
            {"masterEnabled":true,"filterOp":false,"rules":[
              {"id":"good","pattern":"广告","type":"KEYWORD","enabled":true},
              {"id":"bad-regex","pattern":"(","type":"REGEX","enabled":true},
              {"id":"too-long","pattern":"${"x".repeat(MAX_COMMENT_FILTER_PATTERN_LENGTH + 1)}","type":"KEYWORD","enabled":true},
              {"id":"","pattern":"no-id","type":"KEYWORD","enabled":true}
            ]}
        """.trimIndent()
        dataStore.edit { it[stringPreferencesKey("thread_comment_filter_v1")] = raw }

        val settings = repository.settings.first()
        assertEquals(listOf("good"), settings.rules.map { it.id })
        assertTrue(settings.masterEnabled)
    }
}

/** In-memory [DataStore] of [Preferences]; a file-backed one cannot be reset on Windows. */
private class FilterMemoryDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

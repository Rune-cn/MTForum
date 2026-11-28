// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.rune.mt.forum.domain.forum.model.CommentFilterRule
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleError
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleType
import io.rune.mt.forum.domain.forum.model.CommentFilterSettings
import io.rune.mt.forum.domain.forum.model.InvalidCommentFilterRuleException
import io.rune.mt.forum.domain.forum.model.MAX_COMMENT_FILTER_PATTERN_LENGTH
import io.rune.mt.forum.domain.forum.model.MAX_COMMENT_FILTER_RULES
import io.rune.mt.forum.domain.forum.repository.CommentFilterRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * [CommentFilterRepository] backed by the **app settings** DataStore (`thread_comment_filter_v1`).
 *
 * The whole configuration is one JSON string: a save is a single atomic `edit`, and the settings
 * screen, the store and the thread page all observe the same DataStore flow. Modelled on
 * [ForumDraftRepositoryImpl]'s JSON-in-DataStore pattern.
 *
 * A document that does not decode (a partial write, a future schema) degrades to the default empty
 * configuration; individual bad rules (blank, over-long, invalid regex) are skipped at load so one
 * corrupt pattern never takes down the whole list. Validation only **rejects new writes**, it never
 * crashes a read.
 *
 * Regex rules are user-supplied and may backtrack catastrophically; see the note on
 * [MAX_COMMENT_FILTER_PATTERN_LENGTH].
 */
class CommentFilterRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) : CommentFilterRepository {

    override val settings: Flow<CommentFilterSettings> = dataStore.data.map { prefs ->
        sanitize(decode(prefs[KEY]))
    }

    override suspend fun setMasterEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        mutate { it.copy(masterEnabled = enabled) }
    }

    override suspend fun setFilterOp(enabled: Boolean) = withContext(Dispatchers.IO) {
        mutate { it.copy(filterOp = enabled) }
    }

    override suspend fun addRule(pattern: String, type: CommentFilterRuleType): CommentFilterRule =
        withContext(Dispatchers.IO) {
            val trimmed = validate(pattern, type)
            var created = CommentFilterRule(id = "", pattern = trimmed, type = type)
            mutate { current ->
                if (current.rules.size >= MAX_COMMENT_FILTER_RULES) {
                    throw InvalidCommentFilterRuleException(CommentFilterRuleError.TOO_MANY_RULES)
                }
                created = CommentFilterRule(id = UUID.randomUUID().toString(), pattern = trimmed, type = type)
                current.copy(rules = current.rules + created)
            }
            created
        }

    override suspend fun updateRule(id: String, pattern: String, type: CommentFilterRuleType) =
        withContext(Dispatchers.IO) {
            val trimmed = validate(pattern, type)
            mutate { current ->
                if (current.rules.none { it.id == id }) return@mutate current
                current.copy(
                    rules = current.rules.map {
                        if (it.id == id) it.copy(pattern = trimmed, type = type) else it
                    },
                )
            }
        }

    override suspend fun deleteRule(id: String) = withContext(Dispatchers.IO) {
        mutate { current -> current.copy(rules = current.rules.filterNot { it.id == id }) }
    }

    override suspend fun clearRules() = withContext(Dispatchers.IO) {
        mutate { it.copy(rules = emptyList()) }
    }

    /** Atomic read-modify-write of the whole document. */
    private suspend fun mutate(transform: (CommentFilterSettings) -> CommentFilterSettings) {
        dataStore.edit { prefs ->
            val current = sanitize(decode(prefs[KEY]))
            prefs[KEY] = json.encodeToString(CommentFilterSettings.serializer(), transform(current))
        }
    }

    /** Returns the trimmed pattern, or throws when it must be rejected at save time. */
    private fun validate(pattern: String, type: CommentFilterRuleType): String {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) throw InvalidCommentFilterRuleException(CommentFilterRuleError.BLANK_PATTERN)
        if (trimmed.length > MAX_COMMENT_FILTER_PATTERN_LENGTH) {
            throw InvalidCommentFilterRuleException(CommentFilterRuleError.PATTERN_TOO_LONG)
        }
        if (type == CommentFilterRuleType.REGEX) {
            runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }
                .getOrElse { throw InvalidCommentFilterRuleException(CommentFilterRuleError.INVALID_REGEX) }
        }
        return trimmed
    }

    private fun decode(raw: String?): CommentFilterSettings {
        if (raw.isNullOrBlank()) return CommentFilterSettings()
        return runCatching { json.decodeFromString(CommentFilterSettings.serializer(), raw) }
            .onFailure { Timber.tag(TAG).w(it, "comment filter unreadable; treating as empty") }
            .getOrDefault(CommentFilterSettings())
    }

    /**
     * Drops rules that could never be applied (blank/over-long pattern, invalid regex) and caps the
     * list, so a hand-edited or half-migrated document still yields a usable list.
     */
    private fun sanitize(settings: CommentFilterSettings): CommentFilterSettings {
        val seen = HashSet<String>()
        val valid = settings.rules.asSequence()
            .filter { it.pattern.isNotBlank() }
            .filter { it.pattern.length <= MAX_COMMENT_FILTER_PATTERN_LENGTH }
            .filter {
                it.type != CommentFilterRuleType.REGEX ||
                    runCatching { Regex(it.pattern, RegexOption.IGNORE_CASE) }.isSuccess
            }
            .filter { it.id.isNotBlank() && seen.add(it.id) }
            .take(MAX_COMMENT_FILTER_RULES)
            .toList()
        return settings.copy(rules = valid)
    }

    private companion object {
        const val TAG = "CommentFilterRepo"
        const val KEY_NAME = "thread_comment_filter_v1"
        val KEY = stringPreferencesKey(KEY_NAME)
    }
}

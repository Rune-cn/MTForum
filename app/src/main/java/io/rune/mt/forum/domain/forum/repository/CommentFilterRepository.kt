// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

import io.rune.mt.forum.domain.forum.model.CommentFilterRule
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleType
import io.rune.mt.forum.domain.forum.model.CommentFilterSettings
import kotlinx.coroutines.flow.Flow

/**
 * Device-local store for the comment (楼层) filter rules.
 *
 * Backed by the **existing app settings DataStore** as one JSON document, modelled on
 * `ForumDraftRepositoryImpl`'s JSON-in-DataStore approach. The whole configuration is one
 * `Preferences` string, so a save is a single atomic `edit` and the rules can never be half-written.
 *
 * Validation happens **at save time** ([addRule]/[updateRule]) and surfaces as
 * [io.rune.mt.forum.domain.forum.model.InvalidCommentFilterRuleException]; a bad pattern already on
 * disk is tolerated at load (the implementation skips it instead of failing the flow). Nothing here
 * talks to the site — the filter is purely local.
 */
interface CommentFilterRepository {

    /** Current configuration; emits again after every setter. */
    val settings: Flow<CommentFilterSettings>

    suspend fun setMasterEnabled(enabled: Boolean)

    /** "也过滤楼主": when true the original post is filtered too (default false). */
    suspend fun setFilterOp(enabled: Boolean)

    /**
     * Adds a rule after validating it. Throws
     * [io.rune.mt.forum.domain.forum.model.InvalidCommentFilterRuleException] for a blank/oversized
     * pattern, an invalid regex, or when the store is full.
     */
    suspend fun addRule(pattern: String, type: CommentFilterRuleType): CommentFilterRule

    /** Replaces the pattern/type of [id], validating the new pattern the same way as [addRule]. */
    suspend fun updateRule(id: String, pattern: String, type: CommentFilterRuleType)

    /** Removes the rule with [id]; a no-op when it does not exist. */
    suspend fun deleteRule(id: String)

    /** Removes every rule (the master switch and 也过滤楼主 are kept). */
    suspend fun clearRules()
}

/** A repository that stores nothing, used as the default for callers that do not care. */
object NoopCommentFilterRepository : CommentFilterRepository {
    override val settings: Flow<CommentFilterSettings> = kotlinx.coroutines.flow.flowOf(CommentFilterSettings())
    override suspend fun setMasterEnabled(enabled: Boolean) = Unit
    override suspend fun setFilterOp(enabled: Boolean) = Unit
    override suspend fun addRule(pattern: String, type: CommentFilterRuleType): CommentFilterRule =
        CommentFilterRule(id = "", pattern = pattern, type = type)

    override suspend fun updateRule(id: String, pattern: String, type: CommentFilterRuleType) = Unit
    override suspend fun deleteRule(id: String) = Unit
    override suspend fun clearRules() = Unit
}

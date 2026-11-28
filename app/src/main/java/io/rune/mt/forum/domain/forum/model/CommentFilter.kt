// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

import kotlinx.serialization.Serializable

/**
 * One user rule that hides comments (楼层) from a thread.
 *
 * A rule is either a plain [CommentFilterRuleType.KEYWORD] (case-insensitive substring) or a
 * [CommentFilterRuleType.REGEX] (Kotlin/Java regex, also case-insensitive). Rules are stored as a
 * JSON list in the app settings DataStore; see [io.rune.mt.forum.domain.forum.repository.CommentFilterRepository].
 *
 * [id] is a stable per-rule identifier (a random UUID) so editing/deleting a row never depends on
 * the pattern text — two rules may legitimately share a pattern with different types.
 */
@Serializable
data class CommentFilterRule(
    val id: String,
    val pattern: String,
    val type: CommentFilterRuleType,
    val enabled: Boolean = true,
)

/** How [CommentFilterRule.pattern] is interpreted. */
@Serializable
enum class CommentFilterRuleType {
    /** Case-insensitive substring match. */
    KEYWORD,

    /** Case-insensitive regular expression match. */
    REGEX,
}

/**
 * The complete comment-filter configuration.
 *
 * [masterEnabled] is the global switch: when false no comment is ever hidden (the stored rules are
 * kept, so toggling it back on restores them). [filterOp] is the opt-in "也过滤楼主": by default the
 * original post is always kept on screen — the feature is about comments, not the thread itself.
 */
@Serializable
data class CommentFilterSettings(
    val masterEnabled: Boolean = false,
    val filterOp: Boolean = false,
    val rules: List<CommentFilterRule> = emptyList(),
)

/** Why a rule could not be stored; the settings screen turns it into a localized message. */
enum class CommentFilterRuleError {
    /** The pattern is empty after trimming. */
    BLANK_PATTERN,

    /** The pattern is longer than [MAX_COMMENT_FILTER_PATTERN_LENGTH]. */
    PATTERN_TOO_LONG,

    /** A regex rule did not compile. */
    INVALID_REGEX,

    /** The store already holds [MAX_COMMENT_FILTER_RULES] rules. */
    TOO_MANY_RULES,
}

/** Thrown by the repository when a rule is rejected at **save time** (never at load time). */
class InvalidCommentFilterRuleException(
    val reason: CommentFilterRuleError,
) : Exception("Invalid comment filter rule: ${reason.name}")

/**
 * Maximum number of rules kept on the device. Bounded so the settings list and the per-page match
 * loop stay cheap; adding beyond this is rejected with [CommentFilterRuleError.TOO_MANY_RULES].
 */
const val MAX_COMMENT_FILTER_RULES = 50

/**
 * Maximum length of a single pattern, in characters.
 *
 * 200 characters is long enough for any realistic keyword or regex the users write, and short
 * enough to bound the worst-case work. Regex rules are still a **catastrophic-backtracking** risk
 * (a pattern like `(a+)+$` can explode); the matcher compiles each rule once and is only ever run
 * against one floor's plain text at a time, so the practical blast radius is a slow thread page,
 * not a hang of the whole app. The limit is documented in `docs/HANDOVER_CN.md`; do not raise it
 * without also revisiting that note.
 */
const val MAX_COMMENT_FILTER_PATTERN_LENGTH = 200

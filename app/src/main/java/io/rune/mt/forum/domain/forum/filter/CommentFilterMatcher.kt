// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.filter

import io.rune.mt.forum.domain.forum.model.CommentFilterRule
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleType
import io.rune.mt.forum.domain.forum.model.MAX_COMMENT_FILTER_PATTERN_LENGTH

/**
 * Matches one floor's plain text against a compiled set of comment-filter rules.
 *
 * **Compile once, match many.** A matcher is built from the stored rules whenever the rule set
 * changes ([compile]); every [isBlocked] call then only runs the already-compiled `Regex` objects
 * and lower-cased keyword strings — no per-floor compilation. The thread ViewModel holds one
 * matcher per settings revision and reuses it for every row, which is what keeps the UI list path
 * cheap (see `ForumViewModel.threadDisplay`).
 *
 * Invalid stored rules are **skipped**, never thrown: a corrupt pattern must not take the whole
 * list down. The repository also drops them at load; this is the second line of defence for
 * callers/tests that build a matcher directly.
 *
 * Regex is a known catastrophic-backtracking risk for hostile patterns. Patterns are capped at
 * [MAX_COMMENT_FILTER_PATTERN_LENGTH] characters and matched against one floor at a time, so the
 * worst case is a slow thread page rather than an ANR of the whole process.
 */
class CommentFilterMatcher private constructor(
    private val compiled: List<CompiledRule>,
) {

    /** Number of usable rules (invalid/disabled/over-long rules are not counted). */
    val ruleCount: Int get() = compiled.size

    /** True when at least one rule could hide [text]. */
    fun isBlocked(text: String): Boolean {
        if (text.isEmpty() || compiled.isEmpty()) return false
        val lowered = text.lowercase()
        return compiled.any { rule ->
            when (rule) {
                is CompiledRule.Keyword -> lowered.contains(rule.needle)
                is CompiledRule.RegexRule -> rule.regex.containsMatchIn(text)
            }
        }
    }

    private sealed interface CompiledRule {
        data class Keyword(val needle: String) : CompiledRule
        data class RegexRule(val regex: Regex) : CompiledRule
    }

    companion object {
        /** A matcher that blocks nothing; convenient when the master switch is off. */
        val EMPTY = CommentFilterMatcher(emptyList())

        /**
         * Compiles [rules] once. Disabled rules, blank patterns, over-long patterns and regexes
         * that fail to compile are silently skipped so a damaged store cannot crash the list.
         */
        fun compile(rules: List<CommentFilterRule>): CommentFilterMatcher {
            val compiled = ArrayList<CompiledRule>(rules.size)
            for (rule in rules) {
                if (!rule.enabled) continue
                val pattern = rule.pattern
                if (pattern.isBlank()) continue
                if (pattern.length > MAX_COMMENT_FILTER_PATTERN_LENGTH) continue
                when (rule.type) {
                    CommentFilterRuleType.KEYWORD ->
                        compiled += CompiledRule.Keyword(pattern.lowercase())

                    CommentFilterRuleType.REGEX -> {
                        val regex = runCatching {
                            Regex(pattern, RegexOption.IGNORE_CASE)
                        }.getOrNull() ?: continue
                        compiled += CompiledRule.RegexRule(regex)
                    }
                }
            }
            return CommentFilterMatcher(compiled)
        }
    }
}

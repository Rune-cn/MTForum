// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.filter.CommentFilterMatcher
import io.rune.mt.forum.domain.forum.model.CommentFilterRule
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleType
import io.rune.mt.forum.domain.forum.model.MAX_COMMENT_FILTER_PATTERN_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher: keyword and regex semantics, case-insensitivity, skipping an invalid pattern and
 * dropping an over-long one. Compilation happens once in [CommentFilterMatcher.compile]; these
 * tests only exercise matching.
 */
class CommentFilterMatcherTest {

    private fun keyword(pattern: String, enabled: Boolean = true) =
        CommentFilterRule(id = pattern, pattern = pattern, type = CommentFilterRuleType.KEYWORD, enabled = enabled)

    private fun regex(pattern: String, enabled: Boolean = true) =
        CommentFilterRule(id = pattern, pattern = pattern, type = CommentFilterRuleType.REGEX, enabled = enabled)

    @Test
    fun `keyword blocks its substring case-insensitively`() {
        val matcher = CommentFilterMatcher.compile(listOf(keyword("Spam")))

        assertTrue(matcher.isBlocked("this is SPAM here"))
        assertTrue(matcher.isBlocked("spam"))
        assertFalse(matcher.isBlocked("not it"))
    }

    @Test
    fun `regex blocks a matching comment`() {
        val matcher = CommentFilterMatcher.compile(listOf(regex("^\\d{5,}$")))

        assertTrue(matcher.isBlocked("123456"))
        assertFalse(matcher.isBlocked("12345 words"))
    }

    @Test
    fun `regex is case-insensitive too`() {
        val matcher = CommentFilterMatcher.compile(listOf(regex("free\\s+gift")))

        assertTrue(matcher.isBlocked("FREE   GIFT"))
    }

    @Test
    fun `an invalid stored pattern is skipped instead of throwing`() {
        val matcher = CommentFilterMatcher.compile(listOf(regex("(")))

        assertEquals(0, matcher.ruleCount)
        assertFalse(matcher.isBlocked("anything"))
    }

    @Test
    fun `a valid rule still works beside an invalid one`() {
        val matcher = CommentFilterMatcher.compile(listOf(regex("("), keyword("badword")))

        assertEquals(1, matcher.ruleCount)
        assertTrue(matcher.isBlocked("a BADWORD appears"))
    }

    @Test
    fun `an over-long pattern is capped away`() {
        val matcher = CommentFilterMatcher.compile(
            listOf(keyword("x".repeat(MAX_COMMENT_FILTER_PATTERN_LENGTH + 1))),
        )

        assertEquals(0, matcher.ruleCount)
        assertFalse(matcher.isBlocked("x".repeat(MAX_COMMENT_FILTER_PATTERN_LENGTH + 1)))
    }

    @Test
    fun `a disabled rule never blocks`() {
        val matcher = CommentFilterMatcher.compile(listOf(keyword("spam", enabled = false)))

        assertEquals(0, matcher.ruleCount)
        assertFalse(matcher.isBlocked("spam"))
    }

    @Test
    fun `an empty matcher blocks nothing`() {
        assertFalse(CommentFilterMatcher.EMPTY.isBlocked("anything at all"))
    }
}

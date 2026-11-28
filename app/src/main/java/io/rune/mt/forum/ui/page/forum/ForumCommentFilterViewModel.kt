// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.CommentFilterRule
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleError
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleType
import io.rune.mt.forum.domain.forum.model.InvalidCommentFilterRuleException
import io.rune.mt.forum.domain.forum.repository.CommentFilterRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state of the comment-filter settings screen. */
data class CommentFilterUiState(
    val masterEnabled: Boolean = false,
    val filterOp: Boolean = false,
    val rules: List<CommentFilterRule> = emptyList(),
)

/**
 * Backs the 评论屏蔽 settings screen.
 *
 * Rule mutations are `suspend` and return a [CommentFilterRuleError] (null on success) so the
 * editor dialog can keep itself open and show the reason a save was rejected — validation happens
 * in the repository at save time. The master switch and 也过滤楼主 are fire-and-forget.
 */
class ForumCommentFilterViewModel(
    private val repository: CommentFilterRepository,
) : ViewModel() {

    val state: StateFlow<CommentFilterUiState> = repository.settings
        .map { CommentFilterUiState(it.masterEnabled, it.filterOp, it.rules) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CommentFilterUiState())

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setMasterEnabled(enabled) }
    }

    fun setFilterOp(enabled: Boolean) {
        viewModelScope.launch { repository.setFilterOp(enabled) }
    }

    /** Saves a new rule; returns the rejection reason, or null on success. */
    suspend fun addRule(pattern: String, type: CommentFilterRuleType): CommentFilterRuleError? =
        runCatching { repository.addRule(pattern, type) }
            .fold(onSuccess = { null }, onFailure = { it.toRuleError() })

    /** Re-saves an existing rule; returns the rejection reason, or null on success. */
    suspend fun updateRule(id: String, pattern: String, type: CommentFilterRuleType): CommentFilterRuleError? =
        runCatching { repository.updateRule(id, pattern, type) }
            .fold(onSuccess = { null }, onFailure = { it.toRuleError() })

    fun deleteRule(id: String) {
        viewModelScope.launch { repository.deleteRule(id) }
    }

    private fun Throwable.toRuleError(): CommentFilterRuleError =
        (this as? InvalidCommentFilterRuleException)?.reason ?: CommentFilterRuleError.STORAGE_FAILED
}

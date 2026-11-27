// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsSharedViewModel : ViewModel() {

    private val _state = MutableStateFlow(SettingsSharedState())
    val state: StateFlow<SettingsSharedState> = _state.asStateFlow()

    fun updateLastMainPageIndex(index: Int) {
        _state.update { currentState ->
            currentState.copy(lastMainPageIndex = index)
        }
    }

    /**
     * Asks the shell to show pager [index] on its next composition.
     *
     * Used by a home deep link: the shell may currently be on 消息/设置, and the link must land on
     * the 论坛 tab rather than merely reopening the app root. The request is one-shot — the shell
     * clears it after animating ([consumeMainPageRequest]).
     */
    fun requestMainPage(index: Int) {
        _state.update { currentState ->
            currentState.copy(pendingMainPageIndex = index)
        }
    }

    /** Called by the shell once it has honoured [requestMainPage]. */
    fun consumeMainPageRequest() {
        _state.update { currentState ->
            currentState.copy(pendingMainPageIndex = null)
        }
    }
}
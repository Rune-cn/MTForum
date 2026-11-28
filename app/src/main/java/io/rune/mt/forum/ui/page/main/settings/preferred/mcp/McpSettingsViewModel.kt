// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.mcp.McpEndpoint
import io.rune.mt.forum.domain.mcp.McpServerController
import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.settings.repository.BooleanSetting
import io.rune.mt.forum.domain.settings.repository.IntSetting
import io.rune.mt.forum.domain.settings.usecase.settings.UpdateSettingUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Persists the MCP switch/port and merges the live server state for the 设置 page.
 *
 * The ViewModel never starts or stops the socket itself: it only writes the preference, and
 * `McpServerController` owns the lifecycle. That keeps the server's lifetime tied to the setting
 * rather than to whether this page is on screen.
 */
class McpSettingsViewModel(
    appSettingsRepo: AppSettingsRepository,
    controller: McpServerController,
    private val updateSetting: UpdateSettingUseCase,
) : ViewModel() {

    val state: StateFlow<McpSettingsState> = combine(
        appSettingsRepo.preferencesFlow,
        controller.state,
    ) { preferences, server ->
        McpSettingsState(
            enabled = preferences.mcpServerEnabled,
            port = preferences.mcpServerPort,
            running = server.running && server.port == preferences.mcpServerPort,
            error = server.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = McpSettingsState(),
    )

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        updateSetting(BooleanSetting.McpServerEnabled, enabled)
    }

    /** Invalid/privileged ports are dropped instead of being written and bound. */
    fun setPort(port: Int) {
        if (!McpEndpoint.isValidPort(port)) return
        viewModelScope.launch { updateSetting(IntSetting.McpServerPort, port) }
    }
}
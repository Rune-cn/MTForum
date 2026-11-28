// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.mcp

import io.rune.mt.forum.domain.mcp.McpEndpoint
import io.rune.mt.forum.domain.mcp.McpServerController
import io.rune.mt.forum.domain.mcp.McpServerState
import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the loopback MCP socket in sync with the persisted 设置 switch and port.
 *
 * One observer collects `preferencesFlow` for the whole app lifetime, so the UI never owns the
 * socket: flipping the switch (or editing the port) in 设置 reconciles the server without an app
 * restart, and the server cannot outlive its setting. A bind failure is reported through
 * [state] instead of crashing the app, and the next settings change retries.
 *
 * The socket itself is bound/unbound only on this collector's dispatcher, so two reconciles can
 * never race into two listeners on one port.
 */
class McpServerControllerImpl(
    private val settingsRepository: AppSettingsRepository,
    private val dispatcher: McpJsonRpcDispatcher,
    private val scope: CoroutineScope,
) : McpServerController {

    private val _state = MutableStateFlow(McpServerState())
    override val state: StateFlow<McpServerState> = _state.asStateFlow()

    private var observer: Job? = null
    private var server: McpHttpServer? = null

    override fun start() {
        if (observer != null) return
        observer = scope.launch {
            try {
                settingsRepository.preferencesFlow.collect { preferences ->
                    reconcile(
                        enabled = preferences.mcpServerEnabled,
                        port = McpEndpoint.normalizePort(preferences.mcpServerPort),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "MCP settings observer stopped")
            }
        }
    }

    private fun reconcile(enabled: Boolean, port: Int) {
        val current = server
        if (!enabled) {
            if (current != null) {
                current.close()
                server = null
            }
            _state.value = McpServerState(running = false, port = port, error = null)
            return
        }
        if (current != null && current.port == port) {
            if (!_state.value.running) _state.value = McpServerState(running = true, port = port, error = null)
            return
        }
        if (current != null) {
            current.close()
            server = null
        }
        try {
            val started = McpHttpServer(dispatcher = dispatcher, requestedPort = port)
            started.start()
            server = started
            _state.value = McpServerState(running = true, port = started.port, error = null)
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "MCP server failed to bind port %d", port)
            _state.value = McpServerState(
                running = false,
                port = port,
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private companion object {
        const val TAG = "McpServer"
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.mcp

import kotlinx.coroutines.flow.StateFlow

/**
 * Live state of the local MCP server.
 *
 * [running] tracks the actual socket, not the persisted switch: the user can have the setting on
 * while the port is occupied, and the UI has to tell those two situations apart. [error] carries
 * the bind failure reason (e.g. "Address already in use").
 */
data class McpServerState(
    val running: Boolean = false,
    val port: Int = McpEndpoint.DEFAULT_PORT,
    val error: String? = null,
)

/**
 * Owns the lifecycle of the loopback MCP server.
 *
 * [start] begins reconciling the persisted setting with the live socket; it is idempotent and is
 * called once from `App.onCreate`. The controller observes the settings flow, so the switch and
 * the port field in 设置 take effect without restarting the app.
 */
interface McpServerController {
    val state: StateFlow<McpServerState>

    /** Starts the settings observer; the first emission binds the socket when enabled. */
    fun start()
}
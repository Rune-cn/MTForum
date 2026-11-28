// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred.mcp

import io.rune.mt.forum.domain.mcp.McpEndpoint

/**
 * UI state of 设置 → MCP 服务.
 *
 * [enabled] is the persisted switch, [running] the live socket. They differ when the port is
 * occupied or while a change is being applied, so the page can show the real status instead of
 * echoing the switch back.
 */
data class McpSettingsState(
    val enabled: Boolean = false,
    val port: Int = McpEndpoint.DEFAULT_PORT,
    val running: Boolean = false,
    val error: String? = null,
) {
    val endpoint: String get() = McpEndpoint.url(port)
}
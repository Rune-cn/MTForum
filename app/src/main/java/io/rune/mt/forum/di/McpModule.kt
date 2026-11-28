// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di

import io.rune.mt.forum.core.env.AppConfig
import io.rune.mt.forum.data.mcp.McpJsonRpcDispatcher
import io.rune.mt.forum.data.mcp.McpServerControllerImpl
import io.rune.mt.forum.domain.mcp.McpPostFormatter
import io.rune.mt.forum.domain.mcp.McpServerController
import io.rune.mt.forum.ui.page.forum.ForumMcpPostFormatter
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The in-app MCP server: a local, read-only JSON-RPC surface over `ForumRepository`.
 *
 * Constructed explicitly (not `singleOf`) because `McpJsonRpcDispatcher` has defaulted
 * constructor parameters, and Koin's reflection-free DSL resolves every parameter including the
 * defaults (rule 34). The controller is a singleton but does nothing until [McpServerController.start]
 * is called from `App.onCreate`; `KoinGraphTest` can therefore build the graph without binding a
 * socket.
 */
val mcpModule = module {
    // Body flattening stays in ForumBodyParser (rule 25); this adapter is the only UI-layer piece
    // the data-side dispatcher sees.
    single<McpPostFormatter> { ForumMcpPostFormatter() }

    single {
        McpJsonRpcDispatcher(
            repository = get(),
            postFormatter = get(),
            serverName = "MT论坛 MCP",
            serverVersion = AppConfig.VERSION_NAME,
        )
    }

    single<McpServerController> {
        McpServerControllerImpl(
            settingsRepository = get(),
            dispatcher = get(),
            scope = get(named("AppScope")),
        )
    }
}
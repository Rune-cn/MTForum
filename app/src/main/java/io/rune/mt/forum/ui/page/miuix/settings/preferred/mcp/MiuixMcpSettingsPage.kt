// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings.preferred.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.mcp.McpEndpoint
import io.rune.mt.forum.domain.mcp.McpToolCatalog
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.main.settings.preferred.mcp.McpSettingsViewModel
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixSwitchWidget
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → MCP 服务: the on/off switch, the loopback endpoint, the port and the tool list.
 *
 * The page is intentionally explicit about what the server is: loopback-only, unauthenticated,
 * read-only, and POST-only (no SSE). It never renders a write action because the MCP surface has
 * none — the tool list below is the complete contract.
 */
@Composable
fun MiuixMcpSettingsPage(
    enableBlur: Boolean = true,
    viewModel: McpSettingsViewModel = koinViewModel(),
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Local draft so an incomplete port (e.g. "9") is visible while typing but never persisted.
    var portText by remember(state.port) { mutableStateOf(state.port.toString()) }
    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && McpEndpoint.isValidPort(portValue)

    val copyEndpoint = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MCP endpoint", state.endpoint))
        Toast.makeText(context, R.string.mcp_server_copied, Toast.LENGTH_SHORT).show()
        Unit
    }

    MiuixPageScaffold(
        title = stringResource(R.string.mcp_server_title),
        chrome = rememberMiuixPageChrome(enableBlur),
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
    ) {
        item { Spacer(modifier = Modifier.size(12.dp)) }
        item { SmallTitle(stringResource(R.string.mcp_server_title)) }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixSwitchWidget(
                    title = stringResource(R.string.mcp_server_enable),
                    description = stringResource(R.string.mcp_server_enable_desc),
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
                BasicComponent(
                    title = state.endpoint,
                    summary = stringResource(R.string.mcp_server_endpoint),
                    onClick = copyEndpoint,
                    endActions = {
                        TextButton(
                            text = stringResource(R.string.mcp_server_copy),
                            onClick = copyEndpoint,
                        )
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    TextField(
                        value = portText,
                        onValueChange = { raw ->
                            if (raw.length <= 5 && raw.all { it.isDigit() }) {
                                portText = raw
                                raw.toIntOrNull()
                                    ?.takeIf { McpEndpoint.isValidPort(it) && it != state.port }
                                    ?.let(viewModel::setPort)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.mcp_server_port),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    )
                    Text(
                        text = if (portValid) {
                            stringResource(R.string.mcp_server_port_range)
                        } else {
                            stringResource(R.string.mcp_server_port_invalid)
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                BasicComponent(
                    title = when {
                        state.running -> stringResource(R.string.mcp_server_status_running, state.port)
                        state.error != null -> stringResource(R.string.mcp_server_status_error, state.error!!)
                        else -> stringResource(R.string.mcp_server_status_stopped)
                    },
                    summary = stringResource(R.string.mcp_server_transport_note),
                )
            }
        }

        item { SmallTitle(stringResource(R.string.mcp_server_tools, McpToolCatalog.tools.size)) }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                McpToolCatalog.tools.forEach { tool ->
                    BasicComponent(
                        title = tool.name,
                        summary = tool.description,
                    )
                }
            }
        }

        item { SmallTitle(stringResource(R.string.mcp_server_security_title)) }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(title = stringResource(R.string.mcp_server_security_loopback))
                BasicComponent(title = stringResource(R.string.mcp_server_security_no_auth))
                BasicComponent(title = stringResource(R.string.mcp_server_security_readonly))
            }
        }

        item { SmallTitle(stringResource(R.string.mcp_server_client_title)) }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.mcp_server_client_hint),
                    summary = stringResource(R.string.mcp_server_client_example, state.endpoint),
                )
            }
        }
    }
}
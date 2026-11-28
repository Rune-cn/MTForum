// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings

import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.plugin.PluginManifest
import io.rune.mt.forum.domain.plugin.PluginRunRecord
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixConfirmDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixHintTextField
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 插件: the plugin centre.
 *
 * Every plugin is one card: name, version, description, the enable switch, and the action its kind
 * allows — 打开 for a built-in (it only resolves an existing page) or 运行 for a script. The top bar
 * carries the two things the page cannot do inline: 刷新 (re-read the remote `plugins.json`) and
 * 本地安装 (pick a `.json` document with the system file picker).
 *
 * The five built-ins are the features that used to live as separate settings rows
 * (浏览历史 / 评论屏蔽 / 板块管理 / MCP 服务 / 帖子缓存), so this page is the only entry point left for
 * them — the rows were removed from the 论坛 section on purpose.
 *
 * All chrome goes through the shared Miuix widgets ([MiuixPageScaffold], [Card], [Switch],
 * [MiuixConfirmDialog] → `MiuixStyleDialog`), so OS3 and OS4 are both correct without a style flag
 * here; network/parse work stays in the ViewModel and the repository (rules 2 / 8).
 */
@Composable
fun ForumPluginPage(
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumPluginViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val chrome = rememberMiuixPageChrome(enableBlur)
    val scope = rememberCoroutineScope()

    var pendingUninstall by remember { mutableStateOf<PluginManifest?>(null) }
    var logExpanded by remember { mutableStateOf(false) }

    // Repository fields are edited locally and only written on 保存, so a half-typed owner never
    // triggers a fetch. The key is the stored value: a save (or an external change) re-seeds them.
    var owner by remember(state.config.owner) { mutableStateOf(state.config.owner) }
    var repo by remember(state.config.repo) { mutableStateOf(state.config.repo) }
    var branch by remember(state.config.branch) { mutableStateOf(state.config.branch) }

    val unreadableFile = stringResource(R.string.plugin_install_local_unreadable)
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Reading a picked document is blocking IO: never on the composition thread (rule 2).
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                Toast.makeText(context, unreadableFile, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.installFromJson(text)
            }
        }
    }

    // One-shot outcome lines (install / run / save) surface as a Toast; the state keeps the logs.
    LaunchedEffect(state.message) {
        state.message?.let { line ->
            Toast.makeText(context, line, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    MiuixPageScaffold(
        title = stringResource(R.string.plugin_page_title),
        chrome = chrome,
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        actions = {
            MiuixTopBarIconButton(onClick = viewModel::refresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.plugin_refresh_action),
                )
            }
            MiuixTopBarIconButton(
                onClick = { pickFile.launch(arrayOf("application/json", "*/*")) },
            ) {
                Icon(
                    imageVector = Icons.Rounded.FileOpen,
                    contentDescription = stringResource(R.string.plugin_install_local_action),
                )
            }
        },
        horizontalPadding = if (AppTheme.useMiuixOs4) 16.dp else 12.dp,
        verticalPadding = 12.dp,
        outerPadding = outerPadding,
    ) {
        item { Spacer(Modifier.size(4.dp)) }

        // A failed catalogue fetch is one line, not an error screen: the built-ins still work.
        state.catalogError?.let { reason ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.plugin_catalog_error_format, reason),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }

        item { SmallTitle(stringResource(R.string.plugin_section_installed)) }
        if (state.cards.isEmpty()) {
            item {
                Text(
                    text = if (state.isLoading) {
                        stringResource(R.string.forum_loading)
                    } else {
                        stringResource(R.string.plugin_empty)
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        items(items = state.cards, key = { "plugin_card_${it.manifest.id}" }) { card ->
            PluginCard(
                manifest = card.manifest,
                enabled = card.enabled,
                record = card.record,
                running = state.runningId == card.manifest.id,
                onEnabledChange = { enabled -> viewModel.setEnabled(card.manifest.id, enabled) },
                onOpen = { card.manifest.builtin?.let(viewModel::openBuiltin) },
                onRun = { viewModel.run(card.manifest) },
                onUninstall = { pendingUninstall = card.manifest },
            )
        }

        if (state.available.isNotEmpty()) {
            item { SmallTitle(stringResource(R.string.plugin_section_available)) }
            items(items = state.available, key = { "plugin_available_${it.id}" }) { manifest ->
                AvailablePluginCard(
                    manifest = manifest,
                    onInstall = { viewModel.install(manifest) },
                )
            }
        }

        // ---- 最近日志 ---------------------------------------------------------
        item { SmallTitle(stringResource(R.string.plugin_log_title)) }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.lastLogName ?: stringResource(R.string.plugin_log_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (state.lastLog.isEmpty()) {
                                    stringResource(R.string.plugin_log_empty)
                                } else {
                                    stringResource(R.string.plugin_log_lines_format, state.lastLog.size)
                                },
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (state.lastLog.isNotEmpty()) {
                            TextButton(
                                text = stringResource(
                                    if (logExpanded) R.string.plugin_log_collapse
                                    else R.string.plugin_log_expand,
                                ),
                                onClick = { logExpanded = !logExpanded },
                            )
                        }
                    }
                    if (logExpanded && state.lastLog.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        // Bounded height + its own scroll: 50 lines must never push the page's
                        // repository block off the screen.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            state.lastLog.forEach { line ->
                                Text(
                                    text = line,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- 插件仓库 ---------------------------------------------------------
        item { SmallTitle(stringResource(R.string.plugin_source_title)) }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.plugin_source_desc),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    MiuixHintTextField(
                        value = owner,
                        onValueChange = { owner = it },
                        labelText = stringResource(R.string.plugin_source_owner_label),
                    )
                    Spacer(Modifier.height(6.dp))
                    MiuixHintTextField(
                        value = repo,
                        onValueChange = { repo = it },
                        labelText = stringResource(R.string.plugin_source_repo_label),
                    )
                    Spacer(Modifier.height(6.dp))
                    MiuixHintTextField(
                        value = branch,
                        onValueChange = { branch = it },
                        labelText = stringResource(R.string.plugin_source_branch_label),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.saveRepository(owner, repo, branch) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.plugin_source_save))
                    }
                }
            }
        }
    }

    pendingUninstall?.let { manifest ->
        MiuixConfirmDialog(
            visible = true,
            title = stringResource(R.string.plugin_uninstall_title),
            message = stringResource(R.string.plugin_uninstall_message_format, manifest.name),
            confirmText = stringResource(R.string.plugin_uninstall_action),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                pendingUninstall = null
                viewModel.uninstall(manifest)
            },
            onDismiss = { pendingUninstall = null },
        )
    }
}

/** One installed plugin: identity, switch, last run and the action its kind allows. */
@Composable
private fun PluginCard(
    manifest: PluginManifest,
    enabled: Boolean,
    record: PluginRunRecord?,
    running: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = manifest.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "v${manifest.version}",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        text = stringResource(
                            if (manifest.isBuiltin) R.string.plugin_builtin_label
                            else R.string.plugin_script_label,
                        ),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(Modifier.height(4.dp))
            if (manifest.description.isNotBlank()) {
                Text(
                    text = manifest.description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = lastRunLabel(record),
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (manifest.isBuiltin) {
                    TextButton(text = stringResource(R.string.plugin_open_action), onClick = onOpen)
                } else {
                    TextButton(
                        text = stringResource(
                            if (running) R.string.plugin_running else R.string.plugin_run_action,
                        ),
                        onClick = { if (!running) onRun() },
                    )
                }
                TextButton(
                    text = stringResource(R.string.plugin_uninstall_action),
                    onClick = onUninstall,
                )
            }
        }
    }
}

/** One catalogue row that is not installed yet: what it is, plus 安装. */
@Composable
private fun AvailablePluginCard(manifest: PluginManifest, onInstall: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = manifest.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "v${manifest.version}",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (manifest.description.isNotBlank()) {
                    Text(
                        text = manifest.description,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                text = stringResource(R.string.plugin_install_action),
                onClick = onInstall,
            )
        }
    }
}

/**
 * "上次运行 …" line for a card.
 *
 * The time is the platform's own relative formatter (like 浏览历史), so the wording follows the
 * system locale instead of growing another translated label set.
 */
@Composable
private fun lastRunLabel(record: PluginRunRecord?): String {
    if (record == null || record.at <= 0L) return stringResource(R.string.plugin_never_run)
    val whenLabel = DateUtils.getRelativeTimeSpanString(
        record.at,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    return if (record.ok) {
        stringResource(R.string.plugin_last_run_ok_format, whenLabel)
    } else {
        stringResource(R.string.plugin_last_run_failed_format, whenLabel, record.message)
    }
}

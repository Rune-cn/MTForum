// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixConfirmDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixSwitchWidget
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 帖子缓存 (设置 → 插件 → 帖子缓存): the switch, the on-disk readout and 清空.
 *
 * The cache itself is written and read by the thread page's ViewModel
 * ([ForumViewModel.loadThreadPage]) through [ForumThreadCacheRepository]; this screen only owns the
 * two things a user needs to see — "is it on" and "how much room does it take" — plus the one
 * destructive action, behind a confirmation because a cleared cache cannot be rebuilt offline.
 *
 * All chrome is the shared Miuix set, so OS3/OS4 both render correctly without a style branch here.
 */
@Composable
fun ForumThreadCachePage(
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumThreadCacheViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }

    val sizeLabel = Formatter.formatFileSize(context, state.bytes)

    MiuixPageScaffold(
        title = stringResource(R.string.thread_cache_title),
        chrome = rememberMiuixPageChrome(enableBlur),
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        actions = {
            // 清空 only when there is something to clear; the dialog below is mandatory.
            if (state.count > 0) {
                MiuixTopBarIconButton(onClick = { showClearConfirm = true }) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.thread_cache_clear_action),
                    )
                }
            }
        },
        horizontalPadding = if (AppTheme.useMiuixOs4) 16.dp else 12.dp,
        verticalPadding = 12.dp,
        outerPadding = outerPadding,
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                MiuixSwitchWidget(
                    title = stringResource(R.string.thread_cache_switch_title),
                    description = stringResource(R.string.thread_cache_switch_desc),
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
            }
        }
        item { SmallTitle(stringResource(R.string.thread_cache_usage_title)) }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.thread_cache_usage_format,
                            state.count,
                            sizeLabel,
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.isLoading) {
                            stringResource(R.string.forum_loading)
                        } else if (state.count == 0) {
                            stringResource(R.string.thread_cache_empty)
                        } else {
                            stringResource(R.string.thread_cache_usage_hint)
                        },
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    state.error?.let { reason ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = reason,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        text = stringResource(R.string.thread_cache_clear_action),
                        onClick = { showClearConfirm = true },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        MiuixConfirmDialog(
            visible = true,
            title = stringResource(R.string.thread_cache_clear_title),
            message = stringResource(R.string.thread_cache_clear_message),
            confirmText = stringResource(R.string.thread_cache_clear_action),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                showClearConfirm = false
                viewModel.clear()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings.preferred

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.core.device.model.Level
import io.rune.mt.forum.core.env.AppConfig
import io.rune.mt.forum.ui.icons.AppIcons
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.main.settings.AccountSection
import io.rune.mt.forum.ui.page.main.settings.AccountViewModel
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.main.settings.preferred.PreferredViewEvent
import io.rune.mt.forum.ui.page.main.settings.preferred.PreferredViewModel
import io.rune.mt.forum.ui.page.main.settings.preferred.about.updateCheckDescription
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBlockingLoadingDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixNavigationItemWidget
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixDropdownPreference
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixStyleDialog
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixPreferredPage(
    enableBlur: Boolean,
    viewModel: PreferredViewModel = koinViewModel(),
    accountViewModel: AccountViewModel = koinViewModel(),
    title: String,
    outerPadding: PaddingValues,
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val accountSession by accountViewModel.session.collectAsStateWithLifecycle()

    val revLevel = when (AppConfig.LEVEL) {
        Level.STABLE -> stringResource(id = R.string.stable)
        Level.PREVIEW -> stringResource(id = R.string.preview)
        Level.UNSTABLE -> stringResource(id = R.string.unstable)
    }

    var updateMessage by remember { mutableStateOf<String?>(null) }
    var showUpdateSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Timber.i("Settings page shown")
        viewModel.uiEvents.collect { event ->
            when (event) {
                is PreferredViewEvent.ShowMessage -> {
                    updateMessage = context.getString(event.messageResId, *event.args.toTypedArray())
                }

                PreferredViewEvent.ShowUpdateSheet -> {
                    showUpdateSheet = true
                }
            }
        }
    }

    MiuixPageScaffold(
        title = title,
        chrome = rememberMiuixPageChrome(enableBlur),
        outerPadding = outerPadding,
    ) {
        item { Spacer(modifier = Modifier.size(12.dp)) }
        item { SmallTitle(stringResource(R.string.account_section_title)) }
        item {
            AccountSection(
                session = accountSession,
                onLogin = { navigator.push(Route.Login) },
                onLogout = accountViewModel::logout,
                // 我的主页 lives on the account card's avatar now; the forum section below only
                // keeps the favourites shortcut.
                onOpenProfile = { user ->
                    navigator.push(Route.UserProfile(user.uid, user.name))
                },
            )
        }
        item { SmallTitle(stringResource(R.string.forum_section_title)) }
        item {
            // Forum shortcuts. Notifications and the profile page are reached from the home page
            // bell and the account card avatar instead of duplicated here; 每日签到 moved to the
            // home top bar's right edge (a sheet, not a settings card). 我的收藏 and 草稿 are the
            // signed-in user's own forum data.
            //
            // 浏览历史 / 评论屏蔽 / 板块管理 / MCP 服务 moved out of this page on request: they are
            // plugin cards now (设置 → 插件), and the plugin centre is their only entry. Keeping a
            // second row here would be a second entry point for the same page.
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = stringResource(R.string.favorites_title),
                    description = stringResource(R.string.favorites_entry_desc),
                    onClick = { navigator.push(Route.Favorites) },
                )
                MiuixNavigationItemWidget(
                    title = stringResource(R.string.drafts_title),
                    description = stringResource(R.string.drafts_entry_desc),
                    onClick = { navigator.push(Route.Drafts) },
                )
            }
        }
        item { SmallTitle(stringResource(R.string.personalization)) }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    icon = AppIcons.Theme,
                    title = stringResource(R.string.theme_settings),
                    description = stringResource(R.string.theme_settings_desc),
                    onClick = {
                        navigator.push(Route.Theme)
                    },
                )
            }
        }
        item { SmallTitle(stringResource(R.string.plugin_section_title)) }
        item {
            // One row: the plugin centre. Its cards are where 浏览历史 / 评论屏蔽 / 板块管理 /
            // MCP 服务 are reached from now on, so no forum or 其他 row mentions them any more.
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = stringResource(R.string.plugin_page_title),
                    description = stringResource(R.string.plugin_entry_desc),
                    onClick = { navigator.push(Route.Plugins) },
                )
            }
        }
        item { SmallTitle(stringResource(R.string.other)) }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.about_detail),
                    summary = updateCheckDescription(uiState.checkState) ?: if (uiState.hasUpdate) {
                        stringResource(
                            R.string.update_available,
                            uiState.remoteVersion,
                        )
                    } else {
                        "$revLevel ${AppConfig.VERSION_NAME}"
                    },
                    summaryColor = BasicComponentColors(
                        color = if (uiState.hasUpdate) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        disabledColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                    ),
                    onClick = { navigator.push(Route.About) },
                )
            }
        }
    }
    // Update check result: the shared style-aware dialog (OS4 glass / OS3 window).
    MiuixStyleDialog(
        show = updateMessage != null,
        title = stringResource(R.string.get_update),
        summary = updateMessage.orEmpty(),
        onDismissRequest = { updateMessage = null },
    ) {
        TextButton(
            text = stringResource(R.string.cancel),
            onClick = { updateMessage = null },
        )
    }

    // Update result sheet: notes rendered with MiuixMarkdownText; download/open
    // actions land on the remote URL in the system browser.
    MiuixUpdateSheet(
        show = showUpdateSheet,
        updateInfo = uiState.updateInfo,
        channelLabel = revLevel,
        releaseNotes = uiState.updateInfo?.updateLog,
        onDismissRequest = { showUpdateSheet = false },
        onOpenReleasePage = { uriHandler.openUri(it) },
        onDownload = { info ->
            val url = info.downloadUrl.ifBlank { info.releaseUrl }
            if (url.isNotBlank()) uriHandler.openUri(url)
        },
    )

    MiuixBlockingLoadingDialog(
        // Derived from the repository's check state, so it cannot outlive the check: the
        // repository settles state on every path (skip / timeout / error / cancellation).
        visible = uiState.isChecking,
        text = stringResource(R.string.checking_update),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

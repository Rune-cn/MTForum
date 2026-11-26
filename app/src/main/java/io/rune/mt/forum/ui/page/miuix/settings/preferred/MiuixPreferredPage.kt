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
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBlockingLoadingDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixNavigationItemWidget
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixDropdownPreference
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun MiuixPreferredPage(
    enableBlur: Boolean,
    viewModel: PreferredViewModel = koinViewModel(),
    accountViewModel: AccountViewModel = koinViewModel(),
    title: String,
    outerPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
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
    var showUpdateLoading by remember { mutableStateOf(false) }
    var showUpdateSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Timber.i("Settings page shown")
        viewModel.uiEvents.collect { event ->
            when (event) {
                is PreferredViewEvent.ShowMessage -> {
                    updateMessage = context.getString(event.messageResId)
                }

                PreferredViewEvent.ShowUpdateLoading -> {
                    showUpdateLoading = true
                }

                PreferredViewEvent.HideUpdateLoading -> {
                    showUpdateLoading = false
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
            )
        }
        item { SmallTitle(stringResource(R.string.forum_section_title)) }
        item {
            // Forum shortcuts. The profile row needs the signed-in uid and is meaningless
            // without a session, so guests only see the two member surfaces they can still
            // open (the screens themselves explain why a login is required).
            val signedInUser = accountSession.user?.takeIf { accountSession.isLoggedIn }
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = stringResource(R.string.notice_entry_title),
                    description = stringResource(R.string.notice_entry_desc),
                    onClick = { navigator.push(Route.Notices) },
                )
                MiuixNavigationItemWidget(
                    title = stringResource(R.string.favorites_title),
                    description = stringResource(R.string.favorites_entry_desc),
                    onClick = { navigator.push(Route.Favorites) },
                )
                if (signedInUser != null) {
                    MiuixNavigationItemWidget(
                        title = stringResource(R.string.profile_entry_title),
                        description = stringResource(R.string.profile_entry_desc),
                        onClick = { navigator.push(Route.UserProfile(signedInUser.uid, signedInUser.name)) },
                    )
                }
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
        item { SmallTitle(stringResource(R.string.other)) }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.about_detail),
                    summary = if (uiState.hasUpdate) {
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
    // Update check result shown with miuix WindowDialog.
    WindowDialog(
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
        visible = showUpdateLoading,
        text = stringResource(R.string.checking_update),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

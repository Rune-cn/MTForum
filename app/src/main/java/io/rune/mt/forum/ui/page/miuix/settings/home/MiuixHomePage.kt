// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.core.env.DeviceConfig
import io.rune.mt.forum.ui.icons.AppIcons
import io.rune.mt.forum.ui.page.main.settings.home.HomePageViewState
import io.rune.mt.forum.ui.page.main.settings.home.HomePageViewModel
import io.rune.mt.forum.ui.theme.AppTheme
import io.rune.mt.forum.ui.theme.miuixHomeStatusCardColorAbnormal
import io.rune.mt.forum.ui.theme.miuixHomeStatusCardColorRunning
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor

@Composable
fun MiuixHomePage(
    enableBlur: Boolean,
    viewModel: HomePageViewModel = koinViewModel(),
    title: String,
    outerPadding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val chrome = rememberMiuixPageChrome(enableBlur)

    LaunchedEffect(Unit) {
        Timber.i("Home page shown")
    }

    MiuixPageScaffold(
        title = title,
        chrome = chrome,
        outerPadding = outerPadding,
        // Restores the original 12dp inset on all sides; dropping the vertical one made the
        // content sit flush under the bar.
        horizontalPadding = 12.dp,
        verticalPadding = 12.dp,
    ) {
        item {
            MiuixStatusCard(uiState = uiState)
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.home_device_info_model),
                    summary = uiState.deviceName,
                )
                BasicComponent(
                    title = stringResource(R.string.home_device_info_system),
                    summary = DeviceConfig.systemVersion,
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(bottom = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.home_learn_more_title),
                    summary = stringResource(R.string.home_learn_more_desc),
                    endActions = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_link_icon),
                            contentDescription = null,
                        )
                    },
                    onClick = { uriHandler.openUri("https://github.com/Rune-cn/MTForum") },
                )
            }
        }

        item { Spacer(modifier = Modifier.navigationBarsPadding()) }
    }
}

/**
 * Demo-shell runtime status card (non-installer semantics).
 *
 * Shows 「运行正常 / Running normally」 (green) by default and keeps the red
 * "abnormal" branch ready for a future real health check, driven by
 * [HomePageViewState.isRunningNormally].
 */
@Composable
private fun MiuixStatusCard(
    uiState: HomePageViewState,
) {
    val isHealthy = uiState.isHealthy

    val containerColor = if (isHealthy) {
        when {
            isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
            AppTheme.isDark -> Color(0xFF1A3825)
            else -> Color(0xFFDFFAE4)
        }
    } else {
        when {
            isDynamicColor -> MiuixTheme.colorScheme.errorContainer
            AppTheme.isDark -> Color(0xFF381A1A)
            else -> Color(0xFFFAEEEE)
        }
    }

    val textContentColor = if (isHealthy) {
        if (isDynamicColor) MiuixTheme.colorScheme.onSecondaryContainer else MiuixTheme.colorScheme.onSurface
    } else {
        if (isDynamicColor) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = containerColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 128.dp),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(42.dp, 22.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(124.dp),
                        imageVector = if (isHealthy) AppIcons.Active else Icons.Rounded.ErrorOutline,
                        tint = if (isHealthy) {
                            if (isDynamicColor) {
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                            } else {
                                miuixHomeStatusCardColorRunning
                            }
                        } else {
                            if (isDynamicColor) {
                                MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
                            } else {
                                miuixHomeStatusCardColorAbnormal
                            }
                        },
                        contentDescription = null,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(
                            if (isHealthy) R.string.home_status_running else R.string.home_status_abnormal,
                        ),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textContentColor,
                    )
                }
            }
        }
    }
}

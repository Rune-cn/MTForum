// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.navigation

import android.os.Build
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.settings.model.preferences.ThemeState
import io.rune.mt.forum.ui.library.FloatingBottomBarMode
import io.rune.mt.forum.ui.page.main.settings.SettingsSharedViewModel
import io.rune.mt.forum.ui.page.miuix.settings.SettingsCompactLayout
import io.rune.mt.forum.ui.page.miuix.settings.SettingsWideScreenLayout
import io.rune.mt.forum.ui.theme.LocalWindowLayoutInfo
import io.rune.mt.forum.ui.theme.WindowLayoutType
import io.rune.mt.forum.ui.theme.rememberMiuixBlurBackdrop
import org.koin.compose.koinInject
import io.rune.mt.forum.ui.theme.isOs4PlatformSupported
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

// Centralized complex layout logic to keep provider clean and readable
@Composable
fun MiuixMainPageWrapper(uiState: ThemeState, sharedViewModel: SettingsSharedViewModel) {
    val layoutInfo = LocalWindowLayoutInfo.current
    val sharedState by sharedViewModel.state.collectAsStateWithLifecycle()
    val useBlur = uiState.useBlur
    // OS4 always presents the floating glass navigation bar; OS3 keeps it opt-in.
    // The platform check mirrors the theme layer: without render effects the glass bar would
    // fall back to an opaque surface, so OS4 degrades to the classic bar instead.
    val useOs4 = isOs4PlatformSupported && uiState.useMiuixOs4
    val useFloatingBottomBar = useOs4 || uiState.useAppleFloatingBar
    val floatingBottomBarMode = when {
        useBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> FloatingBottomBarMode.LiquidGlass
        useBlur -> FloatingBottomBarMode.Blur
        else -> FloatingBottomBarMode.None
    }

    val configCount = 0
    val homeLabel = stringResource(R.string.home)
    val homeIcon = ImageVector.vectorResource(R.drawable.ic_nav_forum)
    val guideIcon = ImageVector.vectorResource(R.drawable.ic_nav_guide)
    val messagesIcon = ImageVector.vectorResource(R.drawable.ic_nav_messages)
    val guideLabel = stringResource(R.string.nav_guide)
    val messagesLabel = stringResource(R.string.nav_messages)
    val preferredLabel = stringResource(R.string.preferred)

    // Four destinations, with 导读 and 消息 in the middle of the bar as requested:
    //   论坛 · 导读 · 消息 · 设置
    val navigationItems = remember(homeLabel, guideLabel, messagesLabel, preferredLabel, homeIcon, guideIcon, messagesIcon) {
        listOf(
            NavigationItem(
                label = homeLabel,
                icon = homeIcon,
            ),
            NavigationItem(
                label = guideLabel,
                icon = guideIcon,
            ),
            NavigationItem(
                label = messagesLabel,
                icon = messagesIcon,
            ),
            NavigationItem(
                label = preferredLabel,
                icon = Icons.Rounded.Settings,
            ),
        )
    }

    val pagerState = rememberPagerState(
        initialPage = sharedState.lastMainPageIndex,
        pageCount = { navigationItems.size },
    )
    val mainPagerState = rememberMainPagerState(pagerState)
    val currentPage = mainPagerState.pagerState.currentPage
    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }
    LaunchedEffect(settledPage) {
        if (sharedState.lastMainPageIndex != settledPage) {
            sharedViewModel.updateLastMainPageIndex(settledPage)
        }
    }
    MainScreenBackHandler(
        mainPagerState = mainPagerState,
        navController = LocalNavigator.current,
    )

    val snackbarHostState = remember { SnackbarHostState() }

    // Create separated backdrops for different blurred components
    val floatingBackdrop = rememberLayerBackdrop()
    val miuixBackdrop = rememberMiuixBlurBackdrop(useBlur)

    // Branch statically without layout delay traps
    if (layoutInfo.type == WindowLayoutType.EXPANDED || layoutInfo.showNavigationRail) {
        SettingsWideScreenLayout(
            configCount = configCount,
            mainPagerState = mainPagerState,
            navigationItems = navigationItems,
            snackbarHostState = snackbarHostState,
            useFloatingBottomBar = useFloatingBottomBar,
            useOs4 = useOs4,
            floatingBottomBarMode = floatingBottomBarMode,
            floatingBackdrop = floatingBackdrop,
            miuixBackdrop = miuixBackdrop,
        )
    } else {
        SettingsCompactLayout(
            configCount = configCount,
            mainPagerState = mainPagerState,
            navigationItems = navigationItems,
            snackbarHostState = snackbarHostState,
            useFloatingBottomBar = useFloatingBottomBar,
            useOs4 = useOs4,
            floatingBottomBarMode = floatingBottomBarMode,
            floatingBackdrop = floatingBackdrop,
            miuixBackdrop = miuixBackdrop,
        )
    }
}

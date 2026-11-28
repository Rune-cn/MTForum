// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.navigation

import timber.log.Timber

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.data.plugin.PluginUiBridge
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackAnimation
import io.rune.mt.forum.domain.settings.model.preferences.ThemeState
import kotlinx.coroutines.flow.StateFlow
import io.rune.mt.forum.ui.animation.predictiveback.OS4_DIM_AMOUNT
import io.rune.mt.forum.ui.animation.predictiveback.appNavTransition
import io.rune.mt.forum.ui.animation.predictiveback.os4CardCornerRadius
import io.rune.mt.forum.ui.page.forum.ForumConversationPage
import io.rune.mt.forum.ui.page.forum.ForumDraftsPage
import io.rune.mt.forum.ui.page.forum.ForumFavoritesPage
import io.rune.mt.forum.ui.page.forum.ForumHomePage
import io.rune.mt.forum.ui.page.forum.ForumListPage
import io.rune.mt.forum.ui.page.forum.ForumNoticesPage
import io.rune.mt.forum.ui.page.forum.ForumProfilePage
import io.rune.mt.forum.ui.page.forum.ForumSearchPage
import io.rune.mt.forum.ui.page.forum.ForumThreadCachePage
import io.rune.mt.forum.ui.page.forum.ForumThreadHistoryPage
import io.rune.mt.forum.ui.page.forum.ForumThreadPage
import io.rune.mt.forum.ui.page.forum.NewThreadPage
import io.rune.mt.forum.ui.page.login.MiuixLoginPage
import io.rune.mt.forum.ui.page.main.settings.SettingsSharedViewModel
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.res.stringResource
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.page.miuix.settings.ForumBoardSettingsPage
import io.rune.mt.forum.ui.page.miuix.settings.ForumCommentFilterPage
import io.rune.mt.forum.ui.page.miuix.settings.ForumPluginPage
import io.rune.mt.forum.ui.page.miuix.settings.preferred.about.MiuixAboutPage
import io.rune.mt.forum.ui.page.miuix.settings.preferred.mcp.MiuixMcpSettingsPage
import io.rune.mt.forum.ui.page.miuix.settings.preferred.theme.MiuixThemeSettingsPage
import io.rune.mt.forum.ui.util.rememberDeviceCornerRadius
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import io.rune.mt.forum.ui.theme.isOs4PlatformSupported
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppNavContainer(
    uiState: ThemeState,
    deepLink: StateFlow<Route?>,
    onDeepLinkConsumed: () -> Unit,
) {
    val sharedViewModel: SettingsSharedViewModel = koinViewModel(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity,
    )

    val backStack = rememberNavBackStack<Route>(Route.Main)
    // `Navigator` is the single writer of back-stack keys and keeps every entry's content key
    // unique (see `Navigator.push`): NavDisplay rejects a second entry that resolves to a key
    // already on the stack. A stack *restored* from a process that died before that rule can
    // still carry the same destination twice, so normalise it once — inside `remember`, i.e.
    // before the first composition of NavDisplay reads the keys; a duplicate would otherwise
    // throw while NavDisplay reconciles and crash the app on every restore.
    val navigator = remember(backStack) {
        Navigator(backStack).apply { dropDuplicateDestinations() }
    }
    val onBack = remember(navigator) { { navigator.pop() } }

    // Plugin pages: the 插件 page's 打开 button and a script's `ctx.ui.open(id)` both publish a
    // built-in page id on the same bridge, and this is the only place that turns one into a route
    // (`pluginBuiltinRoute`). Plugins never hold a Navigator — the nav host owns it.
    val pluginUiBridge: PluginUiBridge = koinInject()
    LaunchedEffect(pluginUiBridge) {
        pluginUiBridge.requests.collect { builtinId ->
            val route = pluginBuiltinRoute(builtinId) ?: return@collect
            Timber.tag("PluginUi").i("open builtin=%s route=%s", builtinId, route)
            navigator.push(route)
        }
    }

    // A link is a one-shot jump: navigate, then clear the flow so nothing replays it on the next
    // recomposition. The Activity only publishes a route on cold start or a new VIEW intent.
    val deepLinkRoute by deepLink.collectAsStateWithLifecycle()
    LaunchedEffect(deepLinkRoute) {
        val route = deepLinkRoute ?: return@LaunchedEffect
        Timber.tag("DeepLink").i("navigate to %s", route)
        if (route == Route.Main) {
            // A home link targets the shell itself: collapse the back stack to it and select the
            // 论坛 pager page, so the app lands on the home tab rather than on whatever tab was
            // last open (the shell restores `lastMainPageIndex` on its own).
            sharedViewModel.requestMainPage(MAIN_PAGE_FORUM)
        }
        navigator.navigateToDeepLink(route)
        onDeepLinkConsumed()
    }

    val useBlur = uiState.useBlur
    // Same platform guard as MiuixNavWrapper: OS4 needs render effects, otherwise it degrades
    // to OS3 so sub-pages stay consistent with the navigation bar that opened them.
    val useOs4 = isOs4PlatformSupported && uiState.useMiuixOs4

    CompositionLocalProvider(
        LocalNavigator provides navigator,
    ) {
        val deviceCornerRadius = rememberDeviceCornerRadius(defaultRadius = 0.dp)
        // OS4 卡片式返回：出页缩成带圆角的卡片，四角都要圆；设备不上报圆角时退化到 32dp。
        val os4CardBack = useOs4 && uiState.predictiveBackAnimation == PredictiveBackAnimation.MIUIX
        val cardCornerRadius = os4CardCornerRadius(deviceCornerRadius)
        val roundAllCorners = os4CardBack ||
            uiState.predictiveBackAnimation == PredictiveBackAnimation.AOSP ||
            uiState.predictiveBackAnimation == PredictiveBackAnimation.Scale ||
            uiState.predictiveBackAnimation == PredictiveBackAnimation.Classic
        val backdropColor = MiuixTheme.colorScheme.surface
        // OS4 卡片式返回用 0.2 的轻微压暗（scrim 是纯黑全屏层，0.5 读起来就是黑闪）；
        // 其余动画保持原来的 0.5，OS3 观感不变。
        val dimAmount = if (os4CardBack) OS4_DIM_AMOUNT else 0.5f
        val effects = remember(cardCornerRadius, roundAllCorners, backdropColor, dimAmount) {
            NavDisplayEffects(
                enableCornerClip = true,
                cornerClipRadius = if (roundAllCorners) cardCornerRadius else deviceCornerRadius,
                cornerClipMode = if (roundAllCorners) NavCornerClipMode.All else NavCornerClipMode.Leading,
                dimAmount = dimAmount,
                backdropColor = backdropColor,
                // 拖动过程中不要吞输入，否则手势到一半会“卡住”手感
                blockInputDuringTransition = false,
            )
        }
        val transition = remember(
            uiState.predictiveBackAnimation,
            uiState.predictiveBackExitDirection,
            os4CardBack,
            cardCornerRadius,
        ) {
            appNavTransition(
                animation = uiState.predictiveBackAnimation,
                exitDirection = uiState.predictiveBackExitDirection,
                useMiuixOs4 = os4CardBack,
                cornerRadius = cardCornerRadius,
            )
        }
        val swipeBackDirection = when (LocalLayoutDirection.current) {
            LayoutDirection.Rtl -> NavSwipeDirection.RightToLeft
            LayoutDirection.Ltr -> NavSwipeDirection.LeftToRight
        }

        // 诊断日志：size<=1 时 NavDisplay 的预测式返回驱动是关闭的（系统接管退出动画）。
        Timber.d(
            "predictiveBack: animation=%s size=%d gestureDriver=%s",
            uiState.predictiveBackAnimation,
            backStack.size,
            backStack.size > 1,
        )

        // ⚠️ 这里不再注册任何 entry 级 NavigationBackHandler / BackHandler。
        // NavDisplay 内部的 PredictiveBackHandlerWithSessions(enabled = size > 1) 才是唯一的
        // 手势驱动者；entry 内任何 enabled 的返回处理器都会抢走手势（= 无预览）。
        //
        // contentKey 就是 route 本身，每个 entry 必须唯一（重复会抛
        // `IllegalArgumentException: Duplicate contentKey on the back stack`）：可重复的页面
        // （ForumThread / ForumList / UserProfile / PrivateConversation / ForumSearch /
        // NewThread）由 `Navigator.push` 写入 instanceId，别的路由一经入栈只保留一份。
        // 不要在这里改成会丢掉 instanceId 的 contentKey，也不要绕过 Navigator 直接 add。
        NavDisplay(
            backStack = backStack,
            onBack = onBack,
            transition = transition,
            effects = effects,
        ) {
            entry<Route.Main> {
                MiuixMainPageWrapper(uiState, sharedViewModel)
            }
            entry<Route.Login>(swipeDismiss = swipeBackDirection) {
                MiuixLoginPage(enableBlur = useBlur)
            }
            entry<Route.About>(swipeDismiss = swipeBackDirection) {
                MiuixAboutPage(useBlur = useBlur, useOs4 = useOs4)
            }
            entry<Route.Theme>(swipeDismiss = swipeBackDirection) {
                MiuixThemeSettingsPage()
            }
            entry<Route.Mcp>(swipeDismiss = swipeBackDirection) {
                MiuixMcpSettingsPage(enableBlur = useBlur)
            }
            entry<Route.ForumHome>(swipeDismiss = swipeBackDirection) {
                ForumHomePage(
                    enableBlur = useBlur,
                    title = stringResource(R.string.app_name),
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.ForumList>(swipeDismiss = swipeBackDirection) { arg ->
                ForumListPage(
                    fid = arg.fid,
                    forumName = arg.name,
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.ForumThread>(swipeDismiss = swipeBackDirection) { arg ->
                ForumThreadPage(
                    tid = arg.tid,
                    title = arg.title,
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                    // The device store is passed in here rather than resolved inside the page, so the
                    // page keeps a Noop default that previews and UI tests can compose without Koin.
                    threadHistory = koinInject(),
                )
            }
            entry<Route.PrivateConversation>(swipeDismiss = swipeBackDirection) { arg ->
                ForumConversationPage(
                    uid = arg.uid,
                    name = arg.name,
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.NewThread>(swipeDismiss = swipeBackDirection) { arg ->
                NewThreadPage(
                    fid = arg.fid,
                    forumName = arg.name,
                    draftTid = arg.draftTid,
                    draftPid = arg.draftPid,
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.Drafts>(swipeDismiss = swipeBackDirection) {
                ForumDraftsPage(
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.ThreadHistory>(swipeDismiss = swipeBackDirection) {
                ForumThreadHistoryPage(
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.Notices>(swipeDismiss = swipeBackDirection) {
                ForumNoticesPage(
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.UserProfile>(swipeDismiss = swipeBackDirection) { arg ->
                ForumProfilePage(
                    uid = arg.uid,
                    fallbackName = arg.name,
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.ForumSearch>(swipeDismiss = swipeBackDirection) { arg ->
                ForumSearchPage(
                    initialKeyword = arg.keyword,
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.Favorites>(swipeDismiss = swipeBackDirection) {
                ForumFavoritesPage(
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.CommentFilter>(swipeDismiss = swipeBackDirection) {
                ForumCommentFilterPage(
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.BoardSettings>(swipeDismiss = swipeBackDirection) {
                ForumBoardSettingsPage(
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.Plugins>(swipeDismiss = swipeBackDirection) {
                ForumPluginPage(
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            entry<Route.ThreadCache>(swipeDismiss = swipeBackDirection) {
                ForumThreadCachePage(
                    enableBlur = useBlur,
                    outerPadding = PaddingValues(),
                )
            }
            // Shell mode：其余页面均已移除。
        }
    }
}

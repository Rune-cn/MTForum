// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.icons.AppMiuixIcons
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** Content margin every sheet built on [MiuixBottomSheet] uses (16 dp at the sides, no bottom). */
internal val MiuixBottomSheetInsideMargin: DpSize = DpSize(16.dp, 0.dp)

/**
 * The app's one bottom-sheet container: OS4 glass sheet / OS3 window sheet (rule 8).
 *
 * It exists so the 每日签到 panel ([`ForumSignInSheet`]) and the reply composer
 * ([`ReplyComposer`]) are **provably** the same surface: both build on this call, therefore the
 * corner radius, the content margin, the header row (title + optional start/end actions), the
 * drag handle and the dismiss gestures come from one implementation instead of two copies that
 * can drift apart. New compact panels should go through it for the same reason.
 *
 * Style split (rule 8):
 * - OS4 (`AppTheme.useMiuixOs4`) → [GlassBottomSheet]. It is an **inline** overlay: it renders
 *   into the root Miuix `Scaffold`'s dialog layer, so the host `MiuixComponentHost` must be
 *   mounted (rule 9) and the caller composes it as a sibling of the page scaffold in the page
 *   root `Box` (rule 12). [backdrop] must be the page-chrome **content** layer (or `null` for the
 *   opaque fallback) — never the host-level `pageBackdrop`, which would make the glass surface
 *   sample its own RenderNode (rules 23/28).
 * - OS3 → [WindowBottomSheet], a real dialog window. Its placement in the composition does not
 *   matter for layout (it is a separate window), which is why callers keep it next to their other
 *   overlays.
 *
 * @param show Target visibility. Keep the call composed while the sheet animates out; do not wrap
 *   the call in `if (show)`.
 * @param onDismissRequest Scrim tap, downward drag or back gesture, and the plain close action.
 * @param title Header title; with [startAction] / [endAction] it forms the sheet's header row.
 * @param backdrop OS4 glass sampling layer (page content layer) or `null`.
 * @param startAction Header leading slot (a close affordance in the reply sheet).
 * @param endAction Header trailing slot.
 * @param defaultWindowInsetsPadding Keep Miuix's own window-inset padding. Pass `false` when the
 *   content applies its own IME/navigation-bar padding (rule 24: one
 *   `navigationBars.union(ime)` pass, never stacked with `imePadding()` + `navigationBarsPadding()`).
 */
@Composable
internal fun MiuixBottomSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String?,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    startAction: (@Composable () -> Unit)? = null,
    endAction: (@Composable () -> Unit)? = null,
    defaultWindowInsetsPadding: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (AppTheme.useMiuixOs4) {
        GlassBottomSheet(
            onDismissRequest = onDismissRequest,
            show = show,
            backdrop = backdrop,
            title = title,
            startAction = startAction,
            endAction = endAction,
            insideMargin = MiuixBottomSheetInsideMargin,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            modifier = modifier,
        ) {
            content()
        }
    } else {
        WindowBottomSheet(
            show = show,
            onDismissRequest = onDismissRequest,
            title = title,
            startAction = startAction,
            endAction = endAction,
            insideMargin = MiuixBottomSheetInsideMargin,
            backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            modifier = modifier,
        ) {
            content()
        }
    }
}

/**
 * The standard sheet-header close affordance.
 *
 * Uses Miuix's `LocalDismissState` when the sheet provides one (both variants run the same sheet
 * layout, so the close animates out through the sheet's own dismissal path) and falls back to
 * [onDismissRequest] when it does not. The button itself is [MiuixBackButton], the project's
 * style-aware icon button (rule 7); a bare `IconButton` would look wrong under OS4.
 *
 * No `BackHandler` is needed: the sheet handles the system back gesture itself (predictive back
 * stays with the sheet/NavDisplay, rule 15).
 */
@Composable
internal fun MiuixBottomSheetCloseAction(onDismissRequest: () -> Unit) {
    val dismissState = LocalDismissState.current
    MiuixBackButton(
        icon = AppMiuixIcons.Close,
        contentDescription = stringResource(R.string.close),
        onClick = { dismissState?.invoke() ?: onDismissRequest() },
    )
}
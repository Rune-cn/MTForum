// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The app's one style-aware modal dialog: OS4 glass dialog / OS3 window dialog (rule 8).
 *
 * Every confirmation/error/loading dialog goes through this call so the style split is decided
 * once from [AppTheme.useMiuixOs4] instead of each caller picking a Miuix widget by hand. A bare
 * `WindowDialog` under OS4 is the same class of leak as a bare `WindowListPopup`: it is the OS3
 * surface, while the glass dialog is the OS4 one.
 *
 * OS3 keeps `WindowDialog`'s real dialog window, so its composition position does not matter.
 * The OS4 [GlassDialog] is an inline overlay that renders into the root Miuix scaffold's dialog
 * layer, exactly like [MiuixBottomSheet]; it therefore needs the app-wide
 * [MiuixComponentHost] mounted (rule 9) and must be composed as part of a page subtree, never
 * from a composition-local that does not exist there.
 *
 * [backdrop] is the page-chrome **content** layer for the glass surface, or `null` to use the
 * opaque fallback. Never pass the host-level `pageBackdrop`: that layer contains the dialog
 * itself and sampling it is the RenderNode self-cycle of rules 23/29.
 */
@Composable
internal fun MiuixStyleDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    summary: String? = null,
    backdrop: Backdrop? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (AppTheme.useMiuixOs4) {
        GlassDialog(
            onDismissRequest = onDismissRequest,
            show = show,
            backdrop = backdrop,
            modifier = modifier,
            title = title,
            titleColor = titleColor,
            summary = summary,
            onDismissFinished = onDismissFinished,
            content = content,
        )
    } else {
        WindowDialog(
            show = show,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            title = title,
            titleColor = titleColor,
            summary = summary,
            onDismissFinished = onDismissFinished,
        ) {
            content()
        }
    }
}
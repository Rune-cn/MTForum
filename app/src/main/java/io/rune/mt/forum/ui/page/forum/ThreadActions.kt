// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * Trailing overflow actions for the thread screen: copy link and share.
 *
 * Both style branches anchor to the button's real window bounds. Two placement bugs lived here:
 *
 *  - OS4 passed `Rect.Zero` as `anchorBounds` (it was never measured), so the glass popup opened
 *    at the window's top-left corner instead of under the icon.
 *  - OS3 used the default popup position provider with `Align.Start`, which left-aligns the menu
 *    with the button; on a right-edge button the menu then overflowed off-screen and got clamped,
 *    which reads as "错位". `ContextMenuPositionProvider` + `TopEnd` right-aligns it properly.
 */
@Composable
internal fun ThreadActions(
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Window-space bounds of the icon button; refreshed on every layout pass.
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }


    val copyLabel = stringResource(R.string.thread_action_copy_link)
    val shareLabel = stringResource(R.string.thread_action_share)

    Box {
        MiuixTopBarIconButton(
            onClick = { expanded = true },
            modifier = Modifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() },
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = copyLabel,
            )
        }

        if (AppTheme.useMiuixOs4) {
            GlassPopup(
                show = expanded,
                onDismissRequest = { expanded = false },
                anchorBounds = anchorBounds,
                // Intentionally null: see the note above about render-node cycles.
                backdrop = null,
                visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(AppTheme.isDark)),
            ) {
                GlassPopupItem(
                    text = copyLabel,
                    onClick = {
                        expanded = false
                        onCopyLink()
                    },
                )
                GlassPopupItem(
                    text = shareLabel,
                    onClick = {
                        expanded = false
                        onShare()
                    },
                )
            }
        } else {
            WindowListPopup(
                show = expanded,
                // Right-aligned context menu: matches a top-bar overflow button.
                popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                alignment = PopupPositionProvider.Align.TopEnd,
                onDismissRequest = { expanded = false },
            ) {
                ListPopupColumn {
                    listOf(copyLabel, shareLabel).forEachIndexed { index, label ->
                        DropdownImpl(
                            text = label,
                            optionSize = 2,
                            isSelected = false,
                            index = index,
                            onSelectedIndexChange = {
                                expanded = false
                                when (index) {
                                    0 -> onCopyLink()
                                    else -> onShare()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

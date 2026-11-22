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
import androidx.compose.ui.res.stringResource
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.page.miuix.widgets.LocalMiuixTopBarBackdrop
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * Trailing overflow actions for the thread screen: copy link and share.
 *
 * The menu follows the ambient Miuix style: OS4 gets [GlassIconDropdownMenu] (the same glass
 * popup the settings screens use), OS3 keeps the classic [WindowListPopup]. Previously this
 * always used the classic popup, which is why it looked wrong on an OS4 bar.
 */
@Composable
internal fun ThreadActions(
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchorRect = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val backdrop = LocalMiuixTopBarBackdrop.current
    val copyLabel = stringResource(R.string.thread_action_copy_link)
    val shareLabel = stringResource(R.string.thread_action_share)

    Box {
        MiuixTopBarIconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = copyLabel,
            )
        }

        if (AppTheme.useMiuixOs4) {
            // OS4: same glass popup the settings screens use, with the two actions as items.
            GlassPopup(
                show = expanded,
                onDismissRequest = { expanded = false },
                anchorBounds = anchorRect.value,
                backdrop = backdrop,
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

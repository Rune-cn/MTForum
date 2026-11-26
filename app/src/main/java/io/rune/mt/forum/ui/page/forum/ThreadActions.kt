// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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

/** One entry of the thread overflow menu. */
internal data class ThreadActionItem(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * Open state of the thread overflow menu, hoisted out of the top bar.
 *
 * `GlassPopup` is an *inline* overlay, not a popup window: it emits a full-size dismiss layer
 * plus a `Layout` that places the panel from window coordinates, and `placeGlassPopup` clamps
 * that panel into `intersect(window, host)`. Composed inside the top bar's `actions` cell it was
 * measured against that cell instead of the page, so the panel was pushed off its anchor, the
 * dismiss layer only covered the cell, and the oversized child distorted the bar itself.
 *
 * Keeping the flag and the anchor bounds here lets [ThreadActions] stay in the top bar while
 * [ThreadActionsGlassMenu] is composed at the page root.
 */
@Stable
internal class ThreadActionsState {
    /** Whether the overflow menu is open. */
    var expanded by mutableStateOf(false)

    /** Window-space bounds of the icon button, refreshed on every layout pass. */
    var anchorBounds by mutableStateOf(Rect.Zero)
}

/** Remembers a [ThreadActionsState] for one thread screen. */
@Composable
internal fun rememberThreadActionsState(): ThreadActionsState = remember { ThreadActionsState() }

/**
 * Trailing overflow button for the thread screen (favourite / like / follow author / copy / share).
 *
 * This only draws the three-dot button and publishes its window bounds; the menu itself is
 * composed by [ThreadActionsGlassMenu] in the page's root `BoxScope`. Two placement bugs lived
 * here:
 *
 *  - The OS3 `WindowListPopup` used the default position provider with `Align.Start`, which
 *    left-aligns the menu with the button; on a right-edge button the menu then overflowed
 *    off-screen and got clamped, which reads as "错位". `ContextMenuPositionProvider` + `TopEnd`
 *    right-aligns it properly. It is a real window, so it stays next to the button.
 *  - The OS4 glass menu was composed here, inside the actions cell. See [ThreadActionsState].
 */
@Composable
internal fun ThreadActions(
    items: List<ThreadActionItem>,
    state: ThreadActionsState,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    // The box wraps just the button so the OS3 window popup — which anchors to the layout node
    // it is composed in — keeps anchoring to the button rather than to the whole actions row.
    // Under OS4 this box holds nothing but the button, so the page-root menu is unaffected.
    Box {
        MiuixTopBarIconButton(
            onClick = { state.expanded = true },
            modifier = modifier.onGloballyPositioned { state.anchorBounds = it.boundsInWindow() },
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.thread_action_more),
            )
        }

        if (!AppTheme.useMiuixOs4) {
            WindowListPopup(
                show = state.expanded,
                // Right-aligned context menu: matches a top-bar overflow button.
                popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                alignment = PopupPositionProvider.Align.TopEnd,
                onDismissRequest = { state.expanded = false },
            ) {
                ListPopupColumn {
                    items.forEachIndexed { index, item ->
                        DropdownImpl(
                            text = item.label,
                            optionSize = items.size,
                            isSelected = false,
                            index = index,
                            onSelectedIndexChange = {
                                state.expanded = false
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The OS4 glass overflow menu, anchored to the top bar button through [state].
 *
 * Must be composed as a child of the page's root `Box(Modifier.fillMaxSize())`, after the page
 * content, so the panel is measured against the whole page rather than the button's cell — the
 * component's own KDoc asks for exactly that. Emits nothing under OS3, where [ThreadActions]
 * opens a real [WindowListPopup] window instead.
 */
@Composable
internal fun BoxScope.ThreadActionsGlassMenu(
    items: List<ThreadActionItem>,
    state: ThreadActionsState,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty() || !AppTheme.useMiuixOs4) return

    GlassPopup(
        show = state.expanded,
        onDismissRequest = { state.expanded = false },
        anchorBounds = state.anchorBounds,
        // Intentionally null: sampling the page layer from a window-level overlay is a
        // different render cycle (see docs/HANDOVER_CN.md); the opaque fallback fill keeps the
        // panel readable and retains the bloom stroke.
        backdrop = null,
        modifier = modifier,
        visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(AppTheme.isDark)),
    ) {
        items.forEach { item ->
            GlassPopupItem(
                text = item.label,
                onClick = {
                    state.expanded = false
                    item.onClick()
                },
            )
        }
    }
}

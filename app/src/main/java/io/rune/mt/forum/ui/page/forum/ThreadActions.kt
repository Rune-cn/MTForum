// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * One entry of the thread overflow menu.
 *
 * [icon] is consumed by the OS4 `GlassPopupItem`, which has its own leading-icon slot
 * (`icon: ImageVector?`), so the glass row keeps the library's native padding, 24dp icon,
 * press effect and text style instead of being hand-built inside the panel. The OS3
 * `DropdownImpl` row is text-only by Miuix's classic convention and ignores the icon.
 */
internal data class ThreadActionItem(
    val label: String,
    val icon: ImageVector,
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
 * Dismisses the open overflow menu on the system back gesture instead of popping the thread.
 *
 * Must be called from the page root. The callback exists in the activity's
 * `OnBackPressedDispatcher` at all times, but `enabled` follows [ThreadActionsState.expanded]:
 *
 *  - while the menu is shut the callback is disabled, so the dispatcher never selects it and
 *    `NavDisplay`'s predictive-back gesture driver remains the only active back handler — no
 *    gesture is stolen from navigation;
 *  - while the menu is open the callback is enabled. It was registered after the nav-level one
 *    (the nav stack composes above the page), so the dispatcher prefers it: a back press /
 *    committed gesture closes the menu and is consumed, so the thread is not popped.
 */
@Composable
internal fun ThreadActionsBackHandler(state: ThreadActionsState) {
    BackHandler(enabled = state.expanded) { state.expanded = false }
}

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
 *
 * Each row is the library's own [GlassPopupItem] with its `icon` slot filled, so icon/tint,
 * 24dp icon size, 8dp icon-to-label gap, 20dp horizontal padding, press highlight and text style
 * all come from `GlassPopupDefaults` rather than from a bare text column. A separator before the
 * last row keeps the system-share row apart from the in-app rows (favourite / like / follow /
 * copy link), the way Miuix's own dropdown menus draw section breaks.
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
        items.forEachIndexed { index, item ->
            if (index == items.lastIndex) {
                // Same recipe as Miuix's (private) GlassMenuDivider: the glass panel's divider
                // paddings and thickness with the theme divider color.
                HorizontalDivider(
                    modifier = Modifier.padding(
                        horizontal = GlassPopupDefaults.DividerPaddingHorizontal,
                        vertical = GlassPopupDefaults.DividerPaddingVertical,
                    ),
                    thickness = GlassPopupDefaults.DividerThickness,
                )
            }
            GlassPopupItem(
                text = item.label,
                onClick = {
                    state.expanded = false
                    item.onClick()
                },
                // The library row is 44dp tall; raising the outer minimum to 48dp extends the
                // full-width selectable area to the platform touch-target minimum while the
                // panel padding keeps the visual row height unchanged.
                modifier = Modifier.heightIn(min = 48.dp),
                icon = item.icon,
            )
        }
    }
}

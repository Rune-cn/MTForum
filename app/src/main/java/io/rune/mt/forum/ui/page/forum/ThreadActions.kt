// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.R
import io.rune.mt.forum.core.safemode.SafeMode
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixComponentScope
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixTopBarIconButton
import io.rune.mt.forum.ui.page.miuix.widgets.currentMiuixComponentScope
import io.rune.mt.forum.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler
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

    /**
     * Predictive-back drag progress: 0 = settled open, 1 = fully dragged towards dismissal.
     *
     * Fed by the system gesture through [ThreadActionsBackHandler] while [expanded] is true and
     * consumed by [ThreadActionsGlassMenu], so the panel scales/fades with the finger instead of
     * disappearing at commit. Reset to 0 whenever the menu closes.
     */
    var dismissProgress by mutableFloatStateOf(0f)

    /** Window-space bounds of the icon button, refreshed on every layout pass. */
    var anchorBounds by mutableStateOf(Rect.Zero)

    /** Closes the menu and clears any in-flight gesture transform. */
    fun close() {
        expanded = false
        dismissProgress = 0f
    }
}

/** Remembers a [ThreadActionsState] for one thread screen. */
@Composable
internal fun rememberThreadActionsState(): ThreadActionsState = remember { ThreadActionsState() }

/** Panel scale reduction at full predictive-back drag. */
internal const val MENU_DISMISS_SCALE_DELTA = 0.08f

/** Panel alpha reduction at full predictive-back drag. */
internal const val MENU_DISMISS_ALPHA_DELTA = 0.9f

/** Outward shift (dp) at full predictive-back drag, towards the swipe edge. */
internal const val MENU_DISMISS_TRANSLATION_DP = 16f

/** Transform applied to the glass panel for one predictive-back progress value. */
internal data class ThreadMenuDismissVisuals(
    val scale: Float,
    val alpha: Float,
    val translationDp: Float,
)

/**
 * Maps predictive-back progress (0..1, clamped) to the menu panel transform.
 *
 * Pure so the gesture mapping can be asserted without driving a real touch gesture: the panel
 * must be untouched at rest and fully faded at commit, with no overshoot in between.
 */
internal fun threadMenuDismissVisuals(progress: Float): ThreadMenuDismissVisuals {
    val p = progress.coerceIn(0f, 1f)
    return ThreadMenuDismissVisuals(
        scale = 1f - MENU_DISMISS_SCALE_DELTA * p,
        alpha = 1f - MENU_DISMISS_ALPHA_DELTA * p,
        translationDp = MENU_DISMISS_TRANSLATION_DP * p,
    )
}

/**
 * Dismisses the open overflow menu *with* the predictive-back gesture instead of popping the
 * thread.
 *
 * This is the same `PredictiveBackHandler` the navigation layer uses (miuix-nav's gesture
 * variant), so the drag streams `NavBackEvent.progress` into [ThreadActionsState.dismissProgress]
 * and [ThreadActionsGlassMenu] renders the preview. Registration rules:
 *
 *  - `enabled = state.expanded`: while the menu is shut the handler is disabled, so `NavDisplay`
 *    keeps sole ownership of the gesture (no stolen navigation preview);
 *  - while the menu is open it is enabled. It is composed **after** [ThreadActionsGlassMenu] in
 *    the page root, and the navigation-event dispatcher prioritises the most recently composed
 *    enabled handler — so our preview wins over the glass popup's own plain dismiss handler.
 *    Composing it before the menu would let the popup swallow the gesture (menu closes on
 *    commit, but with no preview), which is exactly the bug this fixes.
 *
 * OS3's `WindowListPopup` is a real window and cannot be predicted; it keeps its own
 * dismiss-on-back behaviour and this handler is a no-op there.
 */
@Composable
internal fun ThreadActionsBackHandler(state: ThreadActionsState) {
    PredictiveBackHandler(
        enabled = state.expanded,
        onProgress = { events ->
            try {
                events.collect { event -> state.dismissProgress = event.progress }
            } catch (cancel: CancellationException) {
                state.dismissProgress = 0f
                throw cancel
            }
        },
        onCommit = { state.close() },
        onCancel = { state.dismissProgress = 0f },
    )
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
                onDismissRequest = { state.close() },
            ) {
                ListPopupColumn {
                    items.forEachIndexed { index, item ->
                        DropdownImpl(
                            text = item.label,
                            optionSize = items.size,
                            isSelected = false,
                            index = index,
                            onSelectedIndexChange = {
                                state.close()
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Which recorded layer the thread overflow menu samples. */
internal enum class ThreadMenuBackdropSource {
    /** The page-content surface, which the inline popup sits outside of. */
    Content,

    /** Nothing is sampled; the glass panel uses its opaque fallback fill. */
    None,
}

/** Chosen sampling surface plus its provenance, so the choice can be asserted in tests. */
internal data class ThreadMenuBackdrop(
    val source: ThreadMenuBackdropSource,
    val layer: LayerBackdrop?,
)

/**
 * Picks the layer [ThreadActionsGlassMenu] may sample.
 *
 * The page-level `MiuixComponentScope.pageBackdrop` is deliberately **not** a candidate: the
 * app-wide component host records it around the whole navigation tree, and this inline popup is
 * composed inside that tree, so sampling it would make the panel source a layer containing the
 * panel — a RenderNode cycle that hwui walks until the render thread overflows its stack
 * (`computeTransformImpl`, `SIGSEGV` in `RenderThread`; `docs/HANDOVER_CN.md` §11 / §15.8).
 * [MiuixComponentScope.contentBackdrop] is the page-content surface, which the popup is not
 * part of, so it is safe to sample (the same class of surface the OS4 top app bar samples).
 *
 * [SafeMode.blurDisabled] is honoured explicitly: after a crash dump this session must build no
 * sampling path at all, and the menu then falls back to the library's opaque fill. The latch is
 * plain state set in `Application.onCreate`, not part of the composition, so it is read here
 * rather than observed.
 */
internal fun threadMenuBackdrop(
    scope: MiuixComponentScope,
    blurDisabled: Boolean = SafeMode.blurDisabled,
): ThreadMenuBackdrop = when {
    blurDisabled -> ThreadMenuBackdrop(ThreadMenuBackdropSource.None, null)
    scope.contentBackdrop == null -> ThreadMenuBackdrop(ThreadMenuBackdropSource.None, null)
    else -> ThreadMenuBackdrop(ThreadMenuBackdropSource.Content, scope.contentBackdrop)
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
 *
 * ## Which layer may be sampled
 *
 * The popup is an *inline* overlay: it is composed inside the navigation tree, which the
 * app-wide host records into `MiuixComponentScope.pageBackdrop` (`SettingsActivity` applies
 * `pageBackdropModifier` to the root `Box` around `AppNavContainer`). Passing that layer to
 * `GlassPopup` makes the panel sample a layer that **contains the panel itself**: the panel's
 * RenderNode becomes its own ancestor, hwui walks the cycle in
 * `computeTransformImpl(DirtyStack const*, Matrix4*)` until the render thread overflows its
 * stack, and the process dies with `SIGSEGV` in `RenderThread` (documented in
 * `docs/HANDOVER_CN.md` §11 and §15.8; it reproduced on Android 16 / vivo with this menu).
 *
 * So the menu samples the page-content layer instead — the surface recorded around the page
 * content, which the popup sits *outside* of. The OS4 top app bar samples the same class of
 * surface for the same reason and does not crash. [threadMenuBackdrop] owns that choice, and
 * also honours the blur kill-switch from [SafeMode] (see its KDoc).
 */
@Composable
internal fun BoxScope.ThreadActionsGlassMenu(
    items: List<ThreadActionItem>,
    state: ThreadActionsState,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty() || !AppTheme.useMiuixOs4) return

    val componentScope = currentMiuixComponentScope()
    // The popup must never sample the page-level layer that contains it (hwui RenderNode
    // cycle). threadMenuBackdrop() picks the page-content surface and returns null when the
    // SafeMode crash latch is on, so the panel falls back to its opaque fill instead.
    val menuBackdrop = threadMenuBackdrop(componentScope)

    // While a predictive-back gesture is in flight the panel follows the finger: scale down,
    // fade out and shift towards the swipe edge. At rest (progress 0) these are exactly the
    // identity transform, so the open menu is pixel-identical to before.
    val visuals = threadMenuDismissVisuals(state.dismissProgress)
    val directionSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val panelModifier = modifier.graphicsLayer {
        scaleX = visuals.scale
        scaleY = visuals.scale
        alpha = visuals.alpha
        translationX = directionSign * visuals.translationDp.dp.toPx()
    }

    GlassPopup(
        show = state.expanded,
        onDismissRequest = { state.close() },
        anchorBounds = state.anchorBounds,
        backdrop = menuBackdrop.layer,
        modifier = panelModifier,
        // Same glass recipe as GlassDropdownPreference, and the same popup defaults the
        // dropdown passes (24dp corner radius, 8dp vertical content padding, 200/288/416dp
        // sizing with a 12dp safe margin), so the two menus read as one design language.
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
                    state.close()
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

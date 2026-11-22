// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference

/*
 * Single entry points for selection rows.
 *
 * Every selection row in the app must go through this file instead of calling a Miuix
 * preference directly. That is what makes the OS3/OS4 switch global: the style is resolved
 * once from the ambient theme, so a new screen gets the correct presentation without the
 * caller knowing which style is active, and nobody has to remember to update each page when
 * the OS4 style grows.
 *
 * The glass dropdown only supports plain text options. Items carrying an icon, summary or
 * children cannot be represented by it, so those fall back to the classic spinner instead of
 * silently dropping that content.
 */

private fun List<DropdownItem>.arePlainOptions(): Boolean = all {
    it.enabled && it.icon == null && it.summary == null && it.children.isNullOrEmpty()
}

/**
 * Selection row over a list of plain labels, e.g. the language picker.
 *
 * This is the `List<String>` flavour: the glass dropdown accepts it directly, so no
 * [DropdownItem] wrapper is needed.
 */
@Composable
internal fun MiuixDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
) {
    val scope = LocalMiuixComponentScope.current
    val useOs4 = scope.useOs4 || AppTheme.useMiuixOs4
    if (useOs4) {
        GlassDropdownPreference(
            onSelectedIndexChange = onSelectedIndexChange,
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            backdrop = scope.pageBackdrop,
            visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(AppTheme.isDark)),
            modifier = modifier,
            enabled = enabled,
            showValue = showValue,
            insideMargin = insideMargin,
            renderInRootScaffold = scope.popupInRootScaffold,
            summary = summary,
        )
    } else {
        WindowDropdownPreference(
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
            showValue = showValue,
            insideMargin = insideMargin,
            onSelectedIndexChange = onSelectedIndexChange,
        )
    }
}

/**
 * Selection row over rich [DropdownItem]s, e.g. the theme and palette pickers.
 *
 * Falls back to the classic spinner whenever an option carries content the glass dropdown
 * cannot draw, so callers never lose an icon or summary in OS4.
 */
@Composable
internal fun MiuixSpinnerPreference(
    items: List<DropdownItem>,
    selectedIndex: Int,
    title: String,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
) {
    val scope = LocalMiuixComponentScope.current
    val useOs4 = scope.useOs4 || AppTheme.useMiuixOs4
    if (useOs4 && items.arePlainOptions()) {
        GlassDropdownPreference(
            onSelectedIndexChange = { index ->
                onSelectedIndexChange(index)
                items[index].onClick?.invoke()
            },
            items = items.map { it.text },
            selectedIndex = selectedIndex,
            title = title,
            backdrop = scope.pageBackdrop,
            visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(AppTheme.isDark)),
            modifier = modifier,
            enabled = enabled,
            showValue = showValue,
            insideMargin = insideMargin,
            renderInRootScaffold = scope.popupInRootScaffold,
            summary = summary,
        )
    } else {
        WindowSpinnerPreference(
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
            showValue = showValue,
            insideMargin = insideMargin,
            onSelectedIndexChange = onSelectedIndexChange,
        )
    }
}

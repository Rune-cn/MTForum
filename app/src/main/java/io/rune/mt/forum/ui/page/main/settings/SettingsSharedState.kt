// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings

/**
 * Data class to hold the state of the settings page.
 *
 * [lastMainPageIndex] is the tab the shell's pager should restore on the next composition.
 * [pendingMainPageIndex] is a one-shot request (a home deep link) that must win over that restored
 * tab: the shell animates to it on arrival and then clears it, so later recompositions fall back to
 * [lastMainPageIndex] again.
 */
data class SettingsSharedState(
    val lastMainPageIndex: Int = 0,
    val pendingMainPageIndex: Int? = null,
)
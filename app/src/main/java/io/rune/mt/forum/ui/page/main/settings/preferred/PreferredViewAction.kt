// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred

sealed interface PreferredViewAction {
    data class ChangeInternetAccess(val enabled: Boolean) : PreferredViewAction

    data object CheckUpdate : PreferredViewAction

    data class SetAutoCheckUpdateOnStart(val enabled: Boolean) : PreferredViewAction

    data class SetUseBlur(val enabled: Boolean) : PreferredViewAction
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred.about

sealed interface AboutEvent {
    data object ShowUpdateLoading : AboutEvent
    data object HideUpdateLoading : AboutEvent
    data object ShowUpdateSheet : AboutEvent
    data class ShowMessage(@androidx.annotation.StringRes val messageResId: Int) : AboutEvent
}
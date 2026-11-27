// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred.about

import androidx.annotation.StringRes

/**
 * A user-facing reaction to an update check.
 *
 * The loading phase is **not** an event any more: it is derived from
 * `UpdateRepository.checkState`. Only terminal outcomes are delivered as events, so there is no
 * pair of messages that can get out of sync (the old `ShowUpdateLoading`/`HideUpdateLoading` pair
 * is what left 「检查更新」 on screen forever when `Hide` was never reached).
 */
sealed interface AboutEvent {
    /** A one-line result, rendered by the page's dialog. [args] feed the format string. */
    data class ShowMessage(
        @param:StringRes val messageResId: Int,
        val args: List<String> = emptyList(),
    ) : AboutEvent

    /** A newer version was found: open the update sheet (data read from state.updateInfo). */
    data object ShowUpdateSheet : AboutEvent
}
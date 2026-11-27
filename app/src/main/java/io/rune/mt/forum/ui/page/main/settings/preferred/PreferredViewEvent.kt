// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred

import androidx.annotation.StringRes

/**
 * A user-facing reaction to an update check on the settings page.
 *
 * Loading is derived from `UpdateRepository.checkState` now, not from a `ShowUpdateLoading` /
 * `HideUpdateLoading` pair: an unreachable `Hide` used to leave the blocking 「检查更新」 dialog
 * up forever.
 */
sealed interface PreferredViewEvent {
    /** A one-line result, rendered by the page's dialog. [args] feed the format string. */
    data class ShowMessage(
        @param:StringRes val messageResId: Int,
        val args: List<String> = emptyList(),
    ) : PreferredViewEvent

    /** New version found: open UpdateSheet (data read from state.updateInfo). */
    data object ShowUpdateSheet : PreferredViewEvent
}
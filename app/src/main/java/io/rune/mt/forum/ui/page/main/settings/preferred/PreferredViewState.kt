// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred

import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.UpdateCheckState
import io.rune.mt.forum.domain.updater.model.UpdateInfo

data class PreferredViewState(
    val allowInternetAccess: Boolean = true,
    val hasUpdate: Boolean = false,
    val remoteVersion: String = "",
    val autoCheckUpdateOnStart: Boolean = false,
    val useBlur: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    /** Live checker phase; the loading UI is derived from it (never from loose events). */
    val checkState: UpdateCheckState = UpdateCheckState.Idle,
) {
    /** True while a check is running; the blocking dialog follows this and cannot get stuck. */
    val isChecking: Boolean get() = checkState is UpdateCheckState.Checking

    /** The last settled outcome, if any. */
    val lastCheck: UpdateCheckResult? get() = (checkState as? UpdateCheckState.Settled)?.result
}
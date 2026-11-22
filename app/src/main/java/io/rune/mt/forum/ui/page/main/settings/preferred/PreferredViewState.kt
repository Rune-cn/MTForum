// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred

import io.rune.mt.forum.domain.updater.model.UpdateInfo

data class PreferredViewState(
    val allowInternetAccess: Boolean = true,
    val hasUpdate: Boolean = false,
    val remoteVersion: String = "",
    val autoCheckUpdateOnStart: Boolean = false,
    val useBlur: Boolean = false,
    val updateInfo: UpdateInfo? = null,
)
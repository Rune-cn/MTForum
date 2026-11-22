// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.settings.provider

import io.rune.mt.forum.domain.settings.model.preferences.ThemeState
import kotlinx.coroutines.flow.StateFlow

interface ThemeStateProvider {
    val themeStateFlow: StateFlow<ThemeState>
}

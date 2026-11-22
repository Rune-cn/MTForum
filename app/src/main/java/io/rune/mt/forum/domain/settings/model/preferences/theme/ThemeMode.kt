// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.settings.model.preferences.theme

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
    ;

    companion object {
        fun fromValueOrDefault(value: String) = entries.find { it.name == value } ?: SYSTEM
    }
}

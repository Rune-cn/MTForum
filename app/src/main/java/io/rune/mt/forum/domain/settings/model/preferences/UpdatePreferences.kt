// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.settings.model.preferences

/**
 * Define GitHub Update Channels used by the MT 论坛 client
 */
enum class GithubUpdateChannel {
    OFFICIAL,
    PROXY_7ED,
    CUSTOM,
    ;

    companion object {
        fun fromValueOrDefault(value: String) = entries.find { it.name == value } ?: OFFICIAL
    }
}

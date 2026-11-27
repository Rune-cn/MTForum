// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.updater.repository

import io.rune.mt.forum.core.device.model.Level
import io.rune.mt.forum.core.env.AppConfig

/**
 * The build facts an update check depends on.
 *
 * Extracted from `AppConfig`/`BuildConfig` so the check pipeline can be exercised with the real
 * branch logic in a unit test: the debug unit-test variant has `BuildConfig.DEBUG = true`, which
 * would otherwise make [OnlineUpdatePolicy.canCheckUpdates] refuse every test check before it
 * reaches the feed. Production builds get [fromBuild].
 */
internal data class UpdateEnvironment(
    val isDebug: Boolean,
    val level: Level,
    val currentVersion: String,
    /** GitHub REST base; overridable so a test can point at a local fixture server. */
    val apiBaseUrl: String = "https://api.github.com",
    /**
     * Upper bound for one whole check, in milliseconds.
     *
     * `connectTimeout`/`readTimeout` do not cover DNS resolution or a server that trickles bytes,
     * and the check used to run on the same thread/mutex as the startup check — a stuck call
     * there made every later manual check wait forever while the UI kept 「检查更新」 on screen.
     */
    val deadlineMillis: Long = DEFAULT_DEADLINE_MILLIS,
) {
    companion object {
        const val DEFAULT_DEADLINE_MILLIS = 15_000L

        fun fromBuild(): UpdateEnvironment = UpdateEnvironment(
            isDebug = AppConfig.isDebug,
            level = AppConfig.LEVEL,
            currentVersion = AppConfig.VERSION_NAME,
        )
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.core.env

import io.rune.mt.forum.BuildConfig
import io.rune.mt.forum.core.device.model.Level

object AppConfig {
    val LEVEL: Level = when (BuildConfig.BUILD_LEVEL) {
        1 -> Level.PREVIEW
        2 -> Level.STABLE
        else -> Level.UNSTABLE
    }

    val isDebug: Boolean = BuildConfig.DEBUG
    const val VERSION_NAME: String = BuildConfig.VERSION_NAME
    const val VERSION_CODE: Int = BuildConfig.VERSION_CODE

    val isLogEnabled = LEVEL == Level.PREVIEW || LEVEL == Level.UNSTABLE || isDebug
}

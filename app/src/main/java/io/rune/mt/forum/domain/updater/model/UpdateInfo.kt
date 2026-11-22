// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.updater.model

data class UpdateInfo(
    val hasUpdate: Boolean,
    val remoteVersion: String,
    val releaseUrl: String,
    val downloadUrl: String,
    /** Markdown release notes of the remote release, null/blank when absent. */
    val updateLog: String? = null,
)

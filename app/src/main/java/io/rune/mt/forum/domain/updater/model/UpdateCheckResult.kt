// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.updater.model

/**
 * The outcome of exactly one update check.
 *
 * Every path of `checkUpdate` ends in one of these three, so the settings screen always has a
 * definite answer to render (up to date / update available / nothing was checked / failed with a
 * reason). A nullable `UpdateInfo` was not enough: "no result" covered a policy skip, a network
 * failure, a timeout and a parse error at once, and the caller could only show a spinner.
 */
sealed interface UpdateCheckResult {

    /** When this check ran (epoch millis). 0 when no network attempt happened (a policy skip). */
    val checkedAt: Long

    /** The release feed was read and compared with the installed version. */
    data class Checked(
        val info: UpdateInfo,
        override val checkedAt: Long,
    ) : UpdateCheckResult

    /** The check was not attempted, and why. Not a failure: there is nothing to report. */
    data class Skipped(
        val reason: SkipReason,
        override val checkedAt: Long = 0L,
    ) : UpdateCheckResult

    /** The check was attempted and could not produce an answer; [reason] is user-facing. */
    data class Failed(
        val reason: String,
        override val checkedAt: Long,
    ) : UpdateCheckResult

    /** Why an update check was refused before any network request. */
    enum class SkipReason {
        /** The user turned 联网权限 off. */
        INTERNET_DISABLED,

        /** Debug builds are not update-checked. */
        DEBUG_BUILD,

        /** The unstable channel has no release feed. */
        UNSTABLE_CHANNEL,
    }
}

/** True when a newer version than the installed one was found. */
val UpdateCheckResult.hasUpdate: Boolean
    get() = this is UpdateCheckResult.Checked && info.hasUpdate

/** The version the check compared against, when the feed was read. */
val UpdateCheckResult.checkedVersion: String?
    get() = (this as? UpdateCheckResult.Checked)?.info?.remoteVersion

/**
 * The live phase of the update checker.
 *
 * Exposed as a flow so the loading UI is **derived** from state instead of toggled by two loose
 * events: a `ShowUpdateLoading` that never got its `HideUpdateLoading` (early return, exception,
 * cancellation, an in-flight call holding the mutex) used to leave 「检查更新」 on screen forever.
 * [Checking] can only be replaced by [Settled] or [Idle], and the repository always does that.
 */
sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState

    data object Checking : UpdateCheckState

    data class Settled(val result: UpdateCheckResult) : UpdateCheckState
}
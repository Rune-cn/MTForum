// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.UpdateCheckState
import java.text.DateFormat
import java.util.Date

/**
 * How the update checker's outcome is presented, shared by the 设置 and 关于 pages.
 *
 * Extracted so both screens tell the same story and so the wording lives in one place: the last
 * check's time, the version that was actually compared, the failure reason, or why the check was
 * skipped. Previously both only knew "spinner or nothing".
 */
sealed interface UpdateCheckFeedback {
    /** Open the update sheet. */
    data object ShowUpdateSheet : UpdateCheckFeedback

    /** Show [resId] formatted with [args]. */
    data class Message(val resId: Int, val args: List<String> = emptyList()) : UpdateCheckFeedback
}

/** Maps a terminal result to what the page should show. */
fun updateCheckFeedback(result: UpdateCheckResult): UpdateCheckFeedback = when (result) {
    is UpdateCheckResult.Checked -> if (result.info.hasUpdate) {
        UpdateCheckFeedback.ShowUpdateSheet
    } else {
        UpdateCheckFeedback.Message(
            R.string.update_up_to_date_format,
            listOf(result.info.remoteVersion),
        )
    }

    is UpdateCheckResult.Skipped -> UpdateCheckFeedback.Message(skipReasonRes(result.reason))

    is UpdateCheckResult.Failed -> UpdateCheckFeedback.Message(
        R.string.update_check_failed_format,
        listOf(result.reason),
    )
}

/** Resource explaining a policy skip. */
fun skipReasonRes(reason: UpdateCheckResult.SkipReason): Int = when (reason) {
    UpdateCheckResult.SkipReason.INTERNET_DISABLED -> R.string.update_check_skip_offline
    UpdateCheckResult.SkipReason.DEBUG_BUILD -> R.string.update_check_skip_debug
    UpdateCheckResult.SkipReason.UNSTABLE_CHANNEL -> R.string.update_check_skip_channel
}

/**
 * One-line description of the checker for a settings row, or null when nothing is known yet.
 *
 * Shows the checking state, the last comparison (with its time and the version that was checked),
 * a failure reason, or the skip reason — never a bare spinner.
 */
@Composable
fun updateCheckDescription(state: UpdateCheckState): String? {
    if (state is UpdateCheckState.Checking) return stringResource(R.string.checking_update)
    val result = (state as? UpdateCheckState.Settled)?.result ?: return null
    return when (result) {
        is UpdateCheckResult.Checked -> if (result.info.hasUpdate) {
            stringResource(R.string.update_available, result.info.remoteVersion)
        } else {
            stringResource(
                R.string.update_last_checked_format,
                formatCheckedAt(result.checkedAt),
                result.info.remoteVersion,
            )
        }

        is UpdateCheckResult.Skipped -> stringResource(skipReasonRes(result.reason))

        is UpdateCheckResult.Failed -> stringResource(
            R.string.update_check_failed_format,
            result.reason,
        )
    }
}

/** Locale-aware short date+time for the last check. */
fun formatCheckedAt(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.updater.repository

import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.UpdateCheckState
import io.rune.mt.forum.domain.updater.model.UpdateInfo
import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow

interface UpdateRepository {
    /**
     * A StateFlow emitting the latest update information.
     */
    val updateInfoFlow: StateFlow<UpdateInfo?>

    /**
     * The live phase of the checker (idle / checking / settled), so the UI can derive its loading
     * state instead of tracking two events. The checker leaves [UpdateCheckState.Checking] on
     * every path, including cancellation and timeouts.
     */
    val checkState: StateFlow<UpdateCheckState>

    /**
     * Checks for the latest application update.
     * @param force Whether to force the update check.
     *
     * @return a definite [UpdateCheckResult]: a compared version, a policy skip, or a failure with
     *   the reason. Implementations do not throw for network/policy problems; only cancellation
     *   propagates.
     */
    suspend fun checkUpdate(force: Boolean = false): UpdateCheckResult

    /**
     * Opens a stream to download the update package.
     * @return a Pair containing the InputStream and the total content length (if known).
     */
    suspend fun downloadUpdate(url: String): Pair<InputStream, Long>?
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.core.safemode

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * Crash-loop guard for the blur path.
 *
 * The blur/glass path records a `LayerBackdrop` and samples it through a `RenderEffect`. On
 * Android 16 that showed up as a hard render-thread crash (`SIGSEGV`/stack overflow inside hwui's
 * `computeTransformImpl`), which is fatal *before* any UI exists. If such a crash happened while
 * the user had blur enabled, the setting is persisted, so every later launch would crash too and
 * the user could never reach the switch to turn it off — an unrecoverable loop.
 *
 * To break it: when the previous run left a crash dump behind, this session renders without blur
 * and the decision is remembered in plain `SharedPreferences` (synchronous, available before the
 * DataStore flow emits). Re-enabling blur in theme settings clears the flag, so the user can
 * always choose to try again.
 */
object SafeMode {

    private const val PREFS = "safe_mode"
    private const val KEY_BLUR_OFF = "blur_disabled_after_crash"
    private const val CRASH_DIR = "crashes"
    private const val TAG = "SafeMode"

    @Volatile
    var blurDisabled: Boolean = false
        private set

    /** Call once from `Application.onCreate`, before any UI is composed. */
    fun evaluate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val crashDir = File(context.cacheDir, CRASH_DIR)
        val pending = crashDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (pending.isNotEmpty()) {
            prefs.edit().putBoolean(KEY_BLUR_OFF, true).apply()
            // Consume the dumps so one crash does not disable blur forever.
            pending.forEach { runCatching { it.delete() } }
            Timber.tag(TAG).e(
                "previous run crashed (%d dump(s)); blur disabled for this session",
                pending.size,
            )
        }

        blurDisabled = prefs.getBoolean(KEY_BLUR_OFF, false)
        if (blurDisabled) {
            Timber.tag(TAG).i("blur is disabled after an earlier crash")
        }
    }

    /** Called when the user explicitly turns blur on, so they can retry the effect. */
    fun onUserEnabledBlur(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_BLUR_OFF).apply()
        blurDisabled = false
        Timber.tag(TAG).i("blur re-enabled by the user")
    }
}

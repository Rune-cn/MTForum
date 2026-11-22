// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.app.Application

/**
 * Empty Application for Robolectric.
 *
 * The production [App] does three things that a JVM test cannot host — HiddenApiBypass,
 * MonetCompat and Timber planting — none of which the UI assertions care about. Switching the
 * test application keeps the real widget tree under test while skipping that startup work.
 */
class TestApp : Application()

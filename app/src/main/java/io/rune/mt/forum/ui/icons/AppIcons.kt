// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.twotone.ColorLens
import io.rune.mt.forum.R

/**
 * Material icon set used by the Miuix UI family.
 *
 * This shell ships only the icons its screens actually reference. The upstream installer icon
 * set was removed together with the installation feature, so no privileged, authorizer or
 * installation-mode glyphs remain to be mistaken for live functionality.
 */
object AppIcons {
    // --- Status icons ---
    val Active = Icons.Rounded.CheckCircleOutline

    // --- Navigation icons ---
    val DragHandle: Int = R.drawable.ic_navigation_rearrange

    // --- Settings icons ---
    val Theme = Icons.TwoTone.ColorLens
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Type-safe navigation keys for Navigation.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object About : Route

    @Serializable
    data object Theme : Route

    @Serializable
    data object Login : Route

    /** The board index (版块宫格) plus the hot-thread preview. */
    @Serializable
    data object ForumHome : Route

    /**
     * A board listing. `name` travels with the key so the app bar can render the board title
     * before the listing request finishes.
     */
    @Serializable
    data class ForumList(val fid: Int, val name: String) : Route

    /** A thread. `title` travels with the key for the same reason. */
    @Serializable
    data class ForumThread(val tid: Int, val title: String) : Route
}
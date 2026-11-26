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

    /** One private-message conversation with [uid]. */
    @Serializable
    data class PrivateConversation(val uid: Int, val name: String) : Route

    /** Compose a new thread in board [fid] (版块列表页的「发帖」入口). */
    @Serializable
    data class NewThread(val fid: Int, val name: String) : Route

    /**
     * In-forum search. [keyword] pre-fills the field so a retry or a "search again" round trip
     * keeps the query; an empty value focuses the field instead of firing a request.
     */
    @Serializable
    data class ForumSearch(val keyword: String = "") : Route

    /**
     * One user's public profile (`home.php?mod=space&uid=`).
     *
     * [name] travels with the key so the app bar shows the nickname before the profile request
     * resolves, the same way [ForumList] carries its board name.
     */
    @Serializable
    data class UserProfile(val uid: Int, val name: String) : Route

    /** The signed-in user's favourited threads (`home.php?mod=space&do=favorite`). */
    @Serializable
    data object Favorites : Route

    /**
     * The site's 提醒 inbox (`home.php?mod=space&do=notice`): 系统提醒 / 回复我的 / 我的帖子 are
     * tabs inside the page, so the route carries no arguments.
     */
    @Serializable
    data object Notices : Route
}
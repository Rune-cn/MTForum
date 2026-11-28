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

    /**
     * 设置 → MCP 服务: the local, read-only MCP server (loopback endpoint, port, exposed tools).
     *
     * Carries no arguments: the switch and port live in the app settings DataStore, and the
     * controller reconciles the socket from there.
     */
    @Serializable
    data object Mcp : Route

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

    /**
     * Compose a new thread in board [fid] (版块列表页的「发帖」入口).
     *
     * [draftTid]/[draftPid] open an existing **site** draft for editing instead of a blank
     * composer; the 草稿 page passes them, and the composer then loads that draft's edit form so
     * 保存草稿 updates it rather than creating a second draft.
     */
    @Serializable
    data class NewThread(
        val fid: Int,
        val name: String,
        val draftTid: Int = 0,
        val draftPid: Long? = null,
    ) : Route

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
     * The signed-in user's **site** drafts
     * (`forum.php?mod=guide&view=my&type=thread&filter=save&fid=0`).
     *
     * These are the drafts the composer's 保存草稿 creates on the server, so the route carries no
     * arguments; a row opens [NewThread] with its tid.
     */
    @Serializable
    data object Drafts : Route

    /**
     * 浏览历史: the threads **this device** opened, most recent first.
     *
     * The list is local (DataStore, written by the thread page), so the route carries no
     * arguments and the page works signed in or out; a row opens [ForumThread].
     */
    @Serializable
    data object ThreadHistory : Route

    /**
     * The site's 提醒 inbox (`home.php?mod=space&do=notice`): 系统提醒 / 回复我的 / 我的帖子 are
     * tabs inside the page, so the route carries no arguments.
     */
    @Serializable
    data object Notices : Route

    /** 评论屏蔽: manage the local keyword/regex comment-filter rules. */
    @Serializable
    data object CommentFilter : Route

    /** 板块管理: reorder and hide boards on the home grid and the board pickers. */
    @Serializable
    data object BoardSettings : Route
}
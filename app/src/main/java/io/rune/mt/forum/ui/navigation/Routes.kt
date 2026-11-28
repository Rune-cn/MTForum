// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Type-safe navigation keys for Navigation.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 *
 * A key is also the entry's **content key** on NavDisplay, and content keys must be unique for
 * every back-stack entry (two entries with equal keys throw
 * `IllegalArgumentException: Duplicate contentKey on the back stack`). Destinations the user can
 * legitimately re-open therefore implement [RepeatableRoute], which stamps a per-entry instance id
 * into the key; `Navigator` is the only place that builds back-stack keys. Singleton destinations
 * must stay unique — `Navigator.push` reveals the open copy instead of stacking a second one.
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
     *
     * Repeatable: opening the same board again gets its own [instanceId]. Same for every route
     * below that implements [RepeatableRoute].
     */
    @Serializable
    data class ForumList(
        val fid: Int,
        val name: String,
        override val instanceId: Long = 0L,
    ) : Route, RepeatableRoute

    /** A thread. `title` travels with the key for the same reason. */
    @Serializable
    data class ForumThread(
        val tid: Int,
        val title: String,
        override val instanceId: Long = 0L,
    ) : Route, RepeatableRoute

    /** One private-message conversation with [uid]. */
    @Serializable
    data class PrivateConversation(
        val uid: Int,
        val name: String,
        override val instanceId: Long = 0L,
    ) : Route, RepeatableRoute

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
        override val instanceId: Long = 0L,
    ) : Route, RepeatableRoute

    /**
     * In-forum search. [keyword] pre-fills the field so a retry or a "search again" round trip
     * keeps the query; an empty value focuses the field instead of firing a request.
     */
    @Serializable
    data class ForumSearch(
        val keyword: String = "",
        override val instanceId: Long = 0L,
    ) : Route, RepeatableRoute

    /**
     * One user's public profile (`home.php?mod=space&uid=`).
     *
     * [name] travels with the key so the app bar shows the nickname before the profile request
     * resolves, the same way [ForumList] carries its board name.
     */
    @Serializable
    data class UserProfile(
        val uid: Int,
        val name: String,
        override val instanceId: Long = 0L,
    ) : Route, RepeatableRoute

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

    /**
     * 设置 → 插件: the plugin centre.
     *
     * Every plugin is a card here — the five built-ins (浏览历史 / 评论屏蔽 / 板块管理 / MCP 服务 /
     * 帖子缓存) plus whatever the remote catalogue offers and the user installed. The routes those
     * built-ins open stay exactly where they were; this page only becomes their entry point.
     */
    @Serializable
    data object Plugins : Route

    /**
     * 帖子缓存: the device-local copy of opened threads — switch, usage readout and 清空.
     *
     * Reachable from the 插件 card (built-in `cache`); it is a page of its own because it has state
     * (the switch) and a destructive action, which do not belong on a card row.
     */
    @Serializable
    data object ThreadCache : Route
}

/**
 * A [Route] the user may open more than once — a thread reached from a quote link, a board list,
 * 浏览历史 or a profile while an earlier copy of that page is still on the back stack.
 *
 * `NavDisplay` identifies every back-stack entry by its content key and rejects two entries that
 * resolve to the same one (`IllegalArgumentException: Duplicate contentKey on the back stack`), so
 * a repeatable page has to carry a distinguisher. [instanceId] is it: `0` for the first copy of a
 * destination, and a fresh (strictly larger) number for every copy pushed while another copy of
 * the same destination is still on the stack — see `Navigator.push`, the only place that builds
 * back-stack keys.
 *
 * Keeping the id **inside the key** (instead of in a side table) makes it part of the saved back
 * stack, so it survives process death, and keeps `contentKey.toString()` — the `rememberSaveable`
 * namespace — derived from values instead of identity.
 *
 * Only mark routes the user can genuinely re-open: singleton destinations (`Route.Main`,
 * `Route.Notices`, `Route.Favorites`, …) must stay unique, and `Navigator.push` reveals the open
 * copy instead of stacking a second one.
 */
interface RepeatableRoute {
    /** Per-back-stack instance discriminator; `0` is the first (and only) copy of the page. */
    val instanceId: Long
}

/** Per-back-stack instance discriminator of [this] key: `0` when the destination cannot repeat. */
val Route.navigationInstanceId: Long
    get() = (this as? RepeatableRoute)?.instanceId ?: 0L

/** True when the user can legitimately open another copy of this destination. */
val Route.repeatsOnBackStack: Boolean
    get() = this is RepeatableRoute

/**
 * A copy of this route stamped with [instance]; destinations that cannot repeat are returned
 * unchanged. `copy(instanceId = …)` keeps every other field, so the page still renders the same
 * tid/uid it was opened with.
 */
fun Route.withNavigationInstance(instance: Long): Route = when (this) {
    is Route.ForumList -> copy(instanceId = instance)
    is Route.ForumThread -> copy(instanceId = instance)
    is Route.NewThread -> copy(instanceId = instance)
    is Route.ForumSearch -> copy(instanceId = instance)
    is Route.UserProfile -> copy(instanceId = instance)
    is Route.PrivateConversation -> copy(instanceId = instance)
    else -> this
}

/**
 * Whether [other] is the same page as this key, ignoring [RepeatableRoute.instanceId] and the
 * fields that only affect what the page *shows* (a cached title/name).
 *
 * This is the "already open" test `Navigator` uses to swallow a double tap and to reveal a
 * singleton page instead of stacking a second copy of it, so keep it aligned with the identity a
 * user perceives: a thread is its `tid`, a profile its `uid`, a board its `fid`.
 */
fun NavKey.isSameDestinationAs(other: NavKey?): Boolean {
    if (other == null) return false
    if (this !is Route || other !is Route) return this == other
    return when (this) {
        is Route.ForumList -> other is Route.ForumList && other.fid == fid
        is Route.ForumThread -> other is Route.ForumThread && other.tid == tid
        is Route.NewThread -> other is Route.NewThread &&
            other.fid == fid &&
            other.draftTid == draftTid &&
            other.draftPid == draftPid
        is Route.ForumSearch -> other is Route.ForumSearch && other.keyword == keyword
        is Route.UserProfile -> other is Route.UserProfile && other.uid == uid
        is Route.PrivateConversation -> other is Route.PrivateConversation && other.uid == uid
        else -> this == other
    }
}

/**
 * The page a built-in plugin id resolves to, or `null` for an unknown id.
 *
 * Both entry points — the 插件 page's 打开 button **and** a script's `ctx.ui.open(id)` — resolve
 * through this one function, so a built-in can never be reachable from one path and not the other.
 * The ids are the `builtin` values declared in `data/plugin/BuiltinPlugins.kt`; that catalogue is
 * the single source of which ids exist, this is only the id → route mapping.
 */
fun pluginBuiltinRoute(builtinId: String): Route? = when (builtinId.trim().lowercase()) {
    "history" -> Route.ThreadHistory
    "filter" -> Route.CommentFilter
    "board" -> Route.BoardSettings
    "mcp" -> Route.Mcp
    "cache" -> Route.ThreadCache
    else -> null
}

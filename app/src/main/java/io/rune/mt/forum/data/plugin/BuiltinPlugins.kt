// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.plugin

import io.rune.mt.forum.domain.plugin.PluginManifest

/**
 * The five features that already existed, expressed as plugin cards.
 *
 * Nothing about their behaviour moved: each manifest only names the `builtin` page id, and the
 * card's "打开" action resolves it to the route the settings page used to link to. That is what
 * makes 浏览历史 / 评论屏蔽 / 板块管理 / MCP 服务 / 帖子缓存 first-class plugins without a second
 * copy of their logic.
 *
 * The catalogue lives in the data layer on purpose (the brief asked for it there): it is the seed
 * of the install document, it is appended to every remote catalogue, and it must be available with
 * no network at all. Names and descriptions are Chinese because the features are; the surrounding
 * chrome comes from the three-language resources.
 */
object BuiltinPlugins {

    const val HISTORY = "history"
    const val FILTER = "filter"
    const val BOARD = "board"
    const val MCP = "mcp"
    const val CACHE = "cache"

    /** Page ids a built-in plugin may resolve to; anything else is rejected by the UI. */
    val PAGE_IDS: Set<String> = linkedSetOf(HISTORY, FILTER, BOARD, MCP, CACHE)

    /**
     * The built-in catalogue in display order.
     *
     * `enabledByDefault = true` is what the brief asks for: on a fresh install the five cards are
     * present and switched on, so the settings page loses its four rows without losing reachability.
     */
    val all: List<PluginManifest> = listOf(
        PluginManifest(
            id = HISTORY,
            name = "浏览历史",
            version = "1.0.0",
            description = "查看本机打开过的帖子，按时间倒序排列，不联网。",
            builtin = HISTORY,
            enabledByDefault = true,
        ),
        PluginManifest(
            id = FILTER,
            name = "评论屏蔽",
            version = "1.0.0",
            description = "用关键词或正则隐藏不想看的楼层，规则只存在本机。",
            builtin = FILTER,
            enabledByDefault = true,
        ),
        PluginManifest(
            id = BOARD,
            name = "板块管理",
            version = "1.0.0",
            description = "调整首页宫格的板块顺序，隐藏不常去的板块。",
            builtin = BOARD,
            enabledByDefault = true,
        ),
        PluginManifest(
            id = MCP,
            name = "MCP 服务",
            version = "1.0.0",
            description = "在本机回环地址上提供只读的帖子与回复接口，供 AI 客户端使用。",
            builtin = MCP,
            enabledByDefault = true,
        ),
        PluginManifest(
            id = CACHE,
            name = "帖子缓存",
            version = "1.0.0",
            description = "把加载过的帖子正文与楼层留在本机，断网时回退到缓存。",
            builtin = CACHE,
            enabledByDefault = true,
        ),
    )

    /** The built-in manifest for [id], or `null` when [id] is not a built-in page. */
    fun manifestFor(id: String): PluginManifest? = all.firstOrNull { it.id == id }

    /** True when [id] names one of the five built-in pages. */
    fun isPageId(id: String): Boolean = id in PAGE_IDS
}

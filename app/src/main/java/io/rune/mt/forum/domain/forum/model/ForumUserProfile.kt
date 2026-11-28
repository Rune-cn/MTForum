// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * One user's public space profile (`home.php?mod=space&uid={uid}&do=profile&mobile=2`).
 *
 * The mobile template splits the same information over several blocks: the header carries the
 * avatar / nickname / level and the 人气 · 关注 · 粉丝 line, `comiis_space_profileico` holds the
 * 帖子 / 回复 / 好友 / 粉丝 counters, `comiis_space_profilejf` the credit counters (积分 / 好评 /
 * 金币 / 信誉) and `comiis_space_profile` the profile fields (用户ID / 生日 / 性别 / …).
 *
 * Every counter is nullable on purpose: the site omits blocks for users that hide them, and a
 * missing number must not render as a fake `0`. [recentThreads] comes from the separate
 * `do=thread&view=me` listing that the repository fetches alongside the profile page; it is a
 * best-effort read, so an empty list only means "nothing to show", never a failed profile.
 */
data class ForumUserProfile(
    val uid: Int,
    val name: String = "",
    val avatarUrl: String? = null,
    /** Rank badge as printed by the site, e.g. `Lv.4`. */
    val levelText: String? = null,
    /** Rank title next to the badge, e.g. `高中生`. */
    val levelTitle: String? = null,
    /** 积分, duplicated from [creditStats] as a typed value for the header summary. */
    val credits: Int? = null,
    /** 人气 */
    val popularity: Int? = null,
    /** The profile owner's 关注 count (people they follow). */
    val followingCount: Int? = null,
    /** 粉丝 count. */
    val followerCount: Int? = null,
    /** 帖子 / 主题 count. */
    val threadCount: Int? = null,
    /** 回复 count. */
    val replyCount: Int? = null,
    /** 好友 count. */
    val friendCount: Int? = null,
    /** The full credit block in site order (积分 / 好评 / 金币 / 信誉). */
    val creditStats: List<ForumProfileStat> = emptyList(),
    /** The profile field block in site order, e.g. 用户ID / 生日 / 性别 / 在线时间. */
    val fields: List<ForumProfileField> = emptyList(),
    /** 个人签名; null when the site prints its "this user is lazy" placeholder. */
    val signature: String? = null,
    /**
     * True when the site's follow button offers 取消关注. Null when the page has no follow
     * control at all (one's own profile), which is not the same as "not following".
     */
    val isFollowing: Boolean? = null,
    /** Session formhash printed by this page; needed for the follow/unfollow POST. */
    val formhash: String? = null,
    val recentThreads: List<ForumThread> = emptyList(),
    val recentThreadPage: Int = 1,
    val recentThreadTotalPages: Int = 1,
)

/** One label/number cell of a profile block, kept in the site's own order. */
data class ForumProfileStat(
    val label: String,
    val value: String,
)

/** One label/value row of the profile's detail block (生日, 性别, 在线时间, …). */
data class ForumProfileField(
    val label: String,
    val value: String,
)
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * The state of the site's 每日签到 page (`k_misign-sign.html`).
 *
 * The page is a whole-screen card, not an API: it prints the visitor's avatar/nickname, the
 * 签到等级, three stat rows (今日排名 / 连续签到 / 累计签到) and a 签到 button whose DOM changes
 * once the day's sign-in is done. Everything here is read from that single page, so one request
 * tells the screen both "did I sign in today" and the numbers to display.
 *
 * [formhash] is the session hash printed in the page's logout link. The 签到 GET needs it, and it
 * only ever appears on an authenticated page — hence it travels with the state, the same way
 * [ForumFavoritesPage.formhash] does, instead of forcing a second request before signing in.
 *
 * [resultMessage] is the server's own CDATA text after a sign-in attempt, kept so the screen can
 * surface the wording verbatim (e.g. `签到成功` / `已签到`) rather than inventing its own.
 */
data class ForumSignInState(
    /** True when the page already rendered the 已签到 button instead of the 签到 one. */
    val alreadySignedToday: Boolean = false,
    /** 签到等级 as printed ("Lv.8"), prefix removed; null when the page omits the line. */
    val signInLevel: String? = null,
    /** 今日排名; the site prints 0 before the day's sign-in. */
    val todayRank: Int? = null,
    /** 连续签到 in days (the page prints "2天", the unit is stripped). */
    val streakDays: Int? = null,
    /** 累计签到 in days (the page prints "271天"). */
    val totalDays: Int? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    /** Session formhash from the page's logout link; required by [ForumSignInOutcome]. */
    val formhash: String? = null,
    /** Server CDATA text of the last sign-in attempt, already mapped by the repository. */
    val resultMessage: String? = null,
)

/**
 * What the `plugin.php?id=k_misign:sign&operation=qiandao` call answered.
 *
 * The endpoint is not JSON — it returns an XML wrapper around one CDATA string, and the string
 * itself is the user-facing result. [AlreadySigned] is the observed `已签到`; anything the server
 * words as a rejection becomes [Failure] so it is never shown as a success.
 */
sealed interface ForumSignInOutcome {
    /** Raw CDATA text, i.e. the server's own wording. */
    val message: String

    /** The sign-in went through; the page must be re-read for the new numbers. */
    data class Success(override val message: String) : ForumSignInOutcome

    /** The account had already signed in today (`已签到`). Not an error, but nothing changed. */
    data class AlreadySigned(override val message: String) : ForumSignInOutcome

    /** Permission / session / plugin rejection. The message is shown as an error. */
    data class Failure(override val message: String) : ForumSignInOutcome
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import kotlinx.coroutines.flow.StateFlow

/** Data entry point for every MT 论坛 screen. */
interface ForumRepository {

    /** Forum home (`forum.php`): board tiles plus the site's own 热帖排行. */
    suspend fun getHome(): ForumHomeData

    /**
     * Board listing, [page] is 1-based.
     *
     * [typeId] narrows the listing to one 主题分类 (`filter=typeid&typeid=N`); `null` is the
     * board's unfiltered 全部 listing.
     */
    suspend fun getThreads(fid: Int, page: Int = 1, typeId: Int? = null): ForumThreadPage

    /**
     * One page of a 导读 view ([view]); [page] is 1-based.
     *
     * Returns the whole [ForumThreadPage] so the caller gets the server's own pager count: the
     * guide lists print a real `div.pg` (e.g. `/ 12 页`), unlike the guide endpoint's old
     * "keep asking until a short page arrives" assumption. 抢沙发 has no pager at all, so its
     * page count stays 1.
     */
    suspend fun getGuidePage(view: ForumGuideView, page: Int = 1): ForumThreadPage

    /** Full thread with its replies. */
    suspend fun getThreadDetail(tid: Int, page: Int = 1): ForumThreadDetail

    /** Posts a reply. Returns the raw server response for error surfacing. */
    suspend fun reply(tid: Int, fid: Int, message: String, formhash: String, noticeAuthor: String?): String

    /** Current persisted session. */
    val session: StateFlow<ForumSession>

    /** Restores cookies and refreshes the logged-in user profile. */
    suspend fun restoreSession(cookies: Map<String, String>): Boolean

    /** Applies the given cookie string (as copied from a browser) and validates it. */
    suspend fun loginWithCookie(cookieHeader: String): Boolean

    /** Clears the cookie jar and persisted session. */
    suspend fun logout()


    /** Loads the profile of the signed-in user, or null when anonymous. */
    suspend fun getUserProfile(uid: Int): io.rune.mt.forum.domain.forum.model.ForumUser?


    // ---- Private messages (私信) -------------------------------------------

    /** Conversation list (`home.php?mod=space&do=pm`). */
    suspend fun getConversations(page: Int = 1): List<ForumPrivateConversation>

    /** One conversation with [uid] (`subop=view&touid=`). */
    suspend fun getConversation(uid: Int, page: Int = 1): ForumConversationDetail

    /** Sends a private message; [pmid] comes from the conversation form when replying. */
    suspend fun sendPrivateMessage(uid: Int, message: String, formhash: String, pmid: String? = null): Boolean

    // ---- Favourites (收藏) --------------------------------------------------

    /** Favourited threads (`home.php?mod=space&do=favorite&view=me`). */
    suspend fun getFavorites(page: Int = 1): List<ForumFavorite>

    // ---- Notices (提醒) -----------------------------------------------------

    /**
     * One page of the signed-in user's 提醒 inbox; [page] is 1-based.
     *
     * The site renders each [tab] as its own page (`view=system|interactive|mypost`), so the tab
     * is part of the request rather than a client-side filter. Throws when the session is stale
     * and the site answers with the login form instead of the list.
     */
    suspend fun getNotices(tab: ForumNoticeTab, page: Int = 1): ForumNoticePage

    /** Adds [tid] to favourites; two-step on the site (open form, then submit). */
    suspend fun addFavorite(tid: Int, formhash: String): Boolean

    /** Removes a favourite by its [favid]; two-step on the site. */
    suspend fun removeFavorite(favid: Long, formhash: String): Boolean

    // ---- Follow (关注) ------------------------------------------------------

    suspend fun followUser(uid: Int, formhash: String): Boolean

    suspend fun unfollowUser(uid: Int, formhash: String): Boolean

    // ---- Recommend / like (顶·赞) ------------------------------------------

    suspend fun recommendThread(tid: Int, formhash: String): Boolean

    // ---- New thread (发新帖) ------------------------------------------------

    /** Fetches the hidden formhash/posttime needed to submit a new thread in [fid]. */
    suspend fun getNewThreadForm(fid: Int): io.rune.mt.forum.domain.forum.model.ForumNewThreadForm

    suspend fun createThread(
        fid: Int,
        subject: String,
        message: String,
        form: io.rune.mt.forum.domain.forum.model.ForumNewThreadForm,
        images: List<NewThreadImage> = emptyList(),
    ): NewThreadResult
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import kotlinx.coroutines.flow.StateFlow

/** Data entry point for every MT 论坛 screen. */
interface ForumRepository {

    /** Board index (版块列表). */
    suspend fun getForums(): List<Forum>

    /** Board listing, [page] is 1-based. */
    suspend fun getThreads(fid: Int, page: Int = 1): ForumThreadPage

    /** Hot / featured threads from the guide. */
    suspend fun getHotThreads(page: Int = 1): List<io.rune.mt.forum.domain.forum.model.ForumThread>

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

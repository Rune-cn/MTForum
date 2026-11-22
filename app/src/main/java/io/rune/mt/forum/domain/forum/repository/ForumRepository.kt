// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
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
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumFavoritesPage
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumHotEntry
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import io.rune.mt.forum.domain.forum.model.ForumSignInState
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake [ForumRepository] for the MCP tests.
 *
 * Only the four read tools are wired; every mutating method fails loudly so a future edit that
 * accidentally reaches posting/PM/login from the MCP dispatcher breaks a test instead of shipping.
 * [session] is a real flow, so the signed-out search path is exercised the same way the app does.
 */
internal class McpFakeRepository(
    var home: ForumHomeData = ForumHomeData(forums = emptyList(), hotRanking = emptyList()),
    var threadPage: ForumThreadPage = ForumThreadPage(fid = 1, threads = emptyList()),
    var detail: ForumThreadDetail = ForumThreadDetail(tid = 1, title = "", posts = emptyList(), page = 1),
    var searchPage: ForumThreadPage = ForumThreadPage(fid = 0, threads = emptyList()),
    loggedIn: Boolean = true,
) : ForumRepository {

    override val session: MutableStateFlow<ForumSession> = MutableStateFlow(
        if (loggedIn) ForumSession(cookies = mapOf("cQWy_2132_auth" to "stub")) else ForumSession(),
    )

    var homeFailure: Exception? = null
    var threadPageFailure: Exception? = null
    var detailFailure: Exception? = null
    var searchFailure: Exception? = null

    var lastThreadsRequest: Pair<Int, Int>? = null
    var lastDetailRequest: Pair<Int, Int>? = null
    var lastSearchRequest: Pair<String, Int>? = null

    val mutationCalls = mutableListOf<String>()

    override suspend fun getHome(): ForumHomeData {
        homeFailure?.let { throw it }
        return home
    }

    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage {
        threadPageFailure?.let { throw it }
        lastThreadsRequest = fid to page
        return threadPage
    }

    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail {
        detailFailure?.let { throw it }
        lastDetailRequest = tid to page
        return detail
    }

    override suspend fun searchThreads(keyword: String, page: Int): ForumThreadPage {
        searchFailure?.let { throw it }
        lastSearchRequest = keyword to page
        return searchPage
    }

    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage = unused("getGuidePage")

    override suspend fun reply(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): String = mutation("reply")

    override suspend fun restoreSession(cookies: Map<String, String>): Boolean = mutation("restoreSession")

    override suspend fun loginWithCookie(cookieHeader: String): Boolean = mutation("loginWithCookie")

    override suspend fun logout() {
        mutation<Unit>("logout")
    }

    override suspend fun getUserProfile(uid: Int): ForumUser? = unused("getUserProfile")

    override suspend fun getUserProfileDetail(uid: Int): ForumUserProfile = unused("getUserProfileDetail")

    override suspend fun getConversations(page: Int): List<ForumPrivateConversation> = unused("getConversations")

    override suspend fun getConversation(uid: Int, page: Int): ForumConversationDetail = unused("getConversation")

    override suspend fun sendPrivateMessage(uid: Int, message: String, formhash: String, pmid: String?): Boolean =
        mutation("sendPrivateMessage")

    override suspend fun getFavorites(page: Int): List<ForumFavorite> = unused("getFavorites")

    override suspend fun getFavoritesPage(page: Int): ForumFavoritesPage = unused("getFavoritesPage")

    override suspend fun addFavorite(tid: Int, formhash: String): Boolean = mutation("addFavorite")

    override suspend fun removeFavorite(favid: Long, formhash: String): Boolean = mutation("removeFavorite")

    override suspend fun getNotices(tab: ForumNoticeTab, page: Int): ForumNoticePage = unused("getNotices")

    override suspend fun followUser(uid: Int, formhash: String): Boolean = mutation("followUser")

    override suspend fun unfollowUser(uid: Int, formhash: String): Boolean = mutation("unfollowUser")

    override suspend fun recommendThread(tid: Int, formhash: String): Boolean = mutation("recommendThread")

    override suspend fun getSignInState(): ForumSignInState = unused("getSignInState")

    override suspend fun signIn(formhash: String): ForumSignInOutcome = mutation("signIn")

    override suspend fun getNewThreadForm(fid: Int): ForumNewThreadForm = unused("getNewThreadForm")

    override suspend fun createThread(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): NewThreadResult = mutation("createThread")

    private fun <T> mutation(name: String): T {
        mutationCalls += name
        error("MCP must never call $name")
    }

    private fun <T> unused(name: String): T = error("$name is not part of the MCP surface")
}

/** One board row keeps the fake home readable. */
internal fun mcpHomeSample(): ForumHomeData = ForumHomeData(
    forums = listOf(
        io.rune.mt.forum.domain.forum.model.Forum(fid = 2, name = "ANDROID 逆向", description = "Repacking", todayCount = 7),
        io.rune.mt.forum.domain.forum.model.Forum(fid = 39, name = "灌水区", description = null),
    ),
    hotRanking = listOf(ForumHotEntry(rank = 1, tid = 100, title = "Hot")),
)

/** Thread rows in the order the site returned them: no client sorting, no dropping. */
internal fun mcpThreadPageSample(page: Int = 1): ForumThreadPage = ForumThreadPage(
    fid = 2,
    page = page,
    totalPages = 5,
    threads = listOf(
        ForumThread(tid = 101, fid = 2, title = "First thread", authorName = "alice", authorUid = 1, replyCount = 3, timeText = "2026-01-02"),
        ForumThread(tid = 102, fid = 2, title = "Second thread", authorName = "bob", authorUid = 2, replyCount = 0, timeText = "2026-01-03", pinned = true),
    ),
)

internal fun mcpPostSample(index: Int, html: String): ForumPost = ForumPost(
    pid = (index + 1).toLong(),
    tid = 166223,
    authorName = if (index == 0) "alice" else "bob",
    authorUid = 1,
    timeText = "2026-01-02 10:00",
    contentHtml = html,
    floor = index,
    isOp = index == 0,
)

/** One thread page with a plain-text reading floor and an attachment-bearing OP. */
internal fun mcpThreadDetailSample(): ForumThreadDetail = ForumThreadDetail(
    tid = 166223,
    title = "How to patch",
    forumName = "ANDROID 逆向",
    page = 1,
    totalPages = 4,
    posts = listOf(
        mcpPostSample(
            index = 0,
            html = """
                <p>Hello <strong>world</strong></p>
                <div class="comiis_attach">
                  <a href="forum.php?mod=attachment&amp;aid=abc123">
                    <img src="static/image/filetype/zip.gif">
                    <span class="f_ok">patch.zip</span>
                  </a>
                  <p class="attach_size">下载积分: 金币 -2</p>
                </div>
            """.trimIndent(),
        ),
        mcpPostSample(index = 1, html = "<p>Thanks!</p>"),
    ),
)
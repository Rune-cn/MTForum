// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import io.rune.mt.forum.data.forum.network.ForumUrls
import io.rune.mt.forum.data.forum.network.MultipartPart
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import timber.log.Timber

/**
 * Data entry point for every forum screen.
 *
 * The network hop is already off the main thread inside [ForumHttpClient]; the jsoup parse that
 * follows it is not free either (a thread page is ~60 KB of HTML with hundreds of nodes), so each
 * public method wraps the whole fetch-and-parse in [Dispatchers.IO] as well. Doing it here keeps
 * the view models free of dispatcher plumbing.
 */
class ForumRepositoryImpl(
    private val http: ForumHttpClient,
    private val cookieJar: ForumCookieJar,
) : ForumRepository {

    private val _session = MutableStateFlow(ForumSession())
    override val session: StateFlow<ForumSession> = _session.asStateFlow()

    override suspend fun getHome(): ForumHomeData = withContext(Dispatchers.IO) {
        // One request: the board grid and the server-ranked 热帖排行 block live on the same page.
        val html = http.getHtml("forum.php")
        ForumHomeData(
            forums = ForumHtmlParser.parseForums(html),
            hotRanking = ForumHtmlParser.parseHotRanking(html),
        )
    }

    override suspend fun getThreads(fid: Int, page: Int): ForumThreadPage = withContext(Dispatchers.IO) {
        val html = http.getHtml("forum.php?mod=forumdisplay&fid=$fid&page=$page")
        ForumHtmlParser.parseThreadPage(html, fid, page)
    }

    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage =
        withContext(Dispatchers.IO) {
            // Every 导读 view renders the same thread-list DOM as a board listing, including
            // `view=hot`; the board parser reads them unchanged and the pager gives the real
            // page count (`div.pg` -> `/ 12 页`), which 抢沙发 omits (single page).
            val html = http.getHtml(ForumUrls.guidePath(view, page))
            ForumHtmlParser.parseThreadPage(html, fid = 0, page = page)
        }

    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = withContext(Dispatchers.IO) {
        // Discuz puts the page number in the SECOND path segment (thread-{tid}-{page}-1.html).
        // The oft-guessed thread-{tid}-1-{page}.html silently returns page 1 again, which is why
        // "load more" appeared to do nothing.
        val html = http.getHtml("thread-$tid-$page-1.html")
        ForumHtmlParser.parseThreadDetail(html, tid, page)
    }

    override suspend fun reply(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): String = withContext(Dispatchers.IO) {
        val fields = buildMap {
            put("formhash", formhash)
            put("message", message)
            put("replysubmit", "yes")
            if (noticeAuthor != null) put("noticeauthor", noticeAuthor)
        }
        http.postForm(
            "forum.php?mod=post&action=reply&fid=$fid&tid=$tid&extra=page%3D1&replysubmit=yes&mobile=2",
            fields,
        )
    }

    override suspend fun restoreSession(cookies: Map<String, String>): Boolean {
        cookieJar.restore(cookies)
        return refreshSession()
    }

    override suspend fun loginWithCookie(cookieHeader: String): Boolean {
        cookieJar.clear()
        cookieHeader.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { pair ->
            val name = pair.substringBefore("=").trim()
            val value = pair.substringAfter("=").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) cookieJar.setCookie(name, value)
        }
        return refreshSession()
    }

    override suspend fun logout() {
        cookieJar.clear()
        _session.value = ForumSession()
    }

    override suspend fun getUserProfile(uid: Int): ForumUser? = withContext(Dispatchers.IO) {
        val html = runCatching { http.getHtml("home.php?mod=space&uid=$uid") }.getOrNull() ?: return@withContext null
        val doc = Jsoup.parse(html)
        // A stale session bounces the space page to the login form. Its title ("登录 - MT论坛")
        // also lacks "的广播", so a `substringBefore` fallback happily accepted it and reported an
        // anonymous visitor as signed in. Require the real profile container instead.
        val name = doc.selectFirst("span.user_tit")?.text()?.trim()
            ?: doc.selectFirst("a.sidenv_user span.user_tit")?.text()?.trim()
        if (name.isNullOrBlank()) {
            Timber.tag("ForumRepository").i("profile uid=%d unavailable (session stale?)", uid)
            return@withContext null
        }
        val avatar = doc.selectFirst("a.sidenv_user img")?.attr("src")?.trim()
            ?: doc.selectFirst("img[src*=avatar]")?.attr("src")?.trim()
        ForumUser(
            uid = uid,
            name = name,
            avatarUrl = ForumUrls.absolute(avatar),
            level = doc.selectFirst("span.user_lev")?.text()?.trim(),
            isLoggedIn = _session.value.isLoggedIn,
        )
    }

    // ---- Private messages (私信) -------------------------------------------

    override suspend fun getConversations(page: Int): List<ForumPrivateConversation> =
        withContext(Dispatchers.IO) {
            ForumHtmlParser.parseConversations(http.getHtml("home.php?mod=space&do=pm&page=$page"))
        }

    override suspend fun getConversation(uid: Int, page: Int): ForumConversationDetail =
        withContext(Dispatchers.IO) {
            val html = http.getHtml("home.php?mod=space&do=pm&subop=view&touid=$uid&page=$page")
            ForumHtmlParser.parseConversation(html, uid)
        }

    override suspend fun sendPrivateMessage(
        uid: Int,
        message: String,
        formhash: String,
        pmid: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val pmidPart = if (pmid != null) "&pmid=$pmid" else ""
        val html = http.postForm(
            "home.php?mod=spacecp&ac=pm&op=send$pmidPart&daterange=2&pmsubmit=yes&mobile=2",
            mapOf("formhash" to formhash, "touid" to uid.toString(), "message" to message),
        )
        writeSucceeded(html)
    }

    // ---- Favourites (收藏) --------------------------------------------------

    override suspend fun getFavorites(page: Int): List<ForumFavorite> = withContext(Dispatchers.IO) {
        ForumHtmlParser.parseFavorites(
            http.getHtml("home.php?mod=space&do=favorite&view=me&type=all&page=$page"),
        )
    }

    // ---- Notices (提醒) -----------------------------------------------------

    override suspend fun getNotices(tab: ForumNoticeTab, page: Int): ForumNoticePage =
        withContext(Dispatchers.IO) {
            val html = http.getHtml(ForumUrls.noticePath(tab, page))
            // A stale cookie set bounces the tab page to the login form, which parses as "no
            // rows". Surfacing the failure keeps the screen from lying with an empty inbox.
            if (ForumHtmlParser.isLoginRequired(html)) {
                throw IllegalStateException("登录状态已失效，请重新登录")
            }
            ForumHtmlParser.parseNotices(html, tab, page)
        }

    override suspend fun addFavorite(tid: Int, formhash: String): Boolean = withContext(Dispatchers.IO) {
        // The site favourites in two hops: GET renders a small form, POST submits it. A single GET
        // only returns that form, which is why a naive one-shot request never adds anything.
        http.getHtml("home.php?mod=spacecp&ac=favorite&type=thread&id=$tid&handlekey=favorite_thread")
        val html = http.postForm(
            "home.php?mod=spacecp&ac=favorite&type=thread&id=$tid&spaceuid=&mobile=2",
            mapOf(
                "formhash" to formhash,
                "favoritesubmit" to "true",
                "handlekey" to "favorite_add",
                "description" to "",
                "referer" to "https://bbs.binmt.cc/thread-$tid-1-1.html",
            ),
        )
        writeSucceeded(html)
    }

    override suspend fun removeFavorite(favid: Long, formhash: String): Boolean = withContext(Dispatchers.IO) {
        http.getHtml("home.php?mod=spacecp&ac=favorite&op=delete&favid=$favid&type=all")
        val html = http.postForm(
            "home.php?mod=spacecp&ac=favorite&op=delete&favid=$favid&type=all&mobile=2",
            mapOf(
                "referer" to "https://bbs.binmt.cc/home.php?mod=space&do=favorite&view=me",
                "deletesubmit" to "true",
                "formhash" to formhash,
                "handlekey" to "comiis",
            ),
        )
        writeSucceeded(html)
    }

    // ---- Follow (关注) ------------------------------------------------------

    override suspend fun followUser(uid: Int, formhash: String): Boolean = withContext(Dispatchers.IO) {
        writeSucceeded(
            http.getHtml("home.php?mod=spacecp&ac=follow&op=add&hash=$formhash&fuid=$uid&mobile=2"),
        )
    }

    override suspend fun unfollowUser(uid: Int, formhash: String): Boolean = withContext(Dispatchers.IO) {
        writeSucceeded(
            http.getHtml("home.php?mod=spacecp&ac=follow&op=del&hash=$formhash&fuid=$uid&mobile=2"),
        )
    }

    // ---- Recommend / like (顶·赞) ------------------------------------------

    override suspend fun recommendThread(tid: Int, formhash: String): Boolean = withContext(Dispatchers.IO) {
        writeSucceeded(
            http.getHtml(
                "forum.php?mod=misc&action=recommend&handlekey=recommend_add&do=add&tid=$tid&hash=$formhash",
            ),
        )
    }

    // ---- New thread (发新帖) ------------------------------------------------

    override suspend fun getNewThreadForm(fid: Int): ForumNewThreadForm = withContext(Dispatchers.IO) {
        ForumHtmlParser.parseNewThreadForm(
            http.getHtml("forum.php?mod=post&action=newthread&fid=$fid"),
            fid,
        )
    }

    override suspend fun createThread(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): NewThreadResult = withContext(Dispatchers.IO) {
        val posttime = form.posttime ?: (System.currentTimeMillis() / 1000).toString()
        val fields = mapOf(
            "formhash" to form.formhash.orEmpty(),
            "posttime" to posttime,
            "subject" to subject,
            "message" to message,
            "htmlon" to "0",
            "usesig" to "1",
            "allownoticeauthor" to "1",
            "save" to "",
        )
        val parts = images.map { MultipartPart("Filedata", it.fileName, it.contentType, it.bytes) }
        val html = http.postMultipart(
            "forum.php?mod=post&action=newthread&fid=$fid&extra=&topicsubmit=yes&mobile=2",
            fields,
            parts,
        )
        if (ForumHtmlParser.isLoginRequired(html)) {
            return@withContext NewThreadResult.Failure("登录状态已失效，请重新登录")
        }
        val notice = ForumHtmlParser.extractNotice(html)
        val failed = notice != null && ERROR_WORDS.containsMatchIn(notice)
        if (failed) {
            NewThreadResult.Failure(notice!!)
        } else {
            NewThreadResult.Success(notice, ForumHtmlParser.resultTid(html))
        }
    }

    /** A write is considered successful unless it bounced to the login form or returned an error notice. */
    private fun writeSucceeded(html: String): Boolean =
        !ForumHtmlParser.isLoginRequired(html) && !ERROR_WORDS.containsMatchIn(ForumHtmlParser.extractNotice(html).orEmpty())

    /** Probes the profile of our own uid to decide whether the cookie set is valid. */
    private suspend fun refreshSession(): Boolean {
        val uid = cookieUid()
        if (uid == null) {
            _session.value = ForumSession(cookies = cookieJar.snapshot())
            return false
        }
        val user = runCatching { getUserProfile(uid) }.getOrNull()
        val ok = user != null
        _session.value = ForumSession(
            cookies = cookieJar.snapshot(),
            user = if (ok) user!!.copy(isLoggedIn = true) else null,
        )
        Timber.tag("ForumRepository").i("session restore uid=%s ok=%s", uid, ok)
        return ok
    }

    /**
     * Reads the signed-in uid out of the cookie jar.
     *
     * `cQWy_2132_lip` looks tempting but holds the client IP (`<ip>%2C<ts>`), not a uid, and
     * `*_ulastactivity` is an opaque hash. `*_lastcheckfeed` is the cookie Discuz writes as
     * `<uid>|<timestamp>` and is the only reliable source here.
     */
    private fun cookieUid(): Int? {
        val raw = cookieJar.snapshot()
        val fromCheckFeed = raw["cQWy_2132_lastcheckfeed"]?.substringBefore("|")?.toIntOrNull()
        if (fromCheckFeed != null) return fromCheckFeed
        val fromCookieName = raw.keys
            .firstOrNull { it.contains("_coloruid_") }
            ?.substringAfter("_coloruid_")
            ?.toIntOrNull()
        if (fromCookieName != null) return fromCookieName
        return raw.entries.firstOrNull { it.key.endsWith("_auth") }
            ?.value?.takeWhile { it.isDigit() }?.toIntOrNull()
    }

    private companion object {
        /** Discuz error notices that mean a write did not go through. */
        val ERROR_WORDS = Regex("抱歉|非法|错误|不能|失败|无权限|权限不足|间隔")
    }
}

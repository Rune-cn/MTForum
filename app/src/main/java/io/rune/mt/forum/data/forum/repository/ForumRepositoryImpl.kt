// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
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

    override suspend fun getForums(): List<Forum> = withContext(Dispatchers.IO) {
        ForumHtmlParser.parseForums(http.getHtml("forum.php"))
    }

    override suspend fun getThreads(fid: Int, page: Int): ForumThreadPage = withContext(Dispatchers.IO) {
        val html = http.getHtml("forum.php?mod=forumdisplay&fid=$fid&page=$page")
        ForumHtmlParser.parseThreadPage(html, fid, page)
    }

    override suspend fun getHotThreads(page: Int): List<ForumThread> = withContext(Dispatchers.IO) {
        // `view=hot` redirects to the portal home (no thread list at all), which is why the guide
        // tab used to render empty. `view=newthread` and `view=digest` are the real 导读 lists and
        // share the board listing's DOM, so the board parser can read them unchanged.
        val html = http.getHtml("forum.php?mod=guide&view=newthread&page=$page")
        val page1 = ForumHtmlParser.parseThreadPage(html, fid = 0, page = page)
        page1.notice + page1.threads
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
            avatarUrl = avatar?.takeIf { it.isNotBlank() }?.let { resolveUrl(it) },
            level = doc.selectFirst("span.user_lev")?.text()?.trim(),
            isLoggedIn = _session.value.isLoggedIn,
        )
    }

    /** Absolute-izes the relative asset URLs the site emits inside profile pages. */
    private fun resolveUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> ForumHttpClient.BASE_URL + url.removePrefix("/")
    }

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
}

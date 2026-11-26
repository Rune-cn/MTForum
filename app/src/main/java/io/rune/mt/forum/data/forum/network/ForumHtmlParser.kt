// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumPrivateMessage
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses the Comiis mobile template of bbs.binmt.cc.
 *
 * The site renders server-side HTML only – its `api/mobile/index.php` endpoint is disabled
 * (502) – so every screen is built from selectors documented in docs/REVERSE_SPEC.md.
 */
object ForumHtmlParser {

    private val FORM_HASH = Regex("""formhash\s*=\s*'([0-9a-f]+)'""")
    private val TID_IN_HREF = Regex("""thread-(\d+)-""")
    private val FID_IN_HREF = Regex("""fid=(\d+)""")
    private val UID_IN_HREF = Regex("""uid=(\d+)""")
    private val FORUM_HREF = Regex("""forum-(\d+)-1\.html""")
    private val PID_ID = Regex("""pid(\d+)""")
    private val UID_TOUID = Regex("""touid=(\d+)""")
    private val PMID_IN_URL = Regex("""pmid=(\d+)""")
    private val FAVID = Regex("""favid=(\d+)""")
    private val TID_RESULT = Regex("""thread-(\d+)-\d+-\d+\.html""")

    fun parseFormhash(html: String): String? = FORM_HASH.find(html)?.groupValues?.get(1)

    /** Parses the board index (`forum.php`, or `forum.php?forumlist=1` for the full list). */
    fun parseForums(html: String): List<Forum> {
        val doc = Jsoup.parse(html)
        return doc.select("div.comiis_mh_hotbk li, div.comiis_fl_g").mapNotNull { li ->
            val link = li.selectFirst("a[href*=forum-]") ?: return@mapNotNull null
            val fid = FORUM_HREF.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val img = li.selectFirst("img")
            Forum(
                fid = fid,
                name = (li.selectFirst("p")?.text() ?: link.text()).trim(),
                iconUrl = img?.attr("comiis_loadimages")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("abs:src"),
                description = li.selectFirst("dd")?.text()?.trim(),
            )
        }.distinctBy { it.fid }
    }

    /** Parses a board listing (`forum.php?mod=forumdisplay&fid=`). */
    fun parseThreadPage(html: String, fid: Int, page: Int): ForumThreadPage {
        val doc = Jsoup.parse(html)
        val threads = doc.select("li.forumlist_li").mapNotNull { parseThreadRow(it, fid) }
        val notice = doc.select("div.comiis_forumlist_top li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val tid = TID_IN_HREF.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            ForumThread(
                tid = tid,
                fid = fid,
                title = a.text().replace("置顶", "").trim(),
                authorName = "",
                authorUid = 0,
                pinned = true,
            )
        }
        return ForumThreadPage(
            fid = fid,
            threads = threads,
            page = page,
            totalPages = parseTotalPages(doc),
            notice = notice,
        )
    }

    private fun parseThreadRow(li: Element, fid: Int): ForumThread? {
        val titleLink = li.selectFirst("div.mmlist_li_box h2 a") ?: return null
        val tid = TID_IN_HREF.find(titleLink.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val authorLink = li.selectFirst("h2 a.top_user")
        val stats = li.select("div.comiis_xznalist_bottom span.comiis_tm").map { it.text().filterDigits().toIntOrNull() ?: 0 }
        return ForumThread(
            tid = tid,
            fid = fid,
            title = titleLink.text().trim(),
            summary = li.selectFirst("div.list_body a")?.text()?.trim(),
            authorName = authorLink?.text()?.trim().orEmpty(),
            authorUid = UID_IN_HREF.find(authorLink?.attr("href").orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            authorAvatarUrl = li.selectFirst("img.top_tximg")?.attr("abs:src"),
            authorLevel = li.selectFirst("span.top_lev")?.text()?.trim(),
            timeText = li.selectFirst("div.forumlist_li_time span")?.text()?.trim(),
            recommendCount = stats.getOrNull(0) ?: 0,
            replyCount = stats.getOrNull(1) ?: 0,
            viewCount = stats.getOrNull(2) ?: 0,
            pinned = li.selectFirst("em.comiis_xifont")?.text()?.contains("置顶") == true,
            imageUrls = parseThumbnails(li),
        )
    }


    /**
     * Collects a thread row's thumbnails.
     *
     * The list template lazy-loads them: `src` is a shared placeholder and the real file sits in
     * the `comiis_loadimages` attribute, so reading `src` alone yields nothing but `none.png`.
     */
    private fun parseThumbnails(li: Element): List<String> =
        li.select("div.comiis_pyqlist_imgs img")
            .mapNotNull { img ->
                val raw = img.attr("comiis_loadimages").ifBlank { img.attr("data-src") }.ifBlank { img.attr("src") }
                if (raw.isBlank() || raw.contains("pic/none.png")) null else raw
            }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .distinct()
            .take(3)

    /** Parses a thread page (`thread-{tid}-1-1.html`). */
    fun parseThreadDetail(html: String, tid: Int, page: Int): ForumThreadDetail {
        val doc = Jsoup.parse(html)
        val perPage = 10
        val posts = doc.select("div.comiis_postli").withIndex().mapNotNull { (index, el) ->
            parsePost(el, tid)?.let { post ->
                // The template prints no floor numbers, so derive them: floor 0 is the OP and
                // the rest continue across pages.
                val isFirstFloor = page == 1 && index == 0
                post.copy(
                    floor = if (isFirstFloor) 0 else (page - 1) * perPage + index,
                    isOp = isFirstFloor,
                )
            }
        }
        val noticeAuthor = doc.selectFirst("input[name=noticeauthor]")?.attr("value")
        return ForumThreadDetail(
            tid = tid,
            fid = FID_IN_HREF.find(html)?.groupValues?.get(1)?.toIntOrNull(),
            title = doc.selectFirst("title")?.text()?.substringBefore(" - MT论坛")?.trim().orEmpty(),
            posts = posts,
            page = page,
            totalPages = parseTotalPages(doc),
            formhash = parseFormhash(html),
            noticeAuthor = noticeAuthor,
        )
    }

    private fun parsePost(el: Element, tid: Int): ForumPost? {
        val pid = PID_ID.find(el.id())?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val content = el.selectFirst("div.comiis_messages")?.html() ?: return null
        val authorLink = el.selectFirst("h2 a.top_user")
        return ForumPost(
            pid = pid,
            tid = tid,
            authorName = authorLink?.text()?.trim().orEmpty(),
            authorUid = UID_IN_HREF.find(authorLink?.attr("href").orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            authorAvatarUrl = el.selectFirst("img.top_tximg")?.attr("abs:src"),
            authorLevel = el.selectFirst("a.top_lev")?.text()?.trim(),
            timeText = el.selectFirst("span.kmtime")?.text()?.trim(),
            location = el.selectFirst("code.comiis_iplocality")?.text()?.trim(),
            contentHtml = content,
            isOp = false, // resolved below: only the first floor of page 1 is the OP
        )
    }

    // ---- Private messages (私信) -------------------------------------------

    /** Conversation list rows on `home.php?mod=space&do=pm`. */
    fun parseConversations(html: String): List<ForumPrivateConversation> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href*=\"subop=view\"][href*=\"touid=\"]").mapNotNull { a ->
            val uid = UID_TOUID.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val dateEl = a.selectFirst("span.f_d")
            val h2 = a.selectFirst("h2")?.text()?.trim().orEmpty()
            val date = dateEl?.text()?.trim()
            val name = if (date != null && h2.startsWith(date)) h2.removePrefix(date).trim() else h2
            ForumPrivateConversation(
                uid = uid,
                name = name.ifBlank { h2 },
                avatarUrl = a.selectFirst("img")?.let { abs(it.attr("src")) },
                lastTimeText = date,
                lastPreview = a.selectFirst("p.f_c")?.text()?.trim()?.takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.uid }
    }

    /** A conversation with [uid] on `home.php?mod=space&do=pm&subop=view&touid=`. */
    fun parseConversation(html: String, uid: Int): ForumConversationDetail {
        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form#pmform")
        val pmid = PMID_IN_URL.find(form?.attr("action").orEmpty())?.groupValues?.get(1)
        val formhash = form?.selectFirst("input[name=formhash]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: parseFormhash(html)
        // Jsoup's select() returns document order, so the date separators between rows are simply
        // skipped while the bubbles keep their natural order.
        val messages = doc.select("div.comiis_friend_msg, div.comiis_self_msg").mapNotNull { el ->
            val isMine = el.className().contains("comiis_self_msg")
            val content = el.selectFirst("div.msg_mes")?.html() ?: return@mapNotNull null
            ForumPrivateMessage(
                fromUid = if (isMine) 0 else uid,
                timeText = el.selectFirst("div.msg_time")?.text()?.trim(),
                avatarUrl = el.selectFirst("img.msg_avt")?.let { abs(it.attr("src")) },
                contentHtml = content,
                isMine = isMine,
            )
        }
        return ForumConversationDetail(
            withUid = uid,
            messages = messages,
            pmid = pmid,
            formhash = formhash,
            totalPages = parseTotalPages(doc),
        )
    }

    // ---- Favourites (收藏) --------------------------------------------------

    fun parseFavorites(html: String): List<ForumFavorite> {
        val doc = Jsoup.parse(html)
        return doc.select("li.mysclist_li").mapNotNull { li ->
            val titleLink = li.selectFirst("a[href*=thread-]") ?: return@mapNotNull null
            val tid = TID_IN_HREF.find(titleLink.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            val favid = FAVID.find(li.selectFirst("a[href*=favid=]")?.attr("href").orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            ForumFavorite(
                favid = favid,
                tid = tid,
                title = titleLink.text().trim(),
                forumName = li.selectFirst("a[href*=forum-]")?.text()?.trim(),
            )
        }
    }

    /** Hidden credentials of the new-thread form. */
    fun parseNewThreadForm(html: String, fid: Int): io.rune.mt.forum.domain.forum.model.ForumNewThreadForm {
        val doc = Jsoup.parse(html)
        val formhash = doc.selectFirst("input[name=formhash]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: parseFormhash(html)
        val posttime = doc.selectFirst("input[name=posttime]")?.attr("value")?.takeIf { it.isNotBlank() }
        val captcha = doc.selectFirst(
            "input[name=seccode], input[name=seccodeverify], div.seccode, #seccodeimg, img[src*=seccode]",
        ) != null
        return io.rune.mt.forum.domain.forum.model.ForumNewThreadForm(formhash, posttime, captcha)
    }

    // ---- Action-result helpers ---------------------------------------------

    fun pageTitle(html: String): String = Jsoup.parse(html).title()

    /** True when a write landed on the login form (stale session) instead of doing the action. */
    fun isLoginRequired(html: String): Boolean {
        val doc = Jsoup.parse(html)
        val title = doc.title()
        return doc.selectFirst("form[action*=loginsubmit]") != null ||
            (title.contains("登录") && doc.selectFirst("input[name=username]") != null)
    }

    /** Best-effort extraction of a Discuz notice/message body (`提示信息` pages). */
    fun extractNotice(html: String): String? {
        val doc = Jsoup.parse(html)
        val el = doc.selectFirst(
            "div#messagetext, div.messagetext, div.comiis_msgbox div.msg_mes, p.alert, div.alert_info",
        )
        return el?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Extracts a thread id from a success redirect (`thread-123-1-1.html`). */
    fun resultTid(html: String): Int? = TID_RESULT.find(html)?.groupValues?.get(1)?.toIntOrNull()

    private fun abs(url: String): String? = when {
        url.isBlank() -> null
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> "https://bbs.binmt.cc/" + url.removePrefix("/")
    }

    private fun parseTotalPages(doc: Document): Int {
        val last = doc.select("div.pg a").mapNotNull { it.text().filterDigits().toIntOrNull() }.maxOrNull()
        return (last ?: 1).coerceAtLeast(1)
    }

    private fun String.filterDigits(): String = filter { it.isDigit() }
}

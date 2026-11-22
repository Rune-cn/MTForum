// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumPost
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

    private fun parseTotalPages(doc: Document): Int {
        val last = doc.select("div.pg a").mapNotNull { it.text().filterDigits().toIntOrNull() }.maxOrNull()
        return (last ?: 1).coerceAtLeast(1)
    }

    private fun String.filterDigits(): String = filter { it.isDigit() }
}

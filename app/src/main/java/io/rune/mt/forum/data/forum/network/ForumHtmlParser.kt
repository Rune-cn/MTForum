// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumCategory
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumDraft
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumFavoritesPage
import io.rune.mt.forum.domain.forum.model.ForumHotEntry
import io.rune.mt.forum.domain.forum.model.ForumNotice
import io.rune.mt.forum.domain.forum.model.ForumNoticeKind
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumPrivateMessage
import io.rune.mt.forum.domain.forum.model.ForumProfileField
import io.rune.mt.forum.domain.forum.model.ForumProfileStat
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import io.rune.mt.forum.domain.forum.model.ForumSignInState
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
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

    private val FORM_HASH = Regex("""formhash\s*=\s*['"]([0-9a-f]+)['"]""")
    /** The mobile template also prints the session hash as a query parameter in logout links. */
    private val FORM_HASH_IN_URL = Regex("""formhash=([0-9a-f]+)""")
    private val TID_IN_HREF = Regex("""thread-(\d+)-""")
    private val FID_IN_HREF = Regex("""fid=(\d+)""")
    /** Discuz `filter=typeid&typeid=N` link of one 主题分类 tab. */
    private val TYPEID_IN_HREF = Regex("""[?&]typeid=(\d+)""")
    private val UID_IN_HREF = Regex("""uid=(\d+)""")
    private val FORUM_HREF = Regex("""forum-(\d+)-1\.html""")
    private val PID_ID = Regex("""pid(\d+)""")
    private val UID_TOUID = Regex("""touid=(\d+)""")
    private val PMID_IN_URL = Regex("""pmid=(\d+)""")
    private val FAVID = Regex("""favid=(\d+)""")
    private val TID_RESULT = Regex("""thread-(\d+)-\d+-\d+\.html""")

    fun parseFormhash(html: String): String? =
        (FORM_HASH.find(html) ?: FORM_HASH_IN_URL.find(html))?.groupValues?.get(1)

    /**
     * Parses every board the site lists on one or more index pages, in document order.
     *
     * Three blocks carry board tiles and each one exposes a different subset:
     * - the shortcut grid of `forum.php` (`div.comiis_mh_hotbk li`, legacy `div.comiis_fl_g`)
     *   holds the eight 热版推荐 tiles only;
     * - the full index of `forum.php?forumlist=1` (the 社区 page, `div.comiis_forumlist` groups)
     *   lists every board, including ones the home grid never shows (e.g. 站务专区);
     * - the quick-post board picker (`ul[class^=comiis_fxpostlistbox_] li`) lists the boards a
     *   signed-in user can post in, i.e. another partial subset.
     *
     * Pass both pages ([pages]) to get the complete set; a single page still yields the boards
     * that page carries. Rows are kept in the order the site prints them (the grid's own order is
     * meaningful and is never re-sorted) and de-duplicated by fid, filling a field the earlier
     * block omitted from a later one (the full index carries the 今日 count, the grid the icon).
     */
    fun parseForums(html: String): List<Forum> = parseForums(listOf(html))

    fun parseForums(pages: List<String>): List<Forum> =
        mergeBoards(pages.flatMap { page -> parseBoardEntries(Jsoup.parse(page)) })

    /** Collects one page's board tiles, block by block, in document order. */
    private fun parseBoardEntries(doc: Document): List<Forum> = buildList {
        addAll(doc.select("div.comiis_mh_hotbk li, div.comiis_fl_g").mapNotNull { shortcutBoard(it) })
        addAll(
            doc.select("div.comiis_forumlist div.comiis_forum_nbox ul li").mapNotNull { indexBoard(it) },
        )
        addAll(doc.select("ul[class^=comiis_fxpostlistbox_] li").mapNotNull { quickPostBoard(it) })
    }

    /** A tile of the home shortcut grid (`div.comiis_mh_hotbk li`). */
    private fun shortcutBoard(li: Element): Forum? {
        val link = li.selectFirst("a[href*=forum-]") ?: return null
        val fid = FORUM_HREF.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return Forum(
            fid = fid,
            name = li.selectFirst("p")?.text()?.trim().orEmpty().ifBlank { link.text().cleanTitle() },
            iconUrl = boardIcon(li),
            description = li.selectFirst("dd")?.text()?.trim(),
        )
    }

    /**
     * A tile of the full index (`forum.php?forumlist=1`).
     *
     * Its `<em>` badge is the site's 今日 post count — the same number the board header prints as
     * `今日 N`, not the board's total — so it fills [Forum.todayCount] and never `threadCount`.
     */
    private fun indexBoard(li: Element): Forum? {
        val link = li.selectFirst("a[href*=forum-]") ?: return null
        val fid = FORUM_HREF.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val img = li.selectFirst("img")
        return Forum(
            fid = fid,
            name = li.selectFirst("p")?.text()?.trim().orEmpty()
                .ifBlank { img?.attr("alt")?.trim().orEmpty() }
                .ifBlank { link.text().cleanTitle() },
            iconUrl = boardIcon(li),
            todayCount = li.selectFirst("em span")?.text()?.filterDigits()?.toIntOrNull(),
        )
    }

    /** A board of the quick-post picker (`ul[class^=comiis_fxpostlistbox_] li`). */
    private fun quickPostBoard(li: Element): Forum? {
        val link = li.selectFirst("a[href*=fid=]") ?: return null
        val fid = FID_IN_HREF.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return Forum(
            fid = fid,
            name = li.selectFirst("em")?.text()?.cleanTitle().orEmpty()
                .ifBlank { li.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { link.text().cleanTitle() },
            iconUrl = boardIcon(li),
        )
    }

    /**
     * Icon URL of a board tile.
     *
     * The grid and the quick-post picker lazy-load it: `src` is the shared `none.png` placeholder
     * and the real file sits in `comiis_loadimages`. The full index prints the real file in `src`.
     */
    private fun boardIcon(el: Element): String? {
        val img = el.selectFirst("img") ?: return null
        return ForumUrls.absolute(
            img.attr(LAZY_IMAGE_ATTR).takeIf { it.isNotBlank() }
                ?: img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("src"),
        )
    }

    /** De-duplicates boards by fid while keeping the first occurrence's position. */
    private fun mergeBoards(entries: List<Forum>): List<Forum> {
        val merged = linkedMapOf<Int, Forum>()
        entries.forEach { board ->
            val previous = merged[board.fid]
            merged[board.fid] = if (previous == null) {
                board
            } else {
                previous.copy(
                    description = previous.description ?: board.description,
                    iconUrl = previous.iconUrl ?: board.iconUrl,
                    todayCount = previous.todayCount ?: board.todayCount,
                )
            }
        }
        return merged.values.toList()
    }

    /**
     * Parses the 主题分类 strip of a board listing (`#forumlist_time_li`).
     *
     * The template reuses one swiper for two unrelated things: a board that has categories prints
     * 全部 followed by one tab per `typeid`, while a board without categories prints the
     * 全部/最新/热门/精华 *sort* switch, which carries no `typeid` at all. Only anchors that really
     * link to `filter=typeid&typeid=N` become categories, so a plain board yields an empty list and
     * the caller keeps its unfiltered listing. The `typeId = null` 全部 entry is emitted only when
     * categories exist, so it never shows up as a dead tab.
     */
    fun parseCategories(html: String): List<ForumCategory> = parseCategories(Jsoup.parse(html))

    private fun parseCategories(doc: Document): List<ForumCategory> {
        val strip = doc.selectFirst("#forumlist_time_li") ?: return emptyList()
        val categories = strip.select("li a[href]").mapNotNull { anchor ->
            val typeId = TYPEID_IN_HREF.find(anchor.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            ForumCategory(typeId = typeId, name = anchor.text().cleanTitle())
                .takeIf { it.name.isNotBlank() }
        }.distinctBy { it.typeId }
        if (categories.isEmpty()) return emptyList()
        return listOf(ForumCategory(typeId = null, name = CATEGORY_ALL_LABEL)) + categories
    }

    /**
     * Parses the server-rendered 热帖排行 block of the forum home page.
     *
     * The list is already ranked by the site: rows are read in document order and the badge
     * number comes from the `em`, never from the list index and never re-sorted. The block has
     * only rank/title/link, so no reply/view data is inferred. `a.text()` also contains the
     * leading rank number, hence the `title` attribute is preferred.
     */
    fun parseHotRanking(html: String): List<ForumHotEntry> {
        val doc = Jsoup.parse(html)
        return doc.select("div.comiis_mh_txtlist_phb ul li").mapIndexedNotNull { index, li ->
            val link = li.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val tid = TID_IN_HREF.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapIndexedNotNull null
            val rank = li.selectFirst("em")?.text()?.filterDigits()?.toIntOrNull() ?: (index + 1)
            val title = link.attr("title").trim().ifBlank {
                link.text().removePrefix(rank.toString()).cleanTitle()
            }
            ForumHotEntry(rank = rank, tid = tid, title = title)
        }
    }

    /** Parses a board listing (`forum.php?mod=forumdisplay&fid=`). */
    fun parseThreadPage(html: String, fid: Int, page: Int): ForumThreadPage {
        val doc = Jsoup.parse(html)
        val threads = doc.select("li.forumlist_li").mapNotNull { parseThreadRow(it, fid) }
        // The 置顶 strip (`div.comiis_forumlist_top`) uses the same row furniture as a regular
        // listing row when the template renders one (byline, avatar, time, counts), so both shapes
        // go through the same parser instead of a title-only special case. The flag is forced on
        // because the strip itself is the site's 置顶 marker.
        // Direct `ul > li` only: a full pinned row repeats the stats block's own `<li>` elements
        // (reply/view counts), and those are cells of the row, not extra rows.
        val notice = doc.select("div.comiis_forumlist_top > ul > li")
            .mapNotNull { parseThreadRow(it, fid, pinned = true) }
        return ForumThreadPage(
            fid = fid,
            threads = threads,
            page = page,
            totalPages = parseTotalPages(doc),
            notice = notice,
            categories = parseCategories(doc),
        )
    }

    /**
     * Parses a search result page (`search.php?mod=forum&searchid=…`).
     *
     * The Comiis search result reuses the board listing's row DOM (`li.forumlist_li`), but rows
     * come from different boards, so [parseThreadRow] is called with `fid = null` and the row's
     * own board link (`forum-<fid>-1.html`) supplies the fid.
     */
    fun parseSearch(html: String, page: Int = 1): ForumThreadPage {
        val doc = Jsoup.parse(html)
        val threads = doc.select("li.forumlist_li").mapNotNull { parseThreadRow(it, fid = null) }
        return ForumThreadPage(
            fid = 0,
            threads = threads,
            page = page,
            totalPages = parseTotalPages(doc),
        )
    }

    /**
     * Parses one listing row, and the same row DOM inside a search result page.
     *
     * A regular row (`li.forumlist_li`) carries its byline, avatar, time and counts in the
     * `forumlist_li_top` / `comiis_xznalist_bottom` blocks. The 置顶 strip uses a `li` that may
     * either be title-only (the global announcements, which the site prints without a byline) or
     * a full row with the same furniture; both are read here, so the strip gets an avatar/time
     * whenever the markup actually carries one instead of being hard-coded to a blank author.
     *
     * [fid] is null for search results, where a page mixes boards: the row's own board link
     * (`forum-<fid>-1.html`) is then the only board source.
     */
    private fun parseThreadRow(li: Element, fid: Int?, pinned: Boolean = false): ForumThread? {
        val titleLink = li.selectFirst("div.mmlist_li_box h2 a")
            ?: li.selectFirst("h2 a[href*=thread-]")
            ?: li.selectFirst("a[href*=thread-]")
            ?: return null
        val tid = TID_IN_HREF.find(titleLink.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        // A board listing knows its fid from the request, but a search result mixes boards: there
        // the row's own board breadcrumb link (`forum-<fid>-1.html`) is the only source.
        val rowFid = fid ?: li.selectFirst("a[href*=forum-]")?.let { link ->
            FORUM_HREF.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0
        val authorLink = li.selectFirst("h2 a.top_user")
        val avatar = li.selectFirst("img.top_tximg") ?: li.selectFirst("img[comiis_loadimages], img[src]")
        val stats = li.select("div.comiis_xznalist_bottom span.comiis_tm")
            .ifEmpty { li.select("span.comiis_tm") }
            .map { it.text().filterDigits().toIntOrNull() ?: 0 }
        // 精华 rows carry a `<span class="bg_c f_f">精</span>` badge inside the title area (the
        // same slot that holds the 热度 badge). The badge text is not part of the title, so both
        // the flag and the title are derived structurally, never by sniffing a leading 精.
        val digest = li.selectFirst("div.mmlist_li_box h2 span.bg_c")?.text()?.contains("精") == true
        val time = li.selectFirst("div.forumlist_li_time span")
            ?: li.selectFirst("span.kmtime")
            ?: li.selectFirst("div.comiis_postli_time span")
        return ForumThread(
            tid = tid,
            fid = rowFid,
            title = threadTitle(titleLink),
            summary = li.selectFirst("div.list_body a")?.text()?.trim(),
            authorName = authorLink?.text()?.trim().orEmpty(),
            authorUid = UID_IN_HREF.find(authorLink?.attr("href").orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            authorAvatarUrl = ForumUrls.absolute(
                avatar?.attr("comiis_loadimages")?.takeIf { it.isNotBlank() } ?: avatar?.attr("src"),
            ),
            authorLevel = li.selectFirst("span.top_lev")?.text()?.trim(),
            timeText = time?.text()?.trim(),
            recommendCount = stats.getOrNull(0) ?: 0,
            replyCount = stats.getOrNull(1) ?: 0,
            viewCount = stats.getOrNull(2) ?: 0,
            pinned = pinned || li.selectFirst("em.comiis_xifont")?.text()?.contains("置顶") == true,
            digest = digest,
            imageUrls = parseThumbnails(li),
        )
    }

    /**
     * Title text with the row's status badges removed.
     *
     * Guide/board rows inline badges inside the title anchor — `span.bg_c` for 精华,
     * `span.bg_a` for 热度 and their icon-font `i.comiis_font` glyphs. None of that belongs to
     * the title, so a clone of the anchor is stripped before [cleanTitle] removes any remaining
     * private-use glyph.
     */
    private fun threadTitle(anchor: Element): String {
        val copy = anchor.clone()
        // `em.comiis_xifont` is the 置顶 marker inside the announcement strip; it is a status,
        // not part of the title, and the row parser reports it through `pinned` instead.
        copy.select("span.bg_c, span.bg_a, i.comiis_font, em.comiis_xifont").remove()
        return copy.text().cleanTitle()
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
                if (raw.isBlank() || raw.contains("pic/none.png")) null else ForumUrls.absolute(raw)
            }
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
            forumName = parseForumName(doc),
            posts = posts,
            page = page,
            totalPages = parseTotalPages(doc),
            formhash = parseFormhash(html),
            noticeAuthor = noticeAuthor,
            recommendCount = parseRecommendCount(doc),
        )
    }

    /**
     * The OP's 推荐 (顶) count, or 0 when nobody has推荐ed.
     *
     * The template prints the count in the recommender list the OP card hydrates after 推荐:
     * `ul.comiis_recommend_list_a em.comiis_recommend_num` (the same component the forum list's
     * row can reuse). A thread with zero likes renders the list empty and the footer's
     * recommend control without any number, so "no element" is 0, never an error. The footer
     * (`span#comiis_recommend_num`) duplicates the value; scoping to the OP list keeps the
     * selector independent of the footer's markup.
     */
    private fun parseRecommendCount(doc: Document): Int =
        doc.selectFirst("ul.comiis_recommend_list_a em.comiis_recommend_num")
            ?.text()
            ?.filterDigits()
            ?.toIntOrNull()
            ?: 0

    private fun parsePost(el: Element, tid: Int): ForumPost? {
        val pid = PID_ID.find(el.id())?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val content = el.selectFirst("div.comiis_messages")?.html() ?: return null
        val authorLink = el.selectFirst("h2 a.top_user")
        val avatar = el.selectFirst("img.top_tximg")
        return ForumPost(
            pid = pid,
            tid = tid,
            authorName = authorLink?.text()?.trim().orEmpty(),
            authorUid = UID_IN_HREF.find(authorLink?.attr("href").orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            authorAvatarUrl = ForumUrls.absolute(
                avatar?.attr("comiis_loadimages")?.takeIf { it.isNotBlank() } ?: avatar?.attr("src"),
            ),
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
                avatarUrl = ForumUrls.absolute(a.selectFirst("img")?.attr("src")),
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
                avatarUrl = ForumUrls.absolute(el.selectFirst("img.msg_avt")?.attr("src")),
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

    fun parseFavorites(html: String): List<ForumFavorite> = parseFavorites(Jsoup.parse(html))

    private fun parseFavorites(doc: Document): List<ForumFavorite> =
        doc.select("li.mysclist_li").mapNotNull { li ->
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

    /**
     * One page of favourites plus the session `formhash` and the server's own page count.
     *
     * The list itself is [parseFavorites]; `formhash` is only present on this page (inside the
     * logout link), which is why the delete flow does not need a separate request.
     */
    fun parseFavoritesPage(html: String, page: Int = 1): ForumFavoritesPage {
        val doc = Jsoup.parse(html)
        return ForumFavoritesPage(
            favorites = parseFavorites(doc),
            formhash = parseFormhash(html),
            page = page,
            totalPages = parseTotalPages(doc),
        )
    }

    // ---- User profile (个人主页) --------------------------------------------

    /**
     * Parses one user's space profile page
     * (`home.php?mod=space&uid={uid}&do=profile&mobile=2`).
     *
     * The plain `home.php?mod=space&uid=` URL only renders the 广播 shell (its feed is filled by
     * an AJAX call), so the profile fields come from the `do=profile` page. The page also renders
     * the *visitor's* sidebar (`span.user_tit`), which is why the name is read from
     * `div.comiis_space_info` instead of reusing that selector — the same mistake that once made
     * `getUserProfile` report a visitor as signed in.
     */
    fun parseUserProfile(html: String, uid: Int): ForumUserProfile {
        val doc = Jsoup.parse(html)
        val info = doc.selectFirst("div.comiis_space_info")
        val headerTexts = info?.select("p span")?.map { it.text().trim() }.orEmpty()
        val counters = doc.select("div.comiis_space_profileico li a span").map { it.text().trim() }
        val creditStats = doc.select("div.comiis_space_profilejf li").mapNotNull { li ->
            val value = li.selectFirst("span.f_0")?.text()?.trim() ?: return@mapNotNull null
            val label = li.text().removePrefix(li.selectFirst("span.f_0")?.text().orEmpty()).trim()
            if (label.isBlank()) null else ForumProfileStat(label = label, value = value)
        }
        val fields = doc.select("div.comiis_space_profile li").mapNotNull { li ->
            // The site prints an extra 二维码 row next to the real fields: an `a.profile_ewmbox`
            // whose only behaviour is a `javascript:;` handler that pops up a QR overlay drawn by
            // the template's own JS. There is nothing to render for it in a native client (the
            // overlay is not part of the HTML we receive), and it carries no value either — the
            // row's `div.profile_rs` holds two icon-font glyphs. Drop it here so the parser stays
            // honest about what it can actually deliver instead of surfacing an empty field.
            if (li.selectFirst("a.profile_ewmbox, .profile_ewm") != null) return@mapNotNull null
            val valueEl = li.selectFirst("div.profile_rs") ?: return@mapNotNull null
            val label = li.selectFirst("span")?.text()?.trim().orEmpty()
            if (label.isBlank()) null else ForumProfileField(label = label, value = valueEl.text().trim())
        }
        val follow = doc.selectFirst("a#followmod")
        val signature = doc.selectFirst("div.comiis_space_profile li div.profile_face")
            ?.text()?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("这家伙很懒") }
        return ForumUserProfile(
            uid = uid,
            name = info?.selectFirst("h2")?.text()?.trim().orEmpty(),
            avatarUrl = ForumUrls.absolute(info?.selectFirst("div.user_img img")?.attr("src")),
            levelText = info?.selectFirst("span.kmlevs.kmlv")?.text()?.trim()?.takeIf { it.isNotBlank() },
            levelTitle = info?.select("span.kmlev")?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() },
            credits = creditStats.firstOrNull { it.label == "积分" }?.value?.filterDigits()?.toIntOrNull(),
            popularity = headerTexts.countBySuffix("人气"),
            followingCount = headerTexts.countBySuffix("关注"),
            followerCount = headerTexts.countBySuffix("粉丝"),
            threadCount = counters.countByPrefix("帖子"),
            replyCount = counters.countByPrefix("回复"),
            friendCount = counters.countByPrefix("好友"),
            creditStats = creditStats,
            fields = fields,
            signature = signature,
            isFollowing = follow?.let {
                it.attr("href").contains("op=del") || it.text().contains("取消关注")
            },
        )
    }

    private fun List<String>.countBySuffix(suffix: String): Int? =
        firstOrNull { it.endsWith(suffix) }?.filterDigits()?.toIntOrNull()

    private fun List<String>.countByPrefix(prefix: String): Int? =
        firstOrNull { it.startsWith(prefix) }?.filterDigits()?.toIntOrNull()

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

    // ---- Daily sign-in (每日签到) -------------------------------------------

    /**
     * Reads the 签到 page (`k_misign-sign.html`).
     *
     * The page is one Card-sized screen from the `k_misign` plugin, laid out by the Comiis mobile
     * template exactly like a member profile header:
     *
     * - `div.comiis_space_info` → `div.user_img img` (avatar), `h2.fyy` (nickname),
     *   `p.fyy.comiis_tm` ("签到等级 Lv.8");
     * - `div.comiis_qdinfo ul li` → each row's label is `span.comiis_tm` and its value is the
     *   row's own remaining text (`今日排名` / `0`, `连续签到` / `2天`, `累计签到` / `271天`);
     * - `div.comiis_btnbox` → `<a id="signresult">签到</a>` while the day's sign-in is still
     *   available, and a plain `<a class="comiis_btn bg_b f_c">已签到</a>` afterwards. The id and
     *   the text both flip, so the flag is read from either signal instead of one exact markup.
     *
     * A guest never reaches this DOM: the site answers with the login form, and the repository
     * turns that into [ForumLoginRequiredException]. The DOM selectors live here only.
     */
    fun parseSignInState(html: String): ForumSignInState {
        val doc = Jsoup.parse(html)
        val info = doc.selectFirst("div.comiis_space_info")
        // Label → value pairs in the site's own order; the label is a nested span, the value is
        // whatever the row prints after it.
        val stats = doc.select("div.comiis_qdinfo ul li").mapNotNull { li ->
            val labelEl = li.selectFirst("span.comiis_tm") ?: return@mapNotNull null
            val label = labelEl.text().cleanTitle()
            val value = li.text().removePrefix(labelEl.text()).cleanTitle()
            if (label.isBlank()) null else label to value
        }.toMap()
        // While 签到 is still possible the template prints the id; once done it drops the id and
        // relabels the button. Either signal is enough, so a template change to one of them
        // cannot flip the flag to "not signed" on an already-signed page.
        val action = doc.selectFirst("div.comiis_btnbox a#signresult")
        val actionLabels = doc.select("div.comiis_btnbox a").map { it.text().cleanTitle() }
        val alreadySigned = action == null ||
            actionLabels.any { it.contains(SIGN_IN_DONE) }

        return ForumSignInState(
            alreadySignedToday = alreadySigned,
            signInLevel = info?.selectFirst("p.fyy.comiis_tm")?.text()?.cleanTitle()
                ?.let { SIGN_IN_LEVEL.find(it)?.groupValues?.get(1) },
            todayRank = stats["今日排名"]?.filterDigits()?.toIntOrNull(),
            streakDays = stats["连续签到"]?.filterDigits()?.toIntOrNull(),
            totalDays = stats["累计签到"]?.filterDigits()?.toIntOrNull(),
            nickname = info?.selectFirst("h2.fyy")?.text()?.cleanTitle()?.takeIf { it.isNotBlank() },
            avatarUrl = ForumUrls.absolute(info?.selectFirst("div.user_img img")?.attr("src")),
            formhash = parseFormhash(html),
        )
    }

    /**
     * Maps the sign-in call's answer (`plugin.php?id=k_misign:sign&operation=qiandao`) to an
     * outcome.
     *
     * The endpoint answers with the site's usual XML wrapper around one CDATA string, e.g.
     * `<?xml version="1.0" encoding="utf-8"?>\r\n<root><![CDATA[已签到]]></root>`. That payload is
     * the whole result and is meant to be shown to the user; only its wording classifies it.
     *
     * `已签到` is the observed already-signed reply. The fresh-success wording could not be
     * captured (the test account had already signed in), so anything that is neither "already
     * signed" nor a rejection is treated as [ForumSignInOutcome.Success] and surfaced verbatim.
     * A rejection (permission / login / flood control) must never be reported as a success.
     */
    fun parseSignInOutcome(raw: String): ForumSignInOutcome {
        val message = parseCdataMessage(raw)
            ?: extractNotice(raw)
            ?: raw.trim().takeIf { it.isNotBlank() && !it.contains("<") }
            ?: return ForumSignInOutcome.Failure(SIGN_IN_EMPTY_REPLY)
        return when {
            SIGN_IN_ALREADY.containsMatchIn(message) -> ForumSignInOutcome.AlreadySigned(message)
            SIGN_IN_FAILURE_WORDS.containsMatchIn(message) -> ForumSignInOutcome.Failure(message)
            else -> ForumSignInOutcome.Success(message)
        }
    }

    /**
     * Extracts the CDATA payload of the site's ajax endpoints.
     *
     * Several plugin endpoints answer `<?xml version="1.0"?>\r\n<root><![CDATA[…] ]]></root>`
     * instead of HTML or JSON; the wrapper is identical every time, so the extraction is kept in
     * one place (see docs/REVERSE_SPEC.md §22).
     */
    fun parseCdataMessage(raw: String): String? =
        CDATA.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    // ---- Drafts (草稿) -------------------------------------------------------

    /**
     * Rows of the 草稿 list (`forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2`).
     *
     * The list reuses the guide/board row DOM (`li.forumlist_li`), so the same furniture shows up:
     * the title comes from `div.mmlist_li_box h2 a`, the body preview from `div.list_body a`, the
     * board from `div.comiis_xznalist_bk a` (`forum-<fid>-1.html`) and the relative time from
     * `div.forumlist_li_time span`. The row does not print the draft's `pid`; the edit form does.
     */
    fun parseDraftPage(html: String, page: Int = 1): ForumDraftPage {
        val doc = Jsoup.parse(html)
        return ForumDraftPage(
            drafts = doc.select("li.forumlist_li").mapNotNull { parseDraftRow(it) },
            page = page,
            totalPages = parseTotalPages(doc),
        )
    }

    /**
     * Row candidates in a 草稿 page (`li.forumlist_li`), before any field is read.
     *
     * Diagnostics only. Logging this next to the parsed count separates the two failure shapes a
     * device report can mean: "the site really served no drafts" (0 rows) and "the page had rows
     * but the row selectors stopped matching" (N rows, 0 parsed).
     */
    fun draftRowCount(html: String): Int = Jsoup.parse(html).select("li.forumlist_li").size

    private fun parseDraftRow(li: Element): ForumDraft? {
        val titleLink = li.selectFirst("div.mmlist_li_box h2 a")
            ?: li.selectFirst("h2 a[href*=thread-]")
            ?: return null
        val tid = TID_IN_HREF.find(titleLink.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val boardLink = li.selectFirst("div.comiis_xznalist_bk a[href*=forum-]")
            ?: li.selectFirst("a[href*=forum-]")
        val fid = FORUM_HREF.find(boardLink?.attr("href").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return ForumDraft(
            tid = tid,
            fid = fid,
            title = threadTitle(titleLink),
            excerpt = li.selectFirst("div.list_body a")?.text()?.let(::draftText)
                ?.takeIf { it.isNotBlank() },
            forumName = boardLink?.text()?.cleanTitle()?.takeIf { it.isNotBlank() },
            // The relative time is printed as `5&nbsp;分钟前`; jsoup keeps the NBSP, so it is
            // normalised or every timestamp would carry an invisible non-breaking space.
            timeText = (li.selectFirst("div.forumlist_li_time span")
                ?: li.selectFirst("span.kmtime"))?.text()?.let(::draftText)?.takeIf { it.isNotBlank() },
            threadUrl = ForumUrls.absolute(titleLink.attr("href")),
        )
    }

    /** Collapses the NBSP/whitespace the draft rows mix into their text. */
    private fun draftText(raw: String): String =
        raw.replace('\u00A0', ' ').replace(DRAFT_WHITESPACE, " ").trim()

    /** Post id of a draft's thread page (`div.comiis_postli#pid…`), i.e. the id its edit form needs. */
    fun draftThreadPid(html: String): Long? =
        Jsoup.parse(html).selectFirst("div.comiis_postli[id^=pid]")
            ?.let { PID_ID.find(it.id())?.groupValues?.get(1)?.toLongOrNull() }

    /**
     * True when a thread page carries the site's 草稿 badge (`span.top_jg` reading 草稿).
     *
     * The badge is what makes the draft case visible at all: the page otherwise renders like a
     * normal thread, because the site does not link drafts from their board.
     */
    fun isDraftThread(html: String): Boolean =
        Jsoup.parse(html).selectFirst("span.top_jg")?.text()?.contains("草稿") == true

    /**
     * The edit form of a draft (`forum.php?mod=post&action=edit&fid=&tid=&pid=&mobile=2`).
     *
     * Every field the form prints is harvested instead of hand-built: the server rejects (or
     * silently ignores) an incomplete field set, and the three submit paths — 发表 (`save` empty),
     * 保存草稿 (`save=1`) and 删除 (`delete=1`) — are all "the same form with one field changed".
     * Disabled inputs are still kept (the mobile template marks `htmlon` disabled but Discuz
     * expects the field), unchecked checkboxes are dropped (a browser would not submit them), and
     * file/button inputs are skipped because they carry no value.
     *
     * [tid]/[fid]/[pid] are request-side fallbacks for a template variant that omits the hidden ids.
     */
    fun parseDraftEdit(html: String, tid: Int, fid: Int, pid: Long? = null): ForumDraftEdit {
        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form#postform") ?: doc.selectFirst("form[action*=editsubmit]")
        val fields = form?.let(::formFields).orEmpty()
        val subject = fields["subject"] ?: form?.selectFirst("input[name=subject]")?.attr("value").orEmpty()
        val message = fields["message"] ?: form?.selectFirst("textarea[name=message]")?.text().orEmpty()
        val action = form?.attr("action")?.trim().orEmpty().ifBlank { EDIT_FALLBACK_ACTION }
        return ForumDraftEdit(
            tid = fields["tid"]?.toIntOrNull() ?: tid,
            fid = fields["fid"]?.toIntOrNull() ?: fid,
            pid = fields["pid"]?.toLongOrNull() ?: pid,
            subject = subject,
            message = message,
            form = ForumEditForm(
                action = normalizeFormAction(action),
                formhash = fields["formhash"].orEmpty(),
                tid = fields["tid"]?.toIntOrNull() ?: tid,
                fid = fields["fid"]?.toIntOrNull() ?: fid,
                pid = fields["pid"]?.toLongOrNull() ?: pid,
                subject = subject,
                message = message,
                fields = fields,
            ),
        )
    }

    /** Every submittable field of [form], in document order (later duplicates do not overwrite). */
    private fun formFields(form: Element): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        form.select("input[name], textarea[name], select[name]").forEach { el ->
            val name = el.attr("name").trim()
            if (name.isEmpty()) return@forEach
            when (el.tagName()) {
                "textarea" -> fields[name] = el.text()
                "select" -> {
                    val selected = el.select("option[selected]").firstOrNull()
                        ?: el.select("option").firstOrNull()
                    selected?.let { fields[name] = it.attr("value") }
                }

                else -> {
                    when (el.attr("type").lowercase()) {
                        "file", "button", "submit", "image", "reset" -> return@forEach
                        "checkbox", "radio" -> if (!el.hasAttr("checked")) return@forEach
                    }
                    fields[name] = el.attr("value")
                }
            }
        }
        return fields
    }

    /**
     * Makes a form action usable as a `ForumHttpClient` path.
     *
     * The mobile template prints either `forum.php?…` or an absolute `https://bbs.binmt.cc/…` URL,
     * and the client's POST helpers take a site-relative path; `mobile=2` is kept because it is
     * what selects the mobile template this client parses.
     */
    private fun normalizeFormAction(action: String): String {
        val path = action.removePrefix(ForumHttpClient.BASE_URL)
            .replace("https://bbs.binmt.cc/", "")
            .removePrefix("/")
        return if (path.contains("mobile=2")) path else "$path&mobile=2"
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

    /**
     * Best-effort extraction of a Discuz notice/message body (`提示信息` pages).
     *
     * The **primary message containers are searched first, as a group**, and the generic
     * `p.alert` / `div.alert_info` chrome only afterwards. A single combined selector would
     * return whichever element appears first in the document, so a page that paints an unrelated
     * alert above its message would be read as the alert — that is how a success page's chrome
     * could be mistaken for a failure notice (e.g. a "帖子不存在" template string sitting in a
     * hidden alert while the real message is 主题删除成功).
     */
    fun extractNotice(html: String): String? {
        val doc = Jsoup.parse(html)
        val primary = doc.selectFirst(
            "div#messagetext, div.messagetext, div.comiis_msgbox div.msg_mes, " +
                // The Comiis mobile 提示信息 page (the attachcredit handshake) prints its one line in
                // `div.comiis_password_top > p`. Selecting the parent `div` instead would also pick
                // up the icon-font glyph of `div.comiis_password_ico`, which then leaks into the
                // user-facing error text. Captured sample: `attach_paid_credit.html`.
                "div.comiis_password_top p",
        )
        val el = primary ?: doc.selectFirst("p.alert, div.alert_info")
        // The template indents the sentence with runs of spaces (and glues a NBSP before ，
        // on some pages), which would otherwise leak into the user-facing error string.
        return el?.text()?.cleanTitle()?.replace('\u00A0', ' ')?.replace(NOTICE_WHITESPACE, " ")?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    // ---- Paid-attachment handshake (attachcredit) --------------------------
    //
    // Discuz answers `mod=attachment&aid=…` for a *paid* attachment with a 302 to
    // `mod=misc&action=attachcredit&aid=…&formhash=…&mobile=2`. That page is a 提示信息 page whose
    // only useful content is a `点击此链接进行跳转` anchor carrying a **regenerated** aid hash and a
    // `ck` token — following it is what actually returns the file. See REVERSE_SPEC §23.

    /** Text of the continue anchor on the attachcredit page. */
    private const val ATTACH_CREDIT_TEXT = "点击此链接进行跳转"

    /**
     * True when [html] (served from [finalUrl], when known) is the paid-attachment handshake page.
     *
     * The page is identified by its URL (`action=attachcredit`), by the word `attachcredit` in the
     * markup, or by its continue-anchor text; any one is enough, because the several Discuz mobile
     * variants do not all keep the action in the body.
     */
    fun isAttachCreditPage(html: String, finalUrl: String? = null): Boolean =
        finalUrl?.contains("action=attachcredit", ignoreCase = true) == true ||
            html.contains("attachcredit", ignoreCase = true) ||
            html.contains(ATTACH_CREDIT_TEXT)

    /**
     * The continue link on the attachcredit page.
     *
     * Jsoup already decodes `&amp;` in the attribute, so the caller gets the URL as the browser
     * would use it, `ck` token included. Resolution against the page URL is the caller's job.
     *
     * @return the raw `href`, or null when this is not an attachcredit page or it carries no link
     *   (an outright refusal, e.g. 金币不足, is a failure notice and must not be followed).
     */
    fun attachCreditContinueUrl(html: String): String? {
        if (!isAttachCreditPage(html)) return null
        val doc = Jsoup.parse(html)
        val anchors = doc.select("a[href]")
        val anchor = anchors.firstOrNull { it.text().contains(ATTACH_CREDIT_TEXT) }
            ?: anchors.firstOrNull { it.attr("href").contains("mod=attachment", ignoreCase = true) }
        return anchor?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** True when [html] is the `acw_sc__v2` JS stub (the interceptor normally solves it first). */
    fun isAcwChallenge(html: String): Boolean =
        html.contains("acw_sc__v2") || html.contains("var arg1=")

    /**
     * True when a `提示信息` body is a refusal rather than a handshake/progress notice.
     *
     * Used to surface the server's own wording (金币不足 / 抱歉 / 无法下载 / 请先登录 / 权限) instead
     * of retrying an attachment download blindly.
     */
    fun isAttachmentFailureNotice(html: String): Boolean {
        val notice = extractNotice(html) ?: return false
        return ATTACH_FAILURE_WORDS.containsMatchIn(notice)
    }

    /** Server wordings that mean "this attachment will not be delivered". */
    private val ATTACH_FAILURE_WORDS =
        Regex("""金币不足|抱歉|无法下载|请先登录|登录后|权限|不存在|已失效|购买|余额不足""")

    /** Extracts a thread id from a success redirect (`thread-123-1-1.html`). */
    fun resultTid(html: String): Int? = TID_RESULT.find(html)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Board name of a thread page.
     *
     * The Comiis mobile template prints the board as the header crumb `a.kmtit` (linking to
     * `forum-{fid}-1.html`); the desktop template uses `#pt` and a `div.comiis_nav` crumb. When a
     * template renders no crumb at all, the `<title>标题 - 版块 - MT论坛</title>` shape still carries
     * the board as the second segment. `MT论坛` is the site name, never a board.
     */
    private fun parseForumName(doc: Document): String? {
        val crumb = doc.selectFirst("a.kmtit[href*=forum-]")
            ?: doc.selectFirst("#pt a[href*=forum-]")
            ?: doc.selectFirst("div.comiis_nav a[href*=forum-]")
            ?: doc.selectFirst("h2.flex a[href*=forum-]")
        crumb?.text()?.cleanTitle()?.takeIf { it.isNotBlank() }?.let { return it }

        val title = doc.selectFirst("title")?.text().orEmpty()
            .removeSuffix(" - MT论坛")
            .trim()
        val parts = title.split(" - ").map { it.trim() }.filter { it.isNotBlank() }
        return parts.getOrNull(1)?.takeIf { it != "MT论坛" && it.isNotBlank() }
    }

    private fun parseTotalPages(doc: Document): Int {
        val last = doc.select("div.pg a").mapNotNull { it.text().filterDigits().toIntOrNull() }.maxOrNull()
        return (last ?: 1).coerceAtLeast(1)
    }

    private fun String.filterDigits(): String = filter { it.isDigit() }

    /** Drops the icon-font glyphs (U+E000..U+F8FF) the mobile template inlines into text. */
    private fun String.cleanTitle(): String =
        replace(ICON_GLYPHS, "").trim()

    private val ICON_GLYPHS = Regex("[\\uE000-\\uF8FF]")

    // ---- 提醒 (notice inbox) -------------------------------------------------

    /**
     * Rows of one 提醒 tab (`home.php?mod=space&do=notice&view=<tab>&mobile=2`).
     *
     * The site renders all three tabs with one template, so the selectors are shared: rows are
     * `div.comiis_notice_list ul li`. The `type=` sub-tabs (帖子 / 点评 / 提到我的, 打招呼 / 好友 /
     * 留言 …) live in `#comiis_sub li`, outside the list container, which is why scoping the
     * query to `div.comiis_notice_list` keeps them from being mistaken for rows — worth an
     * explicit anchor because the *page containing those sub-tabs* otherwise looks like a list.
     *
     * An empty tab prints `div.comiis_notip` (“暂时没有提醒内容”) instead of a list, so it parses
     * to an empty [ForumNoticePage] and the screen shows its own empty card.
     */
    fun parseNotices(html: String, tab: ForumNoticeTab, page: Int): ForumNoticePage {
        val doc = Jsoup.parse(html)
        val notices = doc.select("div.comiis_notice_list ul li").mapNotNull { parseNoticeRow(it) }
        return ForumNoticePage(
            tab = tab,
            page = page,
            totalPages = parseTotalPages(doc),
            notices = notices,
        )
    }

    /** One `li` of the notice list; null when it carries no body (the template's spacers). */
    private fun parseNoticeRow(li: Element): ForumNotice? {
        val body = li.selectFirst("div.ntc_body") ?: return null
        val authorLink = body.selectFirst("a[href*=space-uid-]")
        val authorName = authorLink?.text()?.cleanTitle().orEmpty()
        // A notice links to a post via `ptid=<tid>` (the title anchor first, the trailing 查看
        // second); notices that target a profile/wall keep the row non-navigable instead of
        // opening a thread with a guessed id.
        val threadLink = body.select("a[href]").firstOrNull { NOTICE_PTID.containsMatchIn(it.attr("href")) }
        val tid = threadLink
            ?.let { NOTICE_PTID.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        val threadTitle = threadLink?.text()?.cleanTitle().orEmpty()

        // Render the body without the author (shown on its own line) and without the redundant
        // 查看 anchor (the whole row is the tap target); the remaining text is the preview.
        val preview = body.clone().apply {
            select("a[href*=space-uid-]").remove()
            select("a.lit").remove()
        }
        val previewText = preview.text().let(::normalizeNoticeText)

        return ForumNotice(
            id = li.selectFirst("a[id^=a_note_]")
                ?.attr("id")
                ?.let { NOTICE_ID.find(it)?.groupValues?.get(1)?.toLongOrNull() },
            kind = noticeKind(previewText, hasAuthor = authorName.isNotBlank()),
            authorName = authorName,
            authorAvatarUrl = ForumUrls.absolute(li.selectFirst("a.notice_img img")?.attr("src")),
            contentHtml = preview.html(),
            timeText = li.selectFirst("h2.f_d")?.text()?.let(::normalizeNoticeText)?.ifBlank { null },
            tid = tid,
            threadTitle = threadTitle,
            // The live page prints no unread marker: all 13 rows of a real 2026-11-26
            // `view=mypost` page are `<li class="b_b bg_f cl">`, and the 系统/坛友互动 tabs were
            // empty. Keeping false is the honest reading; see ForumNotice.unread.
            unread = false,
        )
    }

    private fun noticeKind(text: String, hasAuthor: Boolean): ForumNoticeKind = when {
        !hasAuthor -> ForumNoticeKind.System
        text.contains("回复") -> ForumNoticeKind.Reply
        text.contains("点评") -> ForumNoticeKind.Comment
        text.contains("提到") -> ForumNoticeKind.Mention
        text.contains("赞") || text.contains("挺") -> ForumNoticeKind.Like
        else -> ForumNoticeKind.Other
    }

    /** Drops the icon-font glyphs / NBSP the template mixes into the time and body text. */
    private fun normalizeNoticeText(raw: String): String =
        raw.cleanTitle()
            .replace('\u00A0', ' ')
            .replace(NOTICE_WHITESPACE, " ")
            .trim()

    private val NOTICE_PTID = Regex("""ptid=(\d+)""")
    private val NOTICE_ID = Regex("""a_note_(\d+)""")
    private val NOTICE_WHITESPACE = Regex("""\s+""")

    /** Lazy-load attribute the Comiis template puts the real image URL in. */
    private const val LAZY_IMAGE_ATTR = "comiis_loadimages"

    /** Label the site prints on the first 主题分类 tab, which is the unfiltered listing. */
    private const val CATEGORY_ALL_LABEL = "全部"

    // ---- 每日签到 (k_misign) ------------------------------------------------

    /** `签到等级 Lv.8` → `Lv.8`; the page sometimes drops the space. */
    private val SIGN_IN_LEVEL = Regex("""签到等级\s*(\S+)""")

    /** Label the button carries once today's sign-in is done. */
    private const val SIGN_IN_DONE = "已签到"

    /** The observed already-signed reply, plus the wordings the plugin has used around it. */
    private val SIGN_IN_ALREADY = Regex("""已签到|已经签到|今天已经签|今日已签""")

    /** Words that mark a rejected sign-in (permission / login / flood control). */
    private val SIGN_IN_FAILURE_WORDS = Regex("""抱歉|非法|错误|不能|失败|无权限|权限不足|间隔|禁止|限制|请先登录|登录后""")

    /** Fallback when the endpoint answers with an empty body or a bare error page. */
    private const val SIGN_IN_EMPTY_REPLY = "签到接口没有返回内容"

    /** The XML/CDATA wrapper every plugin ajax endpoint answers with. */
    private val CDATA = Regex("""<!\[CDATA\[(.*?)\]\]>""", setOf(RegexOption.DOT_MATCHES_ALL))

    // ---- Drafts -------------------------------------------------------------

    /** Edit-form action the mobile template prints; used when the form element itself is missing. */
    private const val EDIT_FALLBACK_ACTION =
        "forum.php?mod=post&action=edit&extra=&editsubmit=yes&mobile=2"

    private val DRAFT_WHITESPACE = Regex("""\s+""")
}

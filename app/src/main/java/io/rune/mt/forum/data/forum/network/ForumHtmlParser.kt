// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumCategory
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
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
        )
    }

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
}

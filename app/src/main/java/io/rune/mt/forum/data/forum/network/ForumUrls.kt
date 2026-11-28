// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import io.rune.mt.forum.domain.forum.model.ForumGuideView

/**
 * Normalises the image references found in the site's HTML into absolute http(s) URLs.
 *
 * The Comiis mobile template mixes four shapes for the same kind of asset:
 * `https://cdn.binmt.cc/...` (the common case), `//cdn.binmt.cc/...` (protocol relative),
 * `/data/attachment/forum/...` (root relative) and `data/attachment/forum/...`
 * (document relative). Coil treats a model without a scheme as an opaque cache key rather than a
 * URL and fails the request with "no scheme", so anything that reaches the loader has to be
 * absolute.
 *
 * Non-navigable schemes (`javascript:`, `file:`, `data:`) return `null` on purpose: the template
 * uses `javascript:;` for image action buttons and `file:` for lazy-load placeholders, and
 * welding those onto the site host produced URLs like `https://bbs.binmt.cc/javascript:;` that
 * could never load (and rendered as a blank picture block).
 */
internal object ForumUrls {

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** Hosts the site serves its public assets from. */
    private val CDN_HOSTS = setOf("cdn.binmt.cc", "icdn.binmt.cc", "oos.binmt.cc")

    /** Path prefixes that also exist on the forum host itself (Discuz upload tree). */
    private val SITE_PATHS = listOf("/data/attachment/", "/uc_server/", "/template/", "/static/")

    /**
     * Resolves an `<a href>` to a **navigable** target, keeping non-http schemes the browser can
     * still open.
     *
     * [absolute] is tuned for pictures: it drops every scheme but http(s). A post's `[email]` tag
     * renders to `<a href="mailto:…">` and `[qq]` to a `tel:`-like chat URL; those are real,
     * tappable links and must keep their scheme. `mailto:`/`tel:` are passed through verbatim,
     * everything else goes through [absolute] (so `javascript:`/`file:`/`data:` action buttons
     * still resolve to null).
     */
    fun navigable(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val lower = value.lowercase()
        if (lower.startsWith("mailto:") || lower.startsWith("tel:")) return value
        return absolute(value)
    }

    /** Resolves [raw] against the forum root; returns `null` when it cannot be fetched by Coil. */
    fun absolute(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("https://", ignoreCase = true) ||
                value.startsWith("http://", ignoreCase = true) -> value
            // Any other scheme (javascript:, file:, data:, mailto: ...) is not a picture.
            SCHEME.containsMatchIn(value) -> null
            value.startsWith("/") -> ForumHttpClient.BASE_URL + value.removePrefix("/")
            else -> ForumHttpClient.BASE_URL + value
        }
    }

    /**
     * Site path of one 导读 list page.
     *
     * The site's guide tab links all carry `index=1`; page one also works without `page`, but
     * keeping both parameters explicit is what the tab links do and is the only shape verified
     * against the live site. `view=hot` is a real guide list (not a portal redirect).
     */
    fun guidePath(view: ForumGuideView, page: Int): String =
        "forum.php?mod=guide&view=${view.value}&index=1&page=$page"

    /**
     * One page of one 提醒 tab.
     *
     * The live site selects the tab with `view=` (not the desktop template's `filter=`), and the
     * tab pages are the ones that actually render the notice rows: the bare
     * `do=notice&mobile=2` URL is the 消息提醒 hub listing (我的消息 / 我的粉丝 / …), not a list.
     */
    fun noticePath(tab: io.rune.mt.forum.domain.forum.model.ForumNoticeTab, page: Int): String =
        "home.php?mod=space&do=notice&view=${tab.value}&page=$page&mobile=2"

    /**
     * One page of the signed-in user's 草稿 list.
     *
     * `filter=save` is the draft filter of the 我的帖子 guide view; the list is a normal guide list
     * (same rows as a board), paginated with `page=`. Guests get the login form, not an empty list.
     */
    fun draftListPath(page: Int): String =
        "forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2" +
            if (page > 1) "&page=$page" else ""

    /**
     * The draft's own thread page (`thread-<tid>-1-1.html`, the same shape the list row links to).
     *
     * It is the only page that reveals the draft's `pid` (`div.comiis_postli#pid…`), which the
     * edit page needs before it can render a form — Discuz answers `post_nonexistence`
     * (「帖子不存在」) when `pid` is missing.
     */
    fun draftThreadPath(tid: Int): String = "thread-$tid-1-1.html"

    /**
     * The edit page of a draft — the only place that prints the draft's `pid` and the full hidden
     * field set the edit POST needs.
     */
    fun draftEditPath(fid: Int, tid: Int, pid: Long?): String = buildString {
        append("forum.php?mod=post&action=edit&fid=").append(fid)
        append("&tid=").append(tid)
        if (pid != null && pid > 0) append("&pid=").append(pid)
        append("&page=1&mobile=2")
    }

    /**
     * Retry candidate for a CDN image that failed to load.
     *
     * Discuz keeps the same file under the forum host (`/data/attachment/...`, `/uc_server/...`),
     * and that copy goes through the session-aware client, so a cookie-gated attachment that the
     * anonymous CDN request could not return has a second chance. CDN-only paths (e.g.
     * `icdn.binmt.cc/2509/xxxx.gif`) have no site equivalent and are left alone.
     */
    fun imageFallback(url: String?): String? {
        val value = absolute(url) ?: return null
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
        if (uri.host !in CDN_HOSTS) return null
        val path = uri.rawPath.orEmpty()
        if (SITE_PATHS.none { path.startsWith(it) }) return null
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return ForumHttpClient.BASE_URL + path.removePrefix("/") + query
    }
}
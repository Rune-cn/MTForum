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
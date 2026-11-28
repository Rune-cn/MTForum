// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import io.rune.mt.forum.data.forum.model.ForumSmilies
import io.rune.mt.forum.data.forum.network.ForumUrls
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import timber.log.Timber

/**
 * Flattens Discuz post HTML into [ForumBodyBlock]s.
 *
 * The site stores posts as **BBCode** and the Comiis mobile template renders them to **HTML**
 * server-side, so the client receives HTML and the job here is to render it faithfully — no
 * WebView, no third-party rich-text engine. Real threads exercise far more than inline styling:
 * code panels (`div.comiis_blockcode`), heading sizes (`font[size]`), quotes, `hr`, lists, tables,
 * attachment cards and the template's own notes (moderator action, edit remark). Each of those is
 * given its own block type rather than being flattened into `<li>`-per-paragraph prose.
 *
 * [parse] is a pure function of (html, linkColor); callers memoise it per post.
 */
internal object ForumBodyParser {

    private const val TAG = "ForumBodyParser"
    private const val SMILEY_PATH = "static/image/smiley"

    /** Tags whose boundaries start a new paragraph when they are not handled as a richer block. */
    private val BLOCK_TAGS = setOf(
        "div", "p", "li", "ul", "ol", "tr", "table", "section", "h1", "h2", "h3", "h4",
        "pre", "blockquote", "tbody", "thead",
    )

    /** Tags whose contents are never shown as text. */
    private val SKIP_TAGS = setOf("script", "style", "iframe", "audio", "video", "noscript")

    private val BLANK_RUN = Regex("[\\t\\x0B\\f\\r ]+")

    /** `<font size>` values Discuz uses for a heading hierarchy. */
    private val HEADING_SIZES = setOf(6, 5, 4, 2)

    /** Recognised HTML tag names, used to tell a rendered body from a raw BBCode draft. */
    private val HTML_TAG = Regex("""<\s*([a-zA-Z][a-zA-Z0-9]*)\b""")
    private val HTML_TAGS = setOf(
        "div", "br", "p", "span", "a", "img", "font", "ul", "ol", "li", "table", "thead", "tbody",
        "tr", "td", "th", "blockquote", "hr", "strong", "b", "i", "u", "em", "s", "strike", "del",
        "pre", "code", "section", "h1", "h2", "h3", "h4", "script", "style", "iframe", "video",
        "audio", "sub", "sup", "small", "big", "center", "dl", "dt", "dd",
    )

    fun parse(
        html: String,
        linkColor: Color = Color(0xFF3482FF),
        linkHandler: ((String) -> Unit)? = null,
    ): List<ForumBodyBlock> {
        if (html.isBlank()) return emptyList()
        val document = runCatching { Jsoup.parseBodyFragment(prepare(html)) }
            .onFailure { e -> Timber.tag(TAG).w(e, "post html unparsable") }
            .getOrNull()
        if (document == null) return listOf(ForumBodyBlock.Paragraph(AnnotatedString(html.trim())))

        val walker = Walker(linkColor, linkHandler)
        document.body().childNodes().forEach(walker::walkRoot)
        return walker.finish()
    }

    /**
     * Mutable walk state.
     *
     * A class (not a local closure) because the recursive visitor needs to reach the paragraph
     * and emoji buffers that the flush points also touch; Kotlin local functions cannot forward
     * reference each other. A second instance collects the *nested* blocks of a quote or of an
     * `[align]` wrapper, so those constructs keep real structure instead of degrading to prose.
     */
    private class Walker(
        private val linkColor: Color,
        /**
         * Host handler for an inline `<a href>`.
         *
         * When null the link annotation carries no listener, which makes Compose fall back to its
         * own `UriHandler` — the same ACTION_VIEW path the rest of the app uses for site URLs, so
         * the site's deep-link intent filters still get a chance. A caller that wants its own
         * routing (e.g. an in-app browser) passes a handler here instead of getting colour only.
         */
        private val linkHandler: ((String) -> Unit)? = null,
        /** Elements whose subtree must be dropped (quote author/byline labels). */
        private val skip: (Element) -> Boolean = { false },
    ) {

        private val blocks = mutableListOf<ForumBodyBlock>()
        private val emoji = mutableListOf<String>()
        private var text = AnnotatedString.Builder()
        private var paragraphAlign: TextAlign? = null

        fun walkRoot(node: Node) = walkBlock(node, style = null)

        /** Walks [nodes] through this walker, so the caller can harvest the finished blocks. */
        fun walkAll(nodes: List<Node>) {
            nodes.forEach { walkBlock(it, style = null) }
        }

        private fun flush() {
            if (emoji.isNotEmpty()) {
                blocks += ForumBodyBlock.EmojiRow(emoji.toList())
                emoji.clear()
            }
            val value = normalize(text.toAnnotatedString())
            if (value.isNotBlank()) blocks += ForumBodyBlock.Paragraph(value, paragraphAlign)
            text = AnnotatedString.Builder()
        }

        fun finish(): List<ForumBodyBlock> {
            flush()
            return blocks
        }

        // ---- Block-level dispatch ---------------------------------------------

        private fun walkBlock(node: Node, style: SpanStyle?) {
            when (node) {
                is TextNode -> appendWithSmilies(text, node.text().replace(BLANK_RUN, " "), style)

                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        skip(node) -> Unit

                        // Media embeds (`[media]`/`[audio]`/`[flash]`) render on the site as an
                        // `<iframe>` or a script-injected player that a native renderer cannot
                        // play. Dropping them (`iframe`/`script` are skip tags) left a silent hole
                        // where the media was, so they become an honest card carrying the URL.
                        isMediaContainer(node) -> emitMedia(node)

                        isQqChatLink(node) -> emitQqLink(node)

                        tag in SKIP_TAGS -> Unit

                        tag == "br" -> text.append('\n')

                        tag == "hr" -> {
                            flush()
                            blocks += ForumBodyBlock.Divider
                        }

                        tag == "img" -> handleImage(node)

                        // Richer constructs must be matched before the generic block-tag branch,
                        // otherwise their inner `<li>`/`<tr>`/`<div>` become loose paragraphs.
                        node.hasClass("comiis_blockcode") -> emitDiscuzCodeBlock(node)

                        node.hasClass("comiis_quote") -> emitQuote(node.selectFirst("blockquote") ?: node)

                        node.hasClass("comiis_attach") -> emitAttachment(node)

                        node.hasClass("comiis_modact") -> emitModeratorNote(node)

                        node.hasClass("pstatus") -> emitEditNote(node)

                        tag == "blockquote" -> emitQuote(node)

                        tag == "table" -> emitTable(node)

                        tag == "ul" || tag == "ol" -> emitList(node, listMarkerStyle(node), depth = 0)

                        tag == "pre" -> emitPreCode(node)

                        // An inline `<a>` inside a paragraph is the common case (every reference
                        // link in a post). It must be routed through the link-aware inline walk
                        // here too — otherwise only links inside quotes/list items/cells became
                        // tappable, and the paragraph links stayed plain coloured text.
                        tag == "a" -> {
                            val href = ForumUrls.navigable(node.attr("href"))
                            val start = text.length
                            val next = inlineStyle(tag, node) ?: style
                            node.childNodes().forEach { walkBlock(it, next) }
                            if (href != null && text.length > start) {
                                text.addLink(linkAnnotation(href), start, text.length)
                            }
                        }

                        isHeadingFont(node) -> emitHeading(node)

                        // `[align=center]` wraps a whole *block*, and the wrapper may hold a list,
                        // a table or a run of paragraphs. Emitting an aligned group keeps that
                        // structure instead of only recolouring each paragraph's own textAlign.
                        (tag == "div" || tag == "p" || tag == "section") && blockAlignOf(node) != null ->
                            emitAligned(node)

                        tag in BLOCK_TAGS -> {
                            flush()
                            node.childNodes().forEach { walkBlock(it, style) }
                            text.append('\n')
                        }

                        else -> {
                            val next = inlineStyle(tag, node) ?: style
                            node.childNodes().forEach { walkBlock(it, next) }
                        }
                    }
                }

                else -> Unit
            }
        }

        // ---- Richer block emitters --------------------------------------------

        /** One code panel: every `<li>` is a line, and a line may itself contain `<br>`. */
        private fun emitDiscuzCodeBlock(block: Element) {
            flush()
            val lines = mutableListOf<String>()
            val listItems = block.select("ol > li")
            if (listItems.isNotEmpty()) {
                listItems.forEach { li ->
                    val collected = StringBuilder()
                    appendRawText(li, collected)
                    // Each `<li>` is one visual line; the template appends a trailing `<br>` that
                    // merely separates it from the next item, so drop exactly one trailing break.
                    val raw = collected.toString().removeSuffix("\n")
                    splitLines(raw, lines)
                }
            } else {
                val collected = StringBuilder()
                appendRawText(block.selectFirst(".b_l") ?: block, collected)
                splitLines(collected.toString(), lines)
            }
            blocks += ForumBodyBlock.CodeBlock(trimCode(lines))
        }

        /** A `[code]` draft converted to `<pre><code>`: text is already escaped for display. */
        private fun emitPreCode(pre: Element) {
            flush()
            val source = pre.selectFirst("code") ?: pre
            val lines = mutableListOf<String>()
            splitLines(source.wholeText(), lines)
            blocks += ForumBodyBlock.CodeBlock(trimCode(lines))
        }

        private fun emitHeading(font: Element) {
            flush()
            val size = font.attr("size").trim().toIntOrNull() ?: return
            val builder = AnnotatedString.Builder()
            font.childNodes().forEach { walkInline(it, builder, null) }
            val value = normalize(builder.toAnnotatedString())
            if (value.isNotBlank()) {
                blocks += ForumBodyBlock.Heading(value, headingLevel(size))
            }
        }

        /**
         * A quote keeps its *structure*: the Comiis template puts the whole quoted body inside
         * `blockquote`, and that body can itself hold a list, a table, a rule or a code panel.
         *
         * The author byline (`font[color=#999999]…发表于…`) and the leading 「回复」 label are
         * dropped from the body because the renderer shows the author as its own header. Discuz
         * truncates long quotes server-side and appends a literal ` ...`; that marker is stripped
         * and surfaced as [ForumBodyBlock.Quote.truncated] so the renderer can draw a real ellipsis
         * instead of the raw ASCII dots.
         */
        private fun emitQuote(quote: Element) {
            flush()
            val authorEl = quote.select("font").firstOrNull { it.text().contains("发表于") }
            val author = authorEl?.text()?.trim()?.takeIf { it.isNotBlank() }
            val replyLabel = quote.select("font[size=2]").firstOrNull { it.text().trim() == "回复" }

            val inner = Walker(linkColor, linkHandler, skip = { el -> el === authorEl || el === replyLabel })
            inner.walkAll(quote.childNodes())
            val innerBlocks = inner.finish()

            // The byline is gone, so the first paragraph is the start of the quoted body: it stays
            // as the quote's own `text` (that is what the inset rule renders) and every later
            // block — a list, table, picture, nested quote — rides along as a child.
            val first = innerBlocks.firstOrNull()
            val leading = (first as? ForumBodyBlock.Paragraph)?.text ?: AnnotatedString("")
            var children: List<ForumBodyBlock> =
                if (first is ForumBodyBlock.Paragraph) innerBlocks.drop(1) else innerBlocks

            var truncated = false
            val (body, leadingCut) = stripTruncationEllipsis(leading)
            if (leadingCut) truncated = true

            // Discuz truncates the *end* of a long quote; whichever block ends the body holds the
            // marker. Handling only the leading paragraph would leave `...` glued to a list row or
            // a trailing paragraph of a structured quote.
            val lastIndex = children.indexOfLast { it is ForumBodyBlock.Paragraph }
            if (lastIndex >= 0) {
                val last = children[lastIndex] as ForumBodyBlock.Paragraph
                val (trimmed, cut) = stripTruncationEllipsis(last.text)
                if (cut) {
                    truncated = true
                    children = children.toMutableList().also { list ->
                        list[lastIndex] = last.copy(text = trimmed)
                    }
                }
            }
            if (!truncated) {
                // A quote whose whole body is one text run has no children; its own `body` already
                // went through stripTruncationEllipsis above, so `leadingCut` covered it.
                truncated = leadingCut
            }

            blocks += ForumBodyBlock.Quote(
                text = body,
                author = author,
                truncated = truncated,
                children = children,
            )
        }

        /**
         * `ul`/`ol` — except an **image-only list**, which is really the album gallery.
         *
         * Discuz renders a post's 相册 (album) attachments as a list of *pictures*:
         * `<ul class="comiis_img_list"><li><span onclick=…><img …></span></li>…</ul>` (the
         * real markup is in `album_images_177269.html`). Treating every `ul` as a bullet list
         * dropped those images entirely — [collectListItems] only harvests text, and `ul` being a
         * block tag meant the `<img>` never reached the picture branch, so an album-only post
         * rendered as prose with no images at all. An image-only list now yields one
         * [ForumBodyBlock.Picture] per image, in document order.
         *
         * A list that mixes text and images keeps its text rows; the images follow the rows as
         * trailing pictures, because a [ForumBodyBlock.BulletList] row is text-only and cannot
         * carry a block. Image-only `<li>` rows produce no empty bullet in that case.
         */
        private fun emitList(list: Element, style: ListMarkerStyle, depth: Int) {
            flush()
            if (isImageGallery(list)) {
                list.select("img").forEach { handleImage(it) }
                return
            }
            val items = mutableListOf<ForumBodyBlock.ListItem>()
            collectListItems(list, style, depth, items)
            if (items.isNotEmpty()) blocks += ForumBodyBlock.BulletList(items)
            if (list.selectFirst("img") != null) {
                // Mixed list: the rows stay, the images it carried come right after them.
                list.select("img").forEach { handleImage(it) }
            }
        }

        /**
         * True for a list that is really a picture gallery.
         *
         * The template's own `comiis_img_list` class is authoritative. As a guard against
         * template variants the structure is also accepted when the list holds pictures and no
         * text of its own (`<li><span onclick=…><img …></span></li>`, or a bare `<li><img>`),
         * which no genuine bullet list looks like.
         */
        private fun isImageGallery(list: Element): Boolean {
            if (list.selectFirst("img") == null) return false
            if (list.hasClass("comiis_img_list")) return true
            return list.text().isBlank()
        }

        /**
         * Collects `li` rows recursively, so nesting is not limited to two levels.
         *
         * A `li` may put its own text *after* the nested list as well as before it; both runs are
         * appended to the same row. A `li` that carries nothing but a nested list does not get a
         * bullet of its own — the site renders it as a pure container.
         */
        private fun collectListItems(
            list: Element,
            style: ListMarkerStyle,
            depth: Int,
            out: MutableList<ForumBodyBlock.ListItem>,
        ) {
            var index = 0
            list.children().forEach { child ->
                if (child.tagName().lowercase() != "li") return@forEach
                index++
                val builder = AnnotatedString.Builder()
                val nested = mutableListOf<Element>()
                child.childNodes().forEach { node ->
                    val nestedTag = (node as? Element)?.tagName()?.lowercase()
                    if (nestedTag == "ul" || nestedTag == "ol") {
                        nested.add(node as Element)
                    } else {
                        walkInline(node, builder, null)
                    }
                }
                val value = normalize(builder.toAnnotatedString())
                // A row whose only content is a picture is not a bullet: the image is emitted as a
                // trailing Picture by [emitList] instead of leaving an empty marker behind.
                val hasImage = child.selectFirst("img") != null
                if (value.isNotBlank() || (nested.isEmpty() && !hasImage)) {
                    out.add(ForumBodyBlock.ListItem(marker = markerFor(style, index), text = value, depth = depth))
                }
                nested.forEach { nestedList ->
                    collectListItems(
                        nestedList,
                        style = listMarkerStyle(nestedList),
                        depth = depth + 1,
                        out = out,
                    )
                }
            }
        }

        /**
         * Orderedness of a `ul`/`ol` as the site actually renders it.
         *
         * Discuz's `[list=1]` / `[list=a]` do **not** become `<ol>`: the Comiis template emits
         * `<ul type="1" class="litype_1">` and `<ul type="a" class="litype_2">`. Treating every
         * `<ul>` as bulleted silently turned the tutorial's 「第一步/第二步」 and 「选项A/选项B」
         * lists into dots. `type`/the `litype_*` class (and a genuine `<ol>`) carry the style, and
         * nesting inherits whatever each inner list declares.
         */
        private fun listMarkerStyle(list: Element): ListMarkerStyle {
            val type = list.attr("type").trim().lowercase()
            val classes = list.className()
            return when {
                type == "a" || classes.contains("litype_2") -> ListMarkerStyle.ALPHA
                type == "1" || classes.contains("litype_1") -> ListMarkerStyle.NUMERIC
                list.tagName().equals("ol", ignoreCase = true) -> ListMarkerStyle.NUMERIC
                else -> ListMarkerStyle.BULLET
            }
        }

        private fun markerFor(style: ListMarkerStyle, index: Int): String = when (style) {
            ListMarkerStyle.BULLET -> "•"
            ListMarkerStyle.NUMERIC -> "$index."
            // `a.`/`b.`/… cycling after `z`, which is what Discuz's `[list=a]` does.
            ListMarkerStyle.ALPHA -> "${'a' + (index - 1) % 26}."
        }

        /**
         * `table/tr/th|td` with header semantics and merge spans.
         *
         * Discuz's `[table]` has no header row concept, but the site's authors fake one: the first
         * row is written as bold-only cells (`<td><div align="center"><strong>标题</strong></div></td>`
         * in `rich_156696.html`). That row is marked as a header so it is rendered like one, and a
         * real `<th>`/`<thead>` (other template variants) is honoured as well. `colspan`/`rowspan`
         * are carried as a weight so a merged cell keeps its width and the remaining cells do not
         * shift; no cell text is ever dropped. Each cell also records its own alignment from
         * `<td align>`, an inner `<div align>` or a CSS `text-align`, so the site's centred header
         * and short cells stay centred. Column *widths* are a renderer decision
         * (`tableColumnWeights` in `ForumHtmlText.kt`), so the model stays pixel-free.
         */
        private fun emitTable(table: Element) {
            flush()
            val rowElements = table.select("tr")
            val headerRows = rowElements.count { row ->
                row.parents().any { it.tagName().equals("thead", ignoreCase = true) }
            }
            val rows = rowElements.mapIndexed { rowIndex, tr ->
                tr.children()
                    .filter { it.tagName().lowercase() == "td" || it.tagName().lowercase() == "th" }
                    .map { cell ->
                        val builder = AnnotatedString.Builder()
                        cell.childNodes().forEach { walkInline(it, builder, null) }
                        val value = normalize(builder.toAnnotatedString())
                        ForumBodyBlock.TableCell(
                            text = value,
                            header = cell.tagName().equals("th", ignoreCase = true),
                            align = cellAlignOf(cell),
                            span = cell.attr("colspan").trim().toIntOrNull()?.coerceIn(1, 8) ?: 1,
                        )
                    }
                    .takeIf { it.isNotEmpty() }
            }.filterNotNull()

            if (rows.isEmpty()) return

            // A bold-only first row is the site's header idiom. Requiring *every* cell to be fully
            // bold keeps a genuinely emphasised data row from being promoted by accident.
            val alreadyHeader = headerRows > 0 || rows.first().any { it.header }
            val boldOnlyFirstRow = rows.first().all { cell ->
                cell.text.isNotBlank() && isFullyBold(cell.text)
            }
            val finalRows = if (!alreadyHeader && boldOnlyFirstRow) {
                listOf(rows.first().map { it.copy(header = true) }) + rows.drop(1)
            } else {
                rows
            }
            blocks += ForumBodyBlock.Table(finalRows)
        }

        private fun emitAttachment(attach: Element) {
            flush()
            val link = attach.selectFirst("a[href]")
            val url = ForumUrls.absolute(link?.attr("href"))
            val name = attach.selectFirst("span.f_ok")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: link?.selectFirst("p.attach_tit")?.text()?.trim()?.substringBefore("上传")?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: "附件"
            val sizeLine = attach.selectFirst("p.attach_size")?.text()?.trim()
            val caption = sizeLine?.takeIf { it.isNotBlank() }
            // `下载积分: 金币 -1` is the price hint Discuz prints on a paid attachment. The number is
            // the *deduction*, so its absolute value is the cost; 0/absent means "free" and the
            // chooser then skips the warning row entirely.
            val costCoins = sizeLine
                ?.let { ATTACH_COST.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?.let { kotlin.math.abs(it) }
                ?: 0

            // The card carries a *file-type* icon (`static/image/filetype/`) and, for image
            // attachments, sometimes a real preview thumbnail. Conflating the two turned the icon
            // into a giant picture block in earlier builds, so they are kept apart here.
            val images = attach.select("img")
            val iconUrl = images.firstOrNull { imageUrl(it)?.contains(FILETYPE_ICON_PATH) == true }
                ?.let { imageUrl(it) }
            val previewUrl = images.firstOrNull {
                val resolved = imageUrl(it) ?: return@firstOrNull false
                !resolved.contains(FILETYPE_ICON_PATH) && !isSmileySource(resolved)
            }?.let { imageUrl(it) }

            if (url != null) {
                blocks += ForumBodyBlock.Attachment(
                    name = name,
                    caption = caption,
                    url = url,
                    iconUrl = iconUrl,
                    previewUrl = previewUrl,
                    isImage = previewUrl != null || name.hasImageExtension(),
                    costCoins = costCoins,
                )
            } else {
                blocks += ForumBodyBlock.Paragraph(AnnotatedString(name))
            }
        }

        private fun emitModeratorNote(note: Element) {
            flush()
            val value = normalize(AnnotatedString(note.text().trim()))
            if (value.isNotBlank()) blocks += ForumBodyBlock.ModeratorNote(value)
        }

        private fun emitEditNote(note: Element) {
            flush()
            val value = normalize(AnnotatedString(note.text().trim()))
            if (value.isNotBlank()) blocks += ForumBodyBlock.EditNote(value)
        }

        /**
         * A media embed the native renderer cannot play (`[media]`, `[audio]`, `[flash]`).
         *
         * The site turns these into an `<iframe>`, a `<video>`/`<audio>`, or a player the template
         * injects via an inline `<script>` (`document…innerHTML="<iframe src=…">`). All of those are
         * skipped by [SKIP_TAGS], so the old walker left a silent gap. The card keeps the URL the
         * user can still open, which is the honest fallback for content we cannot play inline.
         */
        private fun emitMedia(element: Element) {
            val url = mediaUrlOf(element) ?: return
            flush()
            val label = when {
                element.hasClass("media") || element.selectFirst("audio") != null -> "音频"
                element.tagName().equals("video", ignoreCase = true) ||
                    element.selectFirst("video") != null -> "视频"
                isBilibili(url) -> "视频"
                else -> "媒体"
            }
            blocks += ForumBodyBlock.Media(url = url, label = label)
        }

        /**
         * A `[qq]` tag renders to `<a href="…wpa.qq.com…"><img …/qq_big.gif></a>` with no text.
         * Walking it as a picture produced a stray QQ icon block; it is a chat link, so it becomes
         * a tappable 「QQ <uin>」 line carrying the real href.
         */
        private fun emitQqLink(node: Element) {
            val href = ForumUrls.navigable(node.attr("href")) ?: return
            flush()
            val uin = QQ_UIN.find(node.attr("href"))?.groupValues?.get(1)
            val label = if (uin != null) "QQ $uin" else "QQ"
            val builder = AnnotatedString.Builder()
            val start = builder.length
            builder.append(label)
            builder.addLink(linkAnnotation(href), start, builder.length)
            blocks += ForumBodyBlock.Paragraph(builder.toAnnotatedString())
        }

        /** A `[media]`/`[audio]`/`[flash]` container that carries a recoverable URL. */
        private fun isMediaContainer(node: Element): Boolean = mediaUrlOf(node) != null

        private fun isQqChatLink(node: Element): Boolean =
            node.tagName().equals("a", ignoreCase = true) &&
                node.attr("href").contains("wpa.qq.com")

        /**
         * Recovers the URL behind a media embed.
         *
         * Three shapes appear in real posts: an audio player's `div.media` with a
         * 「如果无法播放，请点击此处…」 fallback `<a href>`; a `<video>`/`<audio>`/`<iframe>`/`<embed>`
         * with a `src`/`data`; and the Comiis media shell (`ignore_js_op`) whose `<script>` assigns
         * an `<iframe src='…'>` string. Order keeps the most specific source first.
         */
        private fun mediaUrlOf(element: Element): String? {
            val tag = element.tagName().lowercase()
            val direct = when (tag) {
                "iframe", "video", "audio", "embed" -> element.attr("src").takeIf { it.isNotBlank() }
                "object" -> element.attr("data").takeIf { it.isNotBlank() }
                else -> null
            }
            if (direct != null) return ForumUrls.absolute(direct)
            if (element.hasClass("media")) {
                element.selectFirst("a[href]")?.let { return ForumUrls.navigable(it.attr("href")) }
            }
            if (tag == "ignore_js_op" || element.hasClass("media")) {
                element.select("script").forEach { script ->
                    MEDIA_EMBED_SRC.find(script.data())?.let { match ->
                        return normalizeMediaUrl(match.groupValues[1])
                    }
                }
            }
            return null
        }

        private fun normalizeMediaUrl(raw: String): String? {
            val absolute = ForumUrls.absolute(raw) ?: return null
            // A bilibili player iframe can be rewritten to its watch page, which is what the
            // author actually linked; anything else is shown as-is.
            BILIBILI_BVID.find(absolute)?.let { return "https://www.bilibili.com/video/${it.groupValues[1]}" }
            return absolute
        }

        private fun isBilibili(url: String): Boolean = BILIBILI_BVID.containsMatchIn(url) || "bilibili.com" in url

        /**
         * `[align=…]` as a **block-level** container: the wrapper's children are collected into
         * their own block list and the whole group carries the alignment, so a centred list, table
         * or picture is centred as a unit instead of losing the wrapper entirely.
         */
        private fun emitAligned(wrapper: Element) {
            flush()
            val align = blockAlignOf(wrapper) ?: return
            val inner = Walker(linkColor)
            inner.walkAll(wrapper.childNodes())
            val innerBlocks = inner.finish().filterNot { it.isBlankParagraph() }
            if (innerBlocks.isNotEmpty()) {
                blocks += ForumBodyBlock.Aligned(align, innerBlocks)
            }
            text.append('\n')
        }

        // ---- Inline walk (quotes, list items, table cells, headings) ----------

        private fun handleImage(node: Element) {
            val url = imageUrl(node) ?: return
            if (isSmiley(node)) {
                emoji += url
                return
            }
            flush()
            blocks += ForumBodyBlock.Picture(url)
        }

        /**
         * Walks inline content into [out]. Block boundaries become line breaks so a quote or a
         * list item that wraps a table/list still degrades to readable text rows.
         *
         * `<a href>` becomes a [LinkAnnotation.Url] over its text run, which is what makes links
         * tappable in the rendered paragraph; a non-navigable href (`javascript:`, `file:`, …)
         * stays coloured text only.
         */
        private fun walkInline(
            node: Node,
            out: AnnotatedString.Builder,
            style: SpanStyle?,
            skip: (Element) -> Boolean = { false },
        ) {
            when (node) {
                is TextNode -> append(out, node.text().replace(BLANK_RUN, " "), style)

                is Element -> {
                    if (skip(node)) return
                    val tag = node.tagName().lowercase()
                    when {
                        tag in SKIP_TAGS -> Unit
                        tag == "br" -> out.append('\n')
                        tag == "img" -> {
                            val alt = node.attr("alt")
                            if (alt.isNotBlank()) out.append(alt)
                        }
                        tag == "a" -> {
                            val href = ForumUrls.navigable(node.attr("href"))
                            val start = out.length
                            val next = inlineStyle(tag, node) ?: style
                            node.childNodes().forEach { walkInline(it, out, next, skip) }
                            if (href != null && out.length > start) {
                                out.addLink(linkAnnotation(href), start, out.length)
                            }
                        }
                        tag in BLOCK_TAGS -> {
                            out.append('\n')
                            node.childNodes().forEach { walkInline(it, out, style, skip) }
                            out.append('\n')
                        }
                        else -> {
                            val next = inlineStyle(tag, node) ?: style
                            node.childNodes().forEach { walkInline(it, out, next, skip) }
                        }
                    }
                }

                else -> Unit
            }
        }

        /**
         * Appends text, splitting out any `[#xxx]` smiley tokens into emoji runs.
         *
         * Discuz keeps a smiley as that token in the stored post and only renders an `<img>` on
         * the server-side page; replies this client sends carry the same tokens, so the renderer
         * must resolve them or the user sees literal `[#呵呵]`.
         */
        private fun appendWithSmilies(out: AnnotatedString.Builder, value: String, style: SpanStyle?) {
            val pattern = Regex("""\[#[^\]]{1,20}]""")
            var last = 0
            pattern.findAll(value).forEach { match ->
                if (match.range.first > last) {
                    append(out, value.substring(last, match.range.first), style)
                }
                val smiley = ForumSmilies.byCode[match.value]
                if (smiley != null) emoji += smiley.url else append(out, match.value, style)
                last = match.range.last + 1
            }
            if (last < value.length) append(out, value.substring(last), style)
        }

        private fun append(out: AnnotatedString.Builder, value: String, style: SpanStyle?) {
            val start = out.length
            out.append(value)
            if (style != null) out.addStyle(style, start, out.length)
        }

        /** Maps the handful of inline tags Discuz emits onto Compose span styles. */
        private fun inlineStyle(tag: String, element: Element): SpanStyle? = when (tag) {
            "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
            "em", "i" -> SpanStyle(fontStyle = FontStyle.Italic)
            "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
            "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            "a" -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            "font" -> fontSpanStyle(element)
            else -> null
        }

        /**
         * `[color]` → `color`, `[backcolor]` → `style="background-color:…"`.
         *
         * The template writes a background as an inline CSS declaration, not an attribute, so
         * reading only `@color` silently dropped `[backcolor]`. Both survive on one `SpanStyle`.
         */
        private fun fontSpanStyle(element: Element): SpanStyle? {
            val color = parseHexColor(element.attr("color"))
            val background = cssColor(element.attr("style"), "background-color")?.let { parseHexColor(it) }
            if (color == null && background == null) return null
            return SpanStyle(
                color = color ?: Color.Unspecified,
                background = background ?: Color.Unspecified,
            )
        }

        /** Link annotation carrying the destination; the renderer binds the click handler. */
        private fun linkAnnotation(url: String): LinkAnnotation.Url = LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ),
            linkInteractionListener = linkHandler?.let { handler ->
                object : androidx.compose.ui.text.LinkInteractionListener {
                    override fun onClick(link: LinkAnnotation) {
                        (link as? LinkAnnotation.Url)?.url?.let(handler)
                    }
                }
            },
        )
    }

    /** Appends a node's text exactly as written, turning `<br>` into a newline (code blocks). */
    private fun appendRawText(node: Node, out: StringBuilder) {
        when (node) {
            // The template pretty-prints each `<li>` with a source newline + indentation before
            // its `<br>`; those literal newlines are formatting artifacts, not code line breaks
            // (real breaks come from `<li>`/`<br>`), so drop a newline and the indent after it.
            is TextNode -> out.append(node.wholeText.replace(RAW_NEWLINE, "").replace("\r", ""))
            is Element -> {
                if (node.tagName().lowercase() == "br") {
                    out.append('\n')
                } else {
                    node.childNodes().forEach { appendRawText(it, out) }
                }
            }
            else -> Unit
        }
    }

    private val RAW_NEWLINE = Regex("\\r?\\n[ \\t]*")

    private fun splitLines(raw: String, out: MutableList<String>) {
        raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace('\u00A0', ' ')
            .split('\n')
            .forEach { out += it }
    }

    /**
     * Normalises a code panel: `&nbsp;` became U+00A0 above, so turn it into a plain space;
     * trailing spaces are dropped (they never matter) but leading/interior spaces are preserved
     * because alignment matters. A template-wide common indent is stripped only when every
     * non-blank line shares it.
     */
    private fun trimCode(lines: List<String>): String {
        val trimmed = lines.map { it.trimEnd() }
        val first = trimmed.indexOfFirst { it.isNotBlank() }
        val last = trimmed.indexOfLast { it.isNotBlank() }
        if (first < 0 || last < 0) return ""
        val body = trimmed.subList(first, last + 1)
        val commonIndent = body.filter { it.isNotBlank() }
            .minOfOrNull { line -> line.takeWhile { it == ' ' }.length } ?: 0
        return body.joinToString("\n") { line ->
            if (commonIndent > 0 && line.length >= commonIndent) line.substring(commonIndent) else line.trimStart()
        }
    }

    private fun headingLevel(size: Int): ForumBodyBlock.HeadingLevel = when (size) {
        6 -> ForumBodyBlock.HeadingLevel.TITLE
        5 -> ForumBodyBlock.HeadingLevel.SUBTITLE
        4 -> ForumBodyBlock.HeadingLevel.SECTION
        else -> ForumBodyBlock.HeadingLevel.CAPTION
    }

    private fun isHeadingFont(element: Element): Boolean {
        if (element.tagName().lowercase() != "font") return false
        val size = element.attr("size").trim().toIntOrNull() ?: return false
        if (size !in HEADING_SIZES) return false
        // `[size=…]` may wrap a whole block (a list, a table, a rule). Promoting that to a single
        // heading flattens its structure into one text run, so a font with block descendants is
        // left as a generic container: the walker then keeps the inner blocks.
        return element.selectFirst("ul, ol, table, blockquote, pre, div, hr, iframe") == null
    }

    /** `<div align>`, `<p align>` and `<center>`-style alignment as a block-level value. */
    private fun blockAlignOf(element: Element): ForumBodyBlock.BlockAlign? =
        when (element.attr("align").trim().lowercase()) {
            "center" -> ForumBodyBlock.BlockAlign.CENTER
            "right" -> ForumBodyBlock.BlockAlign.END
            "left" -> ForumBodyBlock.BlockAlign.START
            else -> null
        }

    /**
     * Per-cell alignment for a table cell.
     *
     * Discuz writes alignment in three shapes, all of which appear in real posts: `<td align>`,
     * an inner `<div align="center">` (the 网盘 table in `rich_156696.html` centres every header and
     * short cell that way), and occasionally `style="text-align:…"`. The cell's own attribute wins,
     * then the first aligned descendant, then a CSS `text-align` on either — that ordering keeps the
     * most specific declaration while still honouring a styled cell cheaply.
     */
    private fun cellAlignOf(cell: Element): ForumBodyBlock.BlockAlign? =
        blockAlignOf(cell)
            ?: cell.selectFirst("div[align], p[align], [style*=text-align]")
                ?.let { blockAlignOf(it) ?: styleAlignOf(it) }
            ?: styleAlignOf(cell)

    /** Cheap `text-align: center|right|left` reader for a `style` attribute. */
    private fun styleAlignOf(element: Element): ForumBodyBlock.BlockAlign? {
        val style = element.attr("style").lowercase()
        if (style.isEmpty()) return null
        return when {
            "text-align:center" in style || "text-align: center" in style -> ForumBodyBlock.BlockAlign.CENTER
            "text-align:right" in style || "text-align: right" in style -> ForumBodyBlock.BlockAlign.END
            "text-align:left" in style || "text-align: left" in style -> ForumBodyBlock.BlockAlign.START
            else -> null
        }
    }

    private fun textAlignOf(align: ForumBodyBlock.BlockAlign?): TextAlign? = when (align) {
        ForumBodyBlock.BlockAlign.CENTER -> TextAlign.Center
        ForumBodyBlock.BlockAlign.END -> TextAlign.End
        ForumBodyBlock.BlockAlign.START -> TextAlign.Start
        null -> null
    }

    /** Reads one `name: value` declaration out of an inline `style` attribute. */
    private fun cssColor(style: String, property: String): String? {
        if (style.isBlank()) return null
        val match = Regex("""(?:^|;)\s*${Regex.escape(property)}\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(style)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Strips Discuz's server-side quote truncation marker.
     *
     * The template cuts a long quoted body and appends a literal ` ...` (see the quote in
     * `sample_bbcode_thread.html`: 「正在迁移v3接口并继 ...」). Keeping those ASCII dots makes the
     * quote look like the author typed them, so they are removed and reported separately.
     */
    private fun stripTruncationEllipsis(text: AnnotatedString): Pair<AnnotatedString, Boolean> {
        val raw = text.text
        val match = TRUNCATION_SUFFIX.find(raw) ?: return text to false
        val cut = raw.substring(0, match.range.first).trimEnd()
        if (cut.isEmpty()) return text to false
        val rebuilt = buildAnnotatedString {
            append(cut)
            text.spanStyles.forEach { span ->
                val end = span.end.coerceAtMost(cut.length)
                if (span.start < end) addStyle(span.item, span.start, end)
            }
            // Truncation only ever cuts a *suffix*, so surviving offsets are unchanged; the ranges
            // still have to be re-added, or they would be lost with the discarded tail.
            text.paragraphStyles.forEach { span ->
                val end = span.end.coerceAtMost(cut.length)
                if (span.start < end) addStyle(span.item, span.start, end)
            }
            text.getLinkAnnotations(0, text.length).forEach { link ->
                val end = link.end.coerceAtMost(cut.length)
                if (link.start >= end) return@forEach
                when (val annotation = link.item) {
                    is LinkAnnotation.Url -> addLink(annotation, link.start, end)
                    is LinkAnnotation.Clickable -> addLink(annotation, link.start, end)
                }
            }
        }
        return rebuilt to true
    }

    /** `…  ...` at the very end, which is how the template marks a truncated quote. */
    private val TRUNCATION_SUFFIX = Regex("""\s+(\.{3,}|…+)\s*$""")

    /** True when the whole [text] is one bold run — the site's header-row idiom. */
    private fun isFullyBold(text: AnnotatedString): Boolean {
        val spans = text.spanStyles.filter { it.end - it.start > 0 }
        return spans.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end >= text.length } &&
            spans.all { it.item.fontWeight == FontWeight.Bold }
    }

    /**
     * Collapses template whitespace while keeping deliberate line breaks.
     *
     * Only whitespace is rewritten, so span styles, paragraph styles and **link annotations** are
     * remapped through the actual transformation rather than recomputed. Dropping the annotations
     * here would silently undo the tappable-link fix: every real body paragraph goes through this
     * function, and a link whose run survived a collapse would otherwise lose its
     * `LinkAnnotation` (the text stayed blue, taps did nothing).
     *
     * The collapse is not a single leading shift. The template pretty-prints its HTML, so a
     * `<br>` leaves a source-indentation text node behind that jsoup normalises to one space, and
     * that space is swallowed together with the line break — every interior collapse moves all
     * later offsets. Subtracting only the leading trim therefore painted link runs (and
     * bold/colour spans) over the wrong characters, which is the reported
     * 「有些 bbcode 蓝色解析错位置了」. The mapping is recorded while the string is rebuilt
     * (`indexMap[rawIndex] == collapsedOffset`), so each range is clamped to where its original
     * text actually went.
     */
    private fun normalize(value: AnnotatedString): AnnotatedString {
        val raw = value.text
        val collapsed = StringBuilder(raw.length)
        // Boundary map: `indexMap[i]` is the offset in `collapsed` just before raw[i] was written.
        val indexMap = IntArray(raw.length + 1)
        var i = 0
        while (i < raw.length) {
            val char = raw[i]
            if (char != ' ' && char != '\t' && char != '\n') {
                indexMap[i] = collapsed.length
                collapsed.append(char)
                i++
                continue
            }
            var end = i
            var breaks = 0
            while (end < raw.length) {
                val next = raw[end]
                if (next != ' ' && next != '\t' && next != '\n') break
                if (next == '\n') breaks++
                end++
            }
            // A run with a newline keeps at most one blank line and swallows the indentation
            // around it; a run without one becomes exactly one space. Leading/trailing runs are
            // trimmed, which is what the old `replace(…).trim()` chain did.
            val token = if (breaks > 0) "\n".repeat(breaks.coerceAtMost(2)) else " "
            for (k in i until end) indexMap[k] = collapsed.length
            if (collapsed.isNotEmpty() && end < raw.length) collapsed.append(token)
            i = end
        }
        indexMap[raw.length] = collapsed.length
        val result = collapsed.toString()
        if (result == raw) return value

        val length = result.length
        fun remap(index: Int): Int =
            indexMap[index.coerceIn(0, raw.length)].coerceIn(0, length)

        return buildAnnotatedString {
            append(result)
            value.spanStyles.forEach { span ->
                val start = remap(span.start)
                val end = remap(span.end)
                if (start < end) addStyle(span.item, start, end)
            }
            // Paragraph styles are ranges too, so they must follow the same mapping.
            value.paragraphStyles.forEach { span ->
                val start = remap(span.start)
                val end = remap(span.end)
                // `addStyle` is overloaded for both SpanStyle and ParagraphStyle.
                if (start < end) addStyle(span.item, start, end)
            }
            value.getLinkAnnotations(0, raw.length).forEach { link ->
                val start = remap(link.start)
                val end = remap(link.end)
                // addLink is overloaded per concrete annotation kind, and the range item is the
                // common supertype, so each kind is re-added explicitly.
                if (start < end) {
                    when (val annotation = link.item) {
                        is LinkAnnotation.Url -> addLink(annotation, start, end)
                        is LinkAnnotation.Clickable -> addLink(annotation, start, end)
                    }
                }
            }
        }
    }

    // ---- BBCode → HTML --------------------------------------------------------

    /**
     * Converts the BBCode that Discuz accepts (and echoes back in some places) into HTML before
     * the DOM walk.
     *
     * The mobile template renders posts to HTML server-side, so a real body is already HTML and
     * must never be re-interpreted: a code panel legitimately contains literal `[b]`/`[i]`/`[url]`
     * text, and running the BBCode chain over it would corrupt the code. So if the source already
     * carries a recognised HTML tag it is returned untouched; only a raw BBCode draft (this
     * client's own reply, or a `preview` response) goes through the replacements.
     */
    private fun prepare(source: String): String = if (looksLikeHtml(source)) source else bbcodeToHtml(source)

    private fun looksLikeHtml(source: String): Boolean {
        if (!source.contains('<')) return false
        // A `[code]` body is *displayed* text, not markup: a draft whose only HTML-looking token
        // sits inside a code panel is still BBCode. Without this, `[code]<div>x</div>[/code]`
        // took the HTML branch and rendered the code as a real element while leaving `[code]`
        // itself on screen (proven by the code panels in `rich_166223.html`, whose lines carry
        // literal `<font …>` / `[b]` fragments).
        val withoutCode = CODE_BLOCK.replace(source, "")
        if (!withoutCode.contains('<')) return false
        return HTML_TAG.findAll(withoutCode).any { match ->
            match.groupValues[1].lowercase() in HTML_TAGS
        }
    }

    /**
     * Discuz BBCode → the small HTML subset [Walker] understands.
     *
     * Order matters: `[code]` bodies are pulled out into placeholders first so no later rule can
     * touch the literal markup they contain; then paired blocks (quote/hide/url…), then bare
     * markers (list/table/hr), then the wrappers whose tag name alone carries the meaning
     * (size/align/font), and finally [RESIDUAL_TAG] scrubs anything still unresolved.
     *
     * `[hide]` deliberately does **not** reveal its body: entitlement is server-side, so a body
     * that leaked through must still read as hidden instead of exposing gated content.
     */
    private fun bbcodeToHtml(source: String): String {
        if (!source.contains('[')) return source

        // Protect code bodies first: their content is displayed verbatim and must not be parsed
        // as BBCode (a `<` would otherwise be read as an HTML tag, `[b]` as bold, …).
        val codes = mutableListOf<String>()
        var out = CODE_BLOCK.replace(source) { match ->
            codes += match.groupValues[1].trim('\n')
            "\u0000CODE${codes.size - 1}\u0000"
        }

        // Lists are stack-matched so a nested `[list]…[/list]` closes the right tag.
        out = convertLists(out)
        REPLACEMENTS.forEach { (regex, replacement) -> out = regex.replace(out, replacement) }
        out = RESIDUAL_TAG.replace(out, "")

        // Restore escaped code. Escaping `<`/`>`/`&` keeps the raw text visible after the DOM
        // parse (a literal `<app>` inside `[code]` stays `<app>`, not a tag).
        codes.forEachIndexed { index, body ->
            out = out.replace("\u0000CODE$index\u0000", "<pre><code>${escapeHtml(body)}</code></pre>")
        }
        return out
    }

    private val CODE_BLOCK = Regex("""\[code]([\s\S]*?)\[/code]""", RegexOption.IGNORE_CASE)

    private val LIST_TOKEN = Regex("""\[(/?)list(?:=([^]]*))?]""", RegexOption.IGNORE_CASE)

    /** Stack-matches `[list]`/`[list=1]`/`[/list]` so nesting closes the correct tag. */
    private fun convertLists(source: String): String {
        if (!source.contains("[list", ignoreCase = true)) return source
        val out = StringBuilder()
        val stack = ArrayDeque<String>()
        var last = 0
        LIST_TOKEN.findAll(source).forEach { match ->
            out.append(source, last, match.range.first)
            if (match.groupValues[1] == "/") {
                out.append(if (stack.isNotEmpty()) "</${stack.removeLast()}>" else "</ul>")
            } else {
                val marker = match.groupValues[2]
                val (tag, typeAttr) = when {
                    marker == "1" -> "ol" to " type=\"1\""
                    marker.equals("A", true) -> "ol" to " type=\"a\""
                    else -> "ul" to ""
                }
                stack.addLast(tag)
                out.append("<$tag$typeAttr>")
            }
            last = match.range.last + 1
        }
        out.append(source, last, source.length)
        return out.toString()
    }

    /**
     * Paired/structural rules, most specific first. `[quote=author]` keeps the author as a bold
     * line above the quoted body; `[table]`/`[tr]`/`[td]`, `[hr]` and nested `[list]` all become
     * real elements the walker already understands.
     */
    private val REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        // Paired structural blocks (most specific first).
        Regex("""\[quote=([^]]+)]\s*([\s\S]*?)\[/quote]""", RegexOption.IGNORE_CASE) to
            "<blockquote><strong>$1</strong><br>$2</blockquote>",
        Regex("""\[quote]([\s\S]*?)\[/quote]""", RegexOption.IGNORE_CASE) to "<blockquote>$1</blockquote>",
        Regex("""\[hide]([\s\S]*?)\[/hide]""", RegexOption.IGNORE_CASE) to "<blockquote>此内容已隐藏</blockquote>",
        Regex("""\[free]([\s\S]*?)\[/free]""", RegexOption.IGNORE_CASE) to "$1",
        // Media the client cannot play: keep the URL in an <iframe> so the walker's honest media
        // card is produced (a bare "$1" used to leave the URL as plain prose).
        Regex("""\[(?:media|audio|flash)(?:=[^]]*)?]([\s\S]*?)\[/(?:media|audio|flash)]""", RegexOption.IGNORE_CASE) to
            "<iframe src=\"$1\"></iframe>",
        Regex("""\[attachimg](\d+)\[/attachimg]""", RegexOption.IGNORE_CASE) to " [附件] ",
        Regex("""\[attach](\d+)\[/attach]""", RegexOption.IGNORE_CASE) to " [附件] ",
        Regex("""\[email=([^]]+)]([\s\S]*?)\[/email]""", RegexOption.IGNORE_CASE) to "<a href=\"mailto:$1\">$2</a>",
        Regex("""\[email]([\s\S]*?)\[/email]""", RegexOption.IGNORE_CASE) to "<a href=\"mailto:$1\">$1</a>",
        Regex("""\[qq](\d+)\[/qq]""", RegexOption.IGNORE_CASE) to
            "<a href=\"http://wpa.qq.com/msgrd?v=3&uin=$1\">QQ $1</a>",
        Regex("""\[url=([^]]+)]([\s\S]*?)\[/url]""", RegexOption.IGNORE_CASE) to "<a href=\"$1\">$2</a>",
        Regex("""\[url]([\s\S]*?)\[/url]""", RegexOption.IGNORE_CASE) to "<a href=\"$1\">$1</a>",
        Regex("""\[color=([^]]+)]([\s\S]*?)\[/color]""", RegexOption.IGNORE_CASE) to "<font color=\"$1\">$2</font>",
        Regex("""\[img(?:=[^]]*)?]([\s\S]*?)\[/img]""", RegexOption.IGNORE_CASE) to "<img src=\"$1\">",
        Regex("""\[b]([\s\S]*?)\[/b]""", RegexOption.IGNORE_CASE) to "<strong>$1</strong>",
        Regex("""\[i]([\s\S]*?)\[/i]""", RegexOption.IGNORE_CASE) to "<em>$1</em>",
        Regex("""\[u]([\s\S]*?)\[/u]""", RegexOption.IGNORE_CASE) to "<u>$1</u>",
        Regex("""\[s]([\s\S]*?)\[/s]""", RegexOption.IGNORE_CASE) to "<s>$1</s>",

        // Bare structural markers.
        Regex("""\[\*]""") to "<li>",
        Regex("""\[table]""", RegexOption.IGNORE_CASE) to "<table>",
        Regex("""\[/table]""", RegexOption.IGNORE_CASE) to "</table>",
        Regex("""\[tr]""", RegexOption.IGNORE_CASE) to "<tr>",
        Regex("""\[/tr]""", RegexOption.IGNORE_CASE) to "</tr>",
        Regex("""\[td]""", RegexOption.IGNORE_CASE) to "<td>",
        Regex("""\[/td]""", RegexOption.IGNORE_CASE) to "</td>",
        Regex("""\[hr]""", RegexOption.IGNORE_CASE) to "<hr>",

        // Size maps onto a real heading, so it must survive instead of being dropped.
        Regex("""\[size=([1-7])]""", RegexOption.IGNORE_CASE) to "<font size=\"$1\">",
        Regex("""\[/size]""", RegexOption.IGNORE_CASE) to "</font>",

        // `[align=…]` becomes a real block-level wrapper, not a stripped marker.
        Regex("""\[align=(left|center|right)]""", RegexOption.IGNORE_CASE) to "<div align=\"$1\">",
        Regex("""\[/align]""", RegexOption.IGNORE_CASE) to "</div>",

        // Wrappers with no visual equivalent: drop the markers, keep the text.
        Regex("""\[(?:font=[^]]*|backcolor=[^]]*|indent|float(?:=[^]]*)?|p|md)]""", RegexOption.IGNORE_CASE) to "",
        Regex("""\[/(?:font|backcolor|indent|float|p|md)]""", RegexOption.IGNORE_CASE) to "",

        // Id-only markers with nothing renderable behind them.
        Regex("""\[(?:postbg|groupid|rate|player|autolink|music)(?:=[^]]*)?]""", RegexOption.IGNORE_CASE) to "",
        Regex("""\[/(?:postbg|groupid|rate|player|autolink|music)]""", RegexOption.IGNORE_CASE) to "",
    )

    /** Last-resort scrub for unbalanced or unsupported Discuz tags left in the body. */
    private val RESIDUAL_TAG = Regex(
        """\[/?(?:attach|attachimg|hide|free|size|font|backcolor|align|indent|float|p|list|table|tr|td|media|audio|flash|postbg|groupid|rate|player|email|md|quote|b|i|u|s|url|img|color|code|hr|qq)(?:=[^]]*)?]""",
        RegexOption.IGNORE_CASE,
    )

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Strips markup and returns one readable line, used for listing summaries. */
    fun plainText(html: String): String {
        if (html.isBlank()) return ""
        val document = runCatching { Jsoup.parseBodyFragment(prepare(html)) }.getOrNull() ?: return html.trim()
        document.select("script, style, img, iframe").forEach { it.remove() }
        document.select("br").forEach { it.replaceWith(TextNode("\n")) }
        return document.body().text()
            .replace(BLANK_RUN, " ")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()
    }

    /**
     * Resolves an image source, preferring the real file behind Discuz's lazy-load placeholder.
     *
     * The Comiis template never puts the uploaded file straight into `src` for every image kind:
     * lists put it in `comiis_loadimages`, the post editor in `file`/`zoomfile`, AJAX fragments in
     * `data-src`; `src` then points at a transparent 1px placeholder (`none.png`,
     * `imageloading.gif`) or a `file:` stub. Reading `src` first is what made image-host uploads
     * render as a blank block even though the real URL was right there in the tag.
     */
    private fun imageUrl(element: Element): String? {
        val lazy = element.attr("comiis_loadimages")
            .ifBlank { element.attr("file") }
            .ifBlank { element.attr("zoomfile") }
            .ifBlank { element.attr("data-src") }
        val raw = element.attr("src")
        val candidate = when {
            raw.isBlank() -> lazy
            raw.startsWith("file:") -> lazy
            raw.startsWith("data:") -> lazy
            raw.startsWith("javascript:") -> lazy
            PLACEHOLDER_SOURCES.any { raw.contains(it) } -> lazy
            else -> raw
        }
        return ForumUrls.absolute(candidate)
    }

    private fun isSmiley(element: Element): Boolean =
        element.hasAttr("smilieid") ||
            element.attr("src").contains(SMILEY_PATH) ||
            element.attr("file").contains(SMILEY_PATH) ||
            element.attr("comiis_loadimages").contains(SMILEY_PATH)

    private fun isSmileySource(url: String): Boolean = url.contains(SMILEY_PATH)

    /** Transparent stubs the template uses in `src` while the real file sits in a lazy attribute. */
    private val PLACEHOLDER_SOURCES = listOf("none.png", "imageloading.gif", "about:blank")

    /** Discuz's file-type badge path — an icon, never a picture of the attachment. */
    private const val FILETYPE_ICON_PATH = "static/image/filetype"

    /**
     * `下载积分: 金币 -1` on the attachment card's size line. The captured number is the signed
     * deduction; the parsed cost is its absolute value.
     */
    private val ATTACH_COST = Regex("""下载积分\s*[:：]\s*金币\s*([-+]?\d+)""")

    /** `[qq]123456789[/qq]` renders a chat URL carrying the account in `uin=`. */
    private val QQ_UIN = Regex("""[?&]uin=(\d+)""")

    /** `src='…'` inside a template media-injection `<script>` (`[media]`/`[flash]`). */
    private val MEDIA_EMBED_SRC = Regex("""(?:src|file)=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)

    /** A bilibili player URL (`…/player.html?bvid=…`), rewritten to its watch page. */
    private val BILIBILI_BVID = Regex("""bvid=([A-Za-z0-9]+)""")

    /** How a `ul`/`ol` numbers its rows. */
    private enum class ListMarkerStyle { BULLET, NUMERIC, ALPHA }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

    private fun String.hasImageExtension(): Boolean {
        val ext = substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in IMAGE_EXTENSIONS
    }

    /**
     * Parses the `#RRGGBB` / `#RGB` / `rgb(…)` colour syntax Discuz emits.
     *
     * Deliberately not `android.graphics.Color.parseColor`: that is an Android stub under JVM
     * unit tests, where it throws and the style would silently vanish.
     */
    private fun parseHexColor(raw: String): Color? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val hex = value.removePrefix("#")
        return when {
            hex.length == 6 -> hex.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
            hex.length == 3 -> hex.toLongOrNull(16)?.let { short ->
                val r = ((short shr 8) and 0xF) * 0x11
                val g = ((short shr 4) and 0xF) * 0x11
                val b = (short and 0xF) * 0x11
                Color(0xFF000000L or (r shl 16) or (g shl 8) or b)
            }
            else -> null
        }.let { color ->
            // Discuz also writes named colours; map the few that actually appear.
            color ?: when (value.lowercase()) {
                "red" -> Color(0xFFFF0000)
                "green" -> Color(0xFF008000)
                "blue" -> Color(0xFF0000FF)
                "black" -> Color(0xFF000000)
                "white" -> Color(0xFFFFFFFF)
                "gray", "grey" -> Color(0xFF808080)
                else -> null
            }
        }
    }
}

/** True for a paragraph that carries no text (dropped from aligned groups). */
private fun ForumBodyBlock.isBlankParagraph(): Boolean = this is ForumBodyBlock.Paragraph && text.isBlank()
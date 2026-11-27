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

                        tag == "ul" || tag == "ol" -> emitList(node, ordered = tag == "ol", depth = 0)

                        tag == "pre" -> emitPreCode(node)

                        // An inline `<a>` inside a paragraph is the common case (every reference
                        // link in a post). It must be routed through the link-aware inline walk
                        // here too — otherwise only links inside quotes/list items/cells became
                        // tappable, and the paragraph links stayed plain coloured text.
                        tag == "a" -> {
                            val href = ForumUrls.absolute(node.attr("href"))
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

        private fun emitList(list: Element, ordered: Boolean, depth: Int) {
            flush()
            val items = mutableListOf<ForumBodyBlock.ListItem>()
            collectListItems(list, ordered, depth, items)
            if (items.isNotEmpty()) blocks += ForumBodyBlock.BulletList(items)
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
            ordered: Boolean,
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
                if (value.isNotBlank() || nested.isEmpty()) {
                    val marker = if (ordered) "$index." else "•"
                    out.add(ForumBodyBlock.ListItem(marker = marker, text = value, depth = depth))
                }
                nested.forEach { nestedList ->
                    collectListItems(
                        nestedList,
                        ordered = nestedList.tagName().lowercase() == "ol",
                        depth = depth + 1,
                        out = out,
                    )
                }
            }
        }

        /**
         * `table/tr/th|td` with header semantics and merge spans.
         *
         * Discuz's `[table]` has no header row concept, but the site's authors fake one: the first
         * row is written as bold-only cells (`<td><div align="center"><strong>标题</strong></div></td>`
         * in `rich_156696.html`). That row is marked as a header so it is rendered like one, and a
         * real `<th>`/`<thead>` (other template variants) is honoured as well. `colspan`/`rowspan`
         * are carried as a weight so a merged cell keeps its width and the remaining cells do not
         * shift; no cell text is ever dropped.
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
                            align = blockAlignOf(cell) ?: blockAlignOf(cell.selectFirst("div[align]") ?: cell),
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
            val caption = attach.selectFirst("p.attach_size")?.text()?.trim()?.takeIf { it.isNotBlank() }

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
                            val href = ForumUrls.absolute(node.attr("href"))
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
            "font" -> parseHexColor(element.attr("color"))?.let { SpanStyle(color = it) }
            else -> null
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
        return size in HEADING_SIZES
    }

    /** `<div align>`, `<p align>` and `<center>`-style alignment as a block-level value. */
    private fun blockAlignOf(element: Element): ForumBodyBlock.BlockAlign? =
        when (element.attr("align").trim().lowercase()) {
            "center" -> ForumBodyBlock.BlockAlign.CENTER
            "right" -> ForumBodyBlock.BlockAlign.END
            "left" -> ForumBodyBlock.BlockAlign.START
            else -> null
        }

    private fun textAlignOf(align: ForumBodyBlock.BlockAlign?): TextAlign? = when (align) {
        ForumBodyBlock.BlockAlign.CENTER -> TextAlign.Center
        ForumBodyBlock.BlockAlign.END -> TextAlign.End
        ForumBodyBlock.BlockAlign.START -> TextAlign.Start
        null -> null
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
     * Only whitespace is rewritten, so span styles and **link annotations** are remapped rather
     * than recomputed. Dropping the annotations here would silently undo the tappable-link fix:
     * every real body paragraph goes through this function, and a link whose run survived a
     * collapse would otherwise lose its `LinkAnnotation` (the text stayed blue, taps did nothing).
     */
    private fun normalize(value: AnnotatedString): AnnotatedString {
        val raw = value.text
        val collapsed = raw
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (collapsed == raw) return value

        // `leadingTrim` is how much was cut from the front; every range shifts by it, and both
        // ends are clamped to the collapsed text.
        val leadingTrim = raw.length - raw.trimStart().length
        return buildAnnotatedString {
            append(collapsed)
            value.spanStyles.forEach { span ->
                val start = (span.start - leadingTrim).coerceAtLeast(0)
                val end = (span.end - leadingTrim).coerceAtMost(collapsed.length)
                if (start < end) addStyle(span.item, start, end)
            }
            value.getLinkAnnotations(0, raw.length).forEach { link ->
                val start = (link.start - leadingTrim).coerceAtLeast(0)
                val end = (link.end - leadingTrim).coerceAtMost(collapsed.length)
                // ddLink is overloaded per concrete annotation kind, and the range item is the
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
                val tag = if (marker == "1" || marker.equals("a", true) || marker.equals("A", true)) "ol" else "ul"
                stack.addLast(tag)
                out.append("<$tag>")
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
        Regex("""\[(?:media|audio|flash)(?:=[^]]*)?]([\s\S]*?)\[/(?:media|audio|flash)]""", RegexOption.IGNORE_CASE) to "$1",
        Regex("""\[attachimg](\d+)\[/attachimg]""", RegexOption.IGNORE_CASE) to " [附件] ",
        Regex("""\[attach](\d+)\[/attach]""", RegexOption.IGNORE_CASE) to " [附件] ",
        Regex("""\[email=([^]]+)]([\s\S]*?)\[/email]""", RegexOption.IGNORE_CASE) to "<a href=\"mailto:$1\">$2</a>",
        Regex("""\[email]([\s\S]*?)\[/email]""", RegexOption.IGNORE_CASE) to "<a href=\"mailto:$1\">$1</a>",
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
        """\[/?(?:attach|attachimg|hide|free|size|font|backcolor|align|indent|float|p|list|table|tr|td|media|audio|flash|postbg|groupid|rate|player|email|md|quote|b|i|u|s|url|img|color|code|hr)(?:=[^]]*)?]""",
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
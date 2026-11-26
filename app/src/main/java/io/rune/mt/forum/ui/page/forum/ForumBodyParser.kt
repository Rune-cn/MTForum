// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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

    fun parse(html: String, linkColor: Color = Color(0xFF3482FF)): List<ForumBodyBlock> {
        if (html.isBlank()) return emptyList()
        val document = runCatching { Jsoup.parseBodyFragment(prepare(html)) }
            .onFailure { e -> Timber.tag(TAG).w(e, "post html unparsable") }
            .getOrNull()
        if (document == null) return listOf(ForumBodyBlock.Paragraph(AnnotatedString(html.trim())))

        val walker = Walker(linkColor)
        document.body().childNodes().forEach(walker::walkRoot)
        return walker.finish()
    }

    /**
     * Mutable walk state.
     *
     * A class (not a local closure) because the recursive visitor needs to reach the paragraph
     * and emoji buffers that the flush points also touch; Kotlin local functions cannot forward
     * reference each other.
     */
    private class Walker(private val linkColor: Color) {

        private val blocks = mutableListOf<ForumBodyBlock>()
        private val emoji = mutableListOf<String>()
        private var text = AnnotatedString.Builder()
        private var paragraphAlign: TextAlign? = null

        fun walkRoot(node: Node) = walkBlock(node, style = null)

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

                        isHeadingFont(node) -> emitHeading(node)

                        (tag == "div" || tag == "p") && textAlignOf(node) != null -> {
                            flush()
                            paragraphAlign = textAlignOf(node)
                            node.childNodes().forEach { walkBlock(it, style) }
                            flush()
                            paragraphAlign = null
                            text.append('\n')
                        }

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

        private fun emitQuote(quote: Element) {
            flush()
            val authorEl = quote.select("font").firstOrNull { it.text().contains("发表于") }
            val author = authorEl?.text()?.trim()?.takeIf { it.isNotBlank() }
            val builder = AnnotatedString.Builder()
            quote.childNodes().forEach { child ->
                walkInline(child, builder, null) { el ->
                    el === authorEl || el.text().trim() == "回复"
                }
            }
            val value = normalize(builder.toAnnotatedString())
            blocks += ForumBodyBlock.Quote(value, author)
        }

        private fun emitList(list: Element, ordered: Boolean, depth: Int) {
            flush()
            val items = mutableListOf<ForumBodyBlock.ListItem>()
            collectListItems(list, ordered, depth, items)
            if (items.isNotEmpty()) blocks += ForumBodyBlock.BulletList(items)
        }

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
                val marker = if (ordered) "$index." else "•"
                out.add(ForumBodyBlock.ListItem(marker = marker, text = value, depth = depth))
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

        private fun emitTable(table: Element) {
            flush()
            val rows = table.select("tr").mapNotNull { tr ->
                val cells = tr.children()
                    .filter { it.tagName().lowercase() == "td" || it.tagName().lowercase() == "th" }
                    .map { cell ->
                        val builder = AnnotatedString.Builder()
                        cell.childNodes().forEach { walkInline(it, builder, null) }
                        normalize(builder.toAnnotatedString())
                    }
                cells.takeIf { it.isNotEmpty() }
            }
            if (rows.isNotEmpty()) blocks += ForumBodyBlock.Table(rows)
        }

        private fun emitAttachment(attach: Element) {
            flush()
            val link = attach.selectFirst("a")
            val url = ForumUrls.absolute(link?.attr("href"))
            val name = attach.selectFirst("span.f_ok")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: link?.selectFirst("p.attach_tit")?.text()?.trim()?.substringBefore("上传")?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: "附件"
            val caption = attach.selectFirst("p.attach_size")?.text()?.trim()?.takeIf { it.isNotBlank() }
            if (url != null) {
                blocks += ForumBodyBlock.Attachment(name = name, caption = caption, url = url)
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

    private fun textAlignOf(element: Element): TextAlign? =
        when (element.attr("align").trim().lowercase()) {
            "center" -> TextAlign.Center
            "right" -> TextAlign.End
            "left" -> TextAlign.Start
            else -> null
        }

    /** Collapses template whitespace while keeping deliberate line breaks. */
    private fun normalize(value: AnnotatedString): AnnotatedString {
        val raw = value.text
        val collapsed = raw
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (collapsed == raw) return value

        // Only whitespace changed, so spans are clamped rather than recomputed. `leadingTrim`
        // is how much was cut from the front; every span shifts by it.
        val leadingTrim = raw.length - raw.trimStart().length
        return buildAnnotatedString {
            append(collapsed)
            value.spanStyles.forEach { span ->
                val start = (span.start - leadingTrim).coerceAtLeast(0)
                val end = (span.end - leadingTrim).coerceAtMost(collapsed.length)
                if (start < end) addStyle(span.item, start, end)
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
        return HTML_TAG.findAll(source).any { match ->
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

        // Wrappers with no visual equivalent: drop the markers, keep the text.
        Regex("""\[(?:font=[^]]*|backcolor=[^]]*|align=[^]]*|indent|float(?:=[^]]*)?|p|md)]""", RegexOption.IGNORE_CASE) to "",
        Regex("""\[/(?:font|backcolor|align|indent|float|p|md)]""", RegexOption.IGNORE_CASE) to "",

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

    /** Transparent stubs the template uses in `src` while the real file sits in a lazy attribute. */
    private val PLACEHOLDER_SOURCES = listOf("none.png", "imageloading.gif", "about:blank")

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

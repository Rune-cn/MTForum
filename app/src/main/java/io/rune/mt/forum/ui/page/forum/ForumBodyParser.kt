// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
 * The site emits **HTML, not BBCode** — checked against several real threads, whose bodies carry
 * `<strong>`/`<i>`/`<u>`/`<font color>`/`<a>` inside `<div>` paragraphs plus `<img>` for smilies
 * and attachments. Inline styling is preserved as Compose [SpanStyle]s, so a post needs no
 * WebView and no third-party rich-text engine.
 *
 * [parse] is a pure function of (html, linkColor); callers memoise it per post.
 */
internal object ForumBodyParser {

    private const val TAG = "ForumBodyParser"
    private const val SMILEY_PATH = "static/image/smiley"

    /** Tags whose boundaries start a new paragraph. */
    private val BLOCK_TAGS = setOf(
        "div", "p", "li", "ul", "ol", "tr", "table", "section", "h1", "h2", "h3", "h4",
        "pre", "blockquote",
    )

    /** Tags whose contents are never shown as text. */
    private val SKIP_TAGS = setOf("script", "style", "iframe", "audio", "video", "noscript")

    private val BLANK_RUN = Regex("[\\t\\x0B\\f\\r ]+")

    fun parse(html: String, linkColor: Color = Color(0xFF3482FF)): List<ForumBodyBlock> {
        if (html.isBlank()) return emptyList()
        val document = runCatching { Jsoup.parseBodyFragment(bbcodeToHtml(html)) }
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

        fun walkRoot(node: Node) = walk(node, style = null, intoQuote = false)

        private fun flush() {
            if (emoji.isNotEmpty()) {
                blocks += ForumBodyBlock.EmojiRow(emoji.toList())
                emoji.clear()
            }
            val value = normalize(text.toAnnotatedString())
            if (value.isNotBlank()) blocks += ForumBodyBlock.Paragraph(value)
            text = AnnotatedString.Builder()
        }

        fun finish(): List<ForumBodyBlock> {
            flush()
            return blocks
        }

        private fun walk(node: Node, style: SpanStyle?, intoQuote: Boolean) {
            when (node) {
                is TextNode -> appendWithSmilies(text, node.text().replace(BLANK_RUN, " "), style)

                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        tag in SKIP_TAGS -> Unit

                        tag == "br" -> text.append('\n')

                        tag == "img" -> {
                            val url = imageUrl(node)
                            if (url != null) {
                                when {
                                    isSmiley(node) -> emoji += url
                                    intoQuote -> Unit
                                    else -> {
                                        flush()
                                        blocks += ForumBodyBlock.Picture(url)
                                    }
                                }
                            }
                        }

                        tag == "blockquote" -> {
                            flush()
                            var quoted = AnnotatedString.Builder()
                            node.childNodes().forEach { walkInto(it, quoted, style) }
                            val value = normalize(quoted.toAnnotatedString())
                            if (value.isNotBlank()) blocks += ForumBodyBlock.Quote(value)
                            quoted = AnnotatedString.Builder()
                        }

                        tag in BLOCK_TAGS -> {
                            flush()
                            node.childNodes().forEach { walk(it, style, intoQuote) }
                            text.append('\n')
                        }

                        else -> {
                            val next = inlineStyle(tag, node) ?: style
                            node.childNodes().forEach { walk(it, next, intoQuote) }
                        }
                    }
                }

                else -> Unit
            }
        }

        /** Quote bodies get their own builder so they never leak into the surrounding paragraph. */
        private fun walkInto(node: Node, out: AnnotatedString.Builder, style: SpanStyle?) {
            when (node) {
                is TextNode -> append(out, node.text().replace(BLANK_RUN, " "), style)

                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        tag in SKIP_TAGS -> Unit
                        tag == "br" -> out.append('\n')
                        tag == "img" -> {
                            // Quotes render text only; a picture inside one is noise.
                            val alt = node.attr("alt")
                            if (alt.isNotBlank()) out.append(alt)
                        }
                        tag in BLOCK_TAGS -> {
                            out.append('\n')
                            node.childNodes().forEach { walkInto(it, out, style) }
                            out.append('\n')
                        }
                        else -> {
                            val next = inlineStyle(tag, node) ?: style
                            node.childNodes().forEach { walkInto(it, out, next) }
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


    /**
     * Converts the BBCode that Discuz accepts (and echoes back in some places) into HTML before
     * the DOM walk.
     *
     * The mobile template renders posts to HTML server-side, so this is normally a no-op. It
     * exists for two real cases: replies this client sends (which carry `[quote]`/`[b]` and a
     * smiley token), and the site's own `preview` responses, which return the raw markup.
     */
    private fun bbcodeToHtml(source: String): String {
        if (!source.contains('[')) return source
        var out = source
        // Structural tags first, so nested inline tags land inside the right element.
        REPLACEMENTS.forEach { (regex, replacement) -> out = regex.replace(out, replacement) }
        return out
    }

    private val REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        Regex("""\[quote]([\s\S]*?)\[/quote]""", RegexOption.IGNORE_CASE) to "<blockquote>$1</blockquote>",
        Regex("""\[b]([\s\S]*?)\[/b]""", RegexOption.IGNORE_CASE) to "<strong>$1</strong>",
        Regex("""\[i]([\s\S]*?)\[/i]""", RegexOption.IGNORE_CASE) to "<em>$1</em>",
        Regex("""\[u]([\s\S]*?)\[/u]""", RegexOption.IGNORE_CASE) to "<u>$1</u>",
        Regex("""\[s]([\s\S]*?)\[/s]""", RegexOption.IGNORE_CASE) to "<s>$1</s>",
        Regex("""\[url=([^]]+)]([\s\S]*?)\[/url]""", RegexOption.IGNORE_CASE) to "<a href=\"$1\">$2</a>",
        Regex("""\[url]([\s\S]*?)\[/url]""", RegexOption.IGNORE_CASE) to "<a href=\"$1\">$1</a>",
        Regex("""\[code]([\s\S]*?)\[/code]""", RegexOption.IGNORE_CASE) to "<pre><code>$1</code></pre>",
        Regex("""\[color=([^]]+)]([\s\S]*?)\[/color]""", RegexOption.IGNORE_CASE) to "<font color=\"$1\">$2</font>",
        Regex("""\[img]([\s\S]*?)\[/img]""", RegexOption.IGNORE_CASE) to "<img src=\"$1\">",
        Regex("""\[hr]""", RegexOption.IGNORE_CASE) to "<hr>",
    )

    /** Strips markup and returns one readable line, used for listing summaries. */
    fun plainText(html: String): String {
        if (html.isBlank()) return ""
        val document = runCatching { Jsoup.parseBodyFragment(bbcodeToHtml(html)) }.getOrNull() ?: return html.trim()
        document.select("script, style, img, iframe").forEach { it.remove() }
        document.select("br").forEach { it.replaceWith(TextNode("\n")) }
        return document.body().text()
            .replace(BLANK_RUN, " ")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()
    }

    /**
     * Resolves an image source, preferring the real file behind Discuz's lazy-load placeholder.
     * The mobile template ships `src="file://..."` plus a `file`/`zoomfile` attribute.
     */
    private fun imageUrl(element: Element): String? {
        val lazy = element.attr("file").ifBlank { element.attr("zoomfile") }.ifBlank { element.attr("data-src") }
        val raw = element.attr("src")
        val candidate = when {
            raw.isBlank() -> lazy
            raw.startsWith("file:") -> lazy
            else -> raw
        }
        return ForumUrls.absolute(candidate)
    }

    private fun isSmiley(element: Element): Boolean =
        element.hasAttr("smilieid") ||
            element.attr("src").contains(SMILEY_PATH) ||
            element.attr("file").contains(SMILEY_PATH)

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

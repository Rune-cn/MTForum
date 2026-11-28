// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.rune.mt.forum.R
import io.rune.mt.forum.data.forum.network.ForumUrls
import timber.log.Timber
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One flattened piece of a Discuz post body.
 *
 * The site stores posts as **BBCode** and the Comiis mobile template renders them to **HTML**
 * server-side, so the client receives HTML. Rendering that with a WebView would pull in a whole
 * browser engine, so the body is flattened into these blocks and laid out natively; inline
 * styling rides along in an [AnnotatedString], which is Compose's own answer to rich text.
 *
 * Each richer Discuz construct gets a dedicated block: code panels keep their line structure,
 * heading sizes keep their hierarchy, quotes carry an author header plus any nested structure,
 * and lists/tables/attachments are real widgets rather than `<li>`-per-paragraph prose.
 */
internal sealed interface ForumBodyBlock {
    /** Block-level alignment carried by a `[align]` wrapper / `align` attribute. */
    enum class BlockAlign { START, CENTER, END }

    /** A paragraph of styled text; blank entries are dropped by the parser. */
    data class Paragraph(
        val text: AnnotatedString,
        val textAlign: TextAlign? = null,
    ) : ForumBodyBlock

    /** A standalone picture (attachment or large image), rendered at full width. */
    data class Picture(val url: String) : ForumBodyBlock

    /** A run of consecutive inline smilies, rendered as small images on one row. */
    data class EmojiRow(val urls: List<String>) : ForumBodyBlock

    /**
     * A media embed the native renderer cannot play (`[media]`/`[audio]`/`[flash]`).
     *
     * The site turns these into an `<iframe>` / `<video>` / a script-injected player, none of which
     * can run here. Dropping them left a silent gap, so the URL is shown in an honest card that
     * opens it externally — the content is not playable inline, but it is never lost.
     */
    data class Media(val url: String, val label: String? = null) : ForumBodyBlock

    /**
     * A quoted reply (`<blockquote>` / `div.comiis_quote`), inset behind a leading rule.
     *
     * [children] holds any nested rich blocks the quote body contained — a list, a table, a code
     * panel, a rule, or a picture — so quoting a formatted post keeps that structure (and its
     * order) instead of flattening it to one text run. Pictures stay [Picture] children rather
     * than a separate list: a side channel would lose their position in the quoted body and
     * render them twice. [truncated] marks a quote the server cut (the template appends a literal
     * ` ...`), so the renderer draws a real ellipsis.
     */
    data class Quote(
        val text: AnnotatedString,
        val author: String? = null,
        val truncated: Boolean = false,
        val children: List<ForumBodyBlock> = emptyList(),
    ) : ForumBodyBlock

    /** Visual weight of a heading parsed from `<font size>`. */
    enum class HeadingLevel { TITLE, SUBTITLE, SECTION, CAPTION }

    /** A heading row (`<font size="6|5|4|2">`, often wrapping `<strong>`). */
    data class Heading(val text: AnnotatedString, val level: HeadingLevel) : ForumBodyBlock

    /** A code panel (`div.comiis_blockcode`) with its exact, line-preserved text. */
    data class CodeBlock(val text: String) : ForumBodyBlock

    /** A horizontal rule (`<hr>`). */
    data object Divider : ForumBodyBlock

    /** One row of a bullet / numbered list, indented by [depth]. */
    data class ListItem(val marker: String, val text: AnnotatedString, val depth: Int)

    /** A `ul`/`ol` list rendered as marker + text rows. */
    data class BulletList(val items: List<ListItem>) : ForumBodyBlock

    /** One table cell: its text, whether it is a header, its alignment and its column span. */
    data class TableCell(
        val text: AnnotatedString,
        val header: Boolean = false,
        val align: BlockAlign? = null,
        /** `colspan`, clamped; drives the cell's weight so merged columns stay readable. */
        val span: Int = 1,
    )

    /**
     * A grid parsed from `table/tr/th|td`, with header cells and merge spans preserved.
     *
     * Columns are **not** equal slices: [ForumHtmlText]'s `tableColumnWeights` measures each
     * column's text and gives short columns a minimum width while the prose column takes the
     * rest (see the heuristic there). Keeping the grid as plain rows keeps the model independent
     * of any pixel decision; the sizing lives in the renderer and is unit-tested through the pure
     * weight function.
     */
    data class Table(val rows: List<List<TableCell>>) : ForumBodyBlock

    /**
     * A block group wrapped in `[align=…]`.
     *
     * `[align]` wraps whole blocks, not just text, so the alignment lives on the group: a centred
     * list, table or picture is centred as a unit. Flattening it to a per-paragraph `textAlign`
     * lost the container and left nested blocks left-aligned.
     */
    data class Aligned(val align: BlockAlign, val blocks: List<ForumBodyBlock>) : ForumBodyBlock

    /**
     * An attachment card (`div.comiis_attach`), tappable to choose download or open.
     *
     * [iconUrl] is the site's file-type badge (`static/image/filetype/`), [previewUrl] a real
     * thumbnail when the attachment is an image, and [isImage] tells the UI which affordance to
     * show (an image attachment is worth previewing as well as downloading).
     *
     * [costCoins] is the price the card advertises in its `p.attach_size` line
     * (`下载积分: 金币 -1` → 1). It is only a hint so the chooser can warn *before* the request;
     * the authoritative charge happens server-side through the attachcredit handshake.
     */
    data class Attachment(
        val name: String,
        val caption: String?,
        val url: String,
        val iconUrl: String? = null,
        val previewUrl: String? = null,
        val isImage: Boolean = false,
        val costCoins: Int = 0,
    ) : ForumBodyBlock

    /** The template's moderator-action note (`div.comiis_modact`). */
    data class ModeratorNote(val text: AnnotatedString) : ForumBodyBlock

    /** The template's edit remark (`i.pstatus`). */
    data class EditNote(val text: AnnotatedString) : ForumBodyBlock
}

/**
 * Renders the raw HTML body of a forum post natively.
 *
 * Bold / italic / underline / strike / font colour survive, inline smilies stay in the text flow
 * as small images, inline `<a href>` is tappable, quotes are inset and keep their nested blocks,
 * code blocks keep their monospace layout, and attachments offer a download-or-open choice.
 * Every URL is normalised against the site so protocol-relative CDN sources still load.
 *
 * @param onAttachmentClick invoked when an attachment card is tapped; the host decides how to
 *   present the download/browser choice. When null the card falls back to opening the URL, so a
 *   bare call from a preview still does something useful.
 */
@Composable
internal fun ForumHtmlText(
    html: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    onImageClick: (String) -> Unit = {},
    onLinkClick: ((String) -> Unit)? = null,
    onAttachmentClick: ((ForumBodyBlock.Attachment) -> Unit)? = null,
) {
    val linkColor = MiuixTheme.colorScheme.primary
    // A LinkAnnotation stores its listener at parse time, so the handler is folded into the parsed
    // body. `rememberUpdatedState` keeps the latest callback reachable while `hasLinkHandler`
    // (a Boolean) is the only remember key, so the body is re-parsed only when the presence of a
    // handler flips — not on every recomposition, and not when a caller passes a new lambda.
    val latestLinkClick by rememberUpdatedState(onLinkClick)
    val hasLinkHandler = onLinkClick != null
    val linkHandler = remember(hasLinkHandler) {
        if (!hasLinkHandler) {
            null
        } else {
            // Explicit Unit body: a lambda whose last expression is an optional call would infer
            // `Unit?`, which does not match the `(String) -> Unit` slot.
            val handler: (String) -> Unit = { url -> latestLinkClick?.invoke(url); Unit }
            handler
        }
    }
    val blocks = remember(html, linkColor, linkHandler) {
        ForumBodyParser.parse(html, linkColor, linkHandler)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> BodyBlock(block, contentColor, onImageClick, onAttachmentClick) }
    }
}

/** Renders one block; shared by the top level and by nested containers (quote, `[align]`). */
@Composable
private fun BodyBlock(
    block: ForumBodyBlock,
    contentColor: Color,
    onImageClick: (String) -> Unit,
    onAttachmentClick: ((ForumBodyBlock.Attachment) -> Unit)?,
) {
    when (block) {
        is ForumBodyBlock.Paragraph -> {
            if (block.text.isNotBlank()) {
                Text(
                    text = block.text,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    color = contentColor,
                    textAlign = block.textAlign ?: TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is ForumBodyBlock.Heading -> HeadingText(block, contentColor)

        is ForumBodyBlock.Quote -> QuoteBlock(block, contentColor, onImageClick, onAttachmentClick)

        is ForumBodyBlock.CodeBlock -> CodeBlockSurface(block, contentColor)

        is ForumBodyBlock.Divider -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .height(1.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)),
        )

        is ForumBodyBlock.BulletList -> BulletListRows(block, contentColor)

        is ForumBodyBlock.Table -> TableGrid(block, contentColor)

        is ForumBodyBlock.Aligned -> AlignedGroup(block, contentColor, onImageClick, onAttachmentClick)

        is ForumBodyBlock.Attachment -> AttachmentCard(block, contentColor, onAttachmentClick)

        is ForumBodyBlock.ModeratorNote -> InfoChip(
            text = block.text,
            background = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
            foreground = MiuixTheme.colorScheme.primary,
        )

        is ForumBodyBlock.EditNote -> Text(
            text = block.text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth(),
        )

        is ForumBodyBlock.EmojiRow -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                block.urls.forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        is ForumBodyBlock.Picture -> PictureBlock(block, onImageClick)

        is ForumBodyBlock.Media -> MediaCard(block, contentColor)
    }
}

@Composable
private fun HeadingText(block: ForumBodyBlock.Heading, contentColor: Color) {
    val (size, line) = when (block.level) {
        ForumBodyBlock.HeadingLevel.TITLE -> 20.sp to 27.sp
        ForumBodyBlock.HeadingLevel.SUBTITLE -> 17.sp to 24.sp
        ForumBodyBlock.HeadingLevel.SECTION -> 15.5.sp to 22.sp
        ForumBodyBlock.HeadingLevel.CAPTION -> 12.5.sp to 18.sp
    }
    Text(
        text = block.text,
        fontSize = size,
        lineHeight = line,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
        // Tighter top spacing than a paragraph gap; the larger semibold face does the rest.
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    )
}

/**
 * An inset quote.
 *
 * The quoted body keeps its own blocks (list/table/code/rule), so quoting a formatted post does
 * not collapse it into one paragraph. Pictures the site renders inside the quote are shown as
 * small thumbnails: dropping them lost visible content, while rendering them full width inside an
 * inset would dominate the reply. A quote the server truncated ends with a real ellipsis row
 * instead of the raw ` ...` the template ships.
 */
@Composable
private fun QuoteBlock(
    block: ForumBodyBlock.Quote,
    contentColor: Color,
    onImageClick: (String) -> Unit,
    onAttachmentClick: ((ForumBodyBlock.Attachment) -> Unit)?,
) {
    val quoteColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(quoteColor.copy(alpha = 0.35f)),
        )
        Column(
            modifier = Modifier.padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val author = block.author
            if (author != null) {
                Text(
                    text = author,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = quoteColor.copy(alpha = 0.85f),
                )
            }
            if (block.text.isNotBlank()) {
                Text(
                    text = block.text,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = quoteColor,
                )
            }
            block.children.forEach { child ->
                QuoteChildBlock(child, quoteColor, onImageClick, onAttachmentClick)
            }
            if (block.truncated) {
                Text(
                    text = stringResource(R.string.forum_body_quote_truncated),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = quoteColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Nested blocks inside a quote, tinted down so they stay visually subordinate to the reply. */
@Composable
private fun QuoteChildBlock(
    block: ForumBodyBlock,
    color: Color,
    onImageClick: (String) -> Unit,
    onAttachmentClick: ((ForumBodyBlock.Attachment) -> Unit)?,
) {
    when (block) {
        is ForumBodyBlock.Paragraph -> if (block.text.isNotBlank()) {
            Text(
                text = block.text,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = color,
                textAlign = block.textAlign ?: TextAlign.Start,
            )
        }

        is ForumBodyBlock.BulletList -> BulletListRows(block, color, compact = true)

        is ForumBodyBlock.Table -> TableGrid(block, color)

        is ForumBodyBlock.Attachment -> AttachmentCard(block, color, onAttachmentClick)

        is ForumBodyBlock.Divider -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color.copy(alpha = 0.25f)),
        )

        is ForumBodyBlock.CodeBlock -> CodeBlockSurface(block, color)

        // A picture inside a quote stays a compact thumbnail: the site renders it, so dropping it
        // lost content, but a full-width image inside an inset would dominate the reply's own text.
        is ForumBodyBlock.Picture -> AsyncImage(
            model = block.url,
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.08f))
                .clickable { onImageClick(block.url) },
            contentScale = ContentScale.Crop,
        )

        // Headings/emoji/notes inside a quote degrade to their text so nothing disappears.
        else -> {
            val flattened = block.flattenedText()
            if (flattened.isNotBlank()) {
                Text(text = flattened, fontSize = 14.sp, lineHeight = 22.sp, color = color)
            }
        }
    }
}

/**
 * `[align=…]` as a block-level container.
 *
 * A single paragraph reuses the plain `Text` path with its alignment; anything richer (a list,
 * table, picture, code panel) is laid out inside a full-width `Column` whose children are aligned
 * by the container, which is what "align the block it wraps" means. Nested text also receives the
 * same `TextAlign` so a multi-line centred paragraph wraps around its own centre.
 */
@Composable
private fun AlignedGroup(
    block: ForumBodyBlock.Aligned,
    contentColor: Color,
    onImageClick: (String) -> Unit,
    onAttachmentClick: ((ForumBodyBlock.Attachment) -> Unit)?,
) {
    val horizontal = when (block.align) {
        ForumBodyBlock.BlockAlign.START -> Alignment.Start
        ForumBodyBlock.BlockAlign.CENTER -> Alignment.CenterHorizontally
        ForumBodyBlock.BlockAlign.END -> Alignment.End
    }
    val textAlign = when (block.align) {
        ForumBodyBlock.BlockAlign.START -> TextAlign.Start
        ForumBodyBlock.BlockAlign.CENTER -> TextAlign.Center
        ForumBodyBlock.BlockAlign.END -> TextAlign.End
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        block.blocks.forEach { child ->
            when (child) {
                // A paragraph inside the wrapper is the common case (that is what `[align]`
                // usually contains), and it needs the container's alignment both as a layout
                // position and as a line-break anchor — the same value the old per-paragraph
                // mapping used, now applied to every block in the group.
                is ForumBodyBlock.Paragraph -> if (child.text.isNotBlank()) {
                    Text(
                        text = child.text,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        color = contentColor,
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> BodyBlock(child, contentColor, onImageClick, onAttachmentClick)
            }
        }
    }
}

@Composable
private fun CodeBlockSurface(block: ForumBodyBlock.CodeBlock, contentColor: Color) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.text) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.forum_body_code_label),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = stringResource(
                    if (copied) R.string.forum_body_copied else R.string.forum_body_copy,
                ),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(block.text))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = block.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun BulletListRows(
    block: ForumBodyBlock.BulletList,
    contentColor: Color,
    compact: Boolean = false,
) {
    val fontSize = if (compact) 14.sp else 15.sp
    val lineHeight = if (compact) 21.sp else 22.sp
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEach { item ->
            // `depth` has no upper bound in the model: the site nests as deep as an author wants,
            // so indentation is linear in depth and re-capped only to keep text readable.
            val indent = (item.depth.coerceAtMost(6) * 14).dp
            Row(modifier = Modifier.fillMaxWidth().padding(start = indent)) {
                Text(
                    text = item.marker,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = contentColor,
                    modifier = Modifier.width(22.dp),
                )
                Text(
                    text = item.text,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Table grid whose **column widths follow content, not column count**.
 *
 * The old renderer gave every cell `.weight(1f)` (or its `colspan`), so a five-column table with a
 * long prose column squeezed all five into `width/5`: the 备注 column wrapped to a character per
 * line, the URL column broke mid-URL, and the table was unreadable (feedback:
 * 「表格方面显示不行」, thread 156696). Column widths now come from [tableColumnWeights], which
 * measures each column's cells and gives short columns a minimum width while the long column takes
 * the rest.
 *
 * A genuinely wide table is laid out at its computed (summed) width inside a `horizontalScroll`
 * so a cell can never be crushed to one character per line; the scroll lives inside the card and
 * the card keeps its rounded border. When the computed width fits, the row is stretched to the card
 * and the weights share the leftover proportionally — so 2- and 3-column tables fill the card as
 * before.
 *
 * Row heights are driven by the tallest cell (a prose cell). Cells are **top-aligned** so a short
 * neighbour's text sits on the first line of the prose rather than being pushed down; the leftover
 * space stays below the short cell, which reads better than centring a one-line label in a
 * ten-line row.
 *
 * A cell's `colspan` contributes the sum of the widths of the columns it covers, so a merged cell
 * keeps its width. Per-cell alignment (from `<td align>`, an inner `<div align>`, or
 * `style="text-align:…"`) is honoured; the header row is bold on a tinted surface.
 */
@Composable
private fun TableGrid(
    block: ForumBodyBlock.Table,
    contentColor: Color,
) {
    val border = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
    val headerBackground = MiuixTheme.colorScheme.surfaceContainerHigh
    val weights = remember(block.rows) { tableColumnWeights(block.rows) }
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(0.5.dp, border, RoundedCornerShape(6.dp)),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // `maxWidth` is the space the card offers today. The table is never narrower than its
            // content-preferring weights; beyond that it scrolls horizontally inside the card.
            val available = maxWidth
            val tableWidth = if (weights.isEmpty()) available else maxOf(available, weights.sum().dp)
            Column(modifier = Modifier.horizontalScroll(scrollState)) {
                block.rows.forEach { row ->
                    val rowAllHeader = row.isNotEmpty() && row.all { it.header }
                    Row(
                        modifier = Modifier
                            .width(tableWidth)
                            .then(if (rowAllHeader) Modifier.background(headerBackground) else Modifier),
                        verticalAlignment = Alignment.Top,
                    ) {
                        var column = 0
                        row.forEach { cell ->
                            val span = cell.span.coerceAtLeast(1)
                            // A merged cell owns the summed weight of the columns it covers.
                            val cellWeight = (0 until span)
                                .sumOf { offset -> (weights.getOrNull(column + offset) ?: 0f).toDouble() }
                                .toFloat()
                                .coerceAtLeast(1f)
                            column += span
                            val align = when (cell.align) {
                                ForumBodyBlock.BlockAlign.CENTER -> TextAlign.Center
                                ForumBodyBlock.BlockAlign.END -> TextAlign.End
                                ForumBodyBlock.BlockAlign.START -> TextAlign.Start
                                null -> TextAlign.Start
                            }
                            Box(
                                modifier = Modifier
                                    .weight(cellWeight)
                                    .then(
                                        // The whole-header row already tints the row; a stray
                                        // header cell in a data row still needs its own tint.
                                        if (cell.header && !rowAllHeader) {
                                            Modifier.background(headerBackground)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .border(0.5.dp, border)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = cell.text,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = if (cell.header) FontWeight.SemiBold else FontWeight.Normal,
                                    color = contentColor,
                                    textAlign = align,
                                    // Fill the cell so `textAlign` actually anchors the text; a
                                    // wrap-content Text would ignore centring and stay left.
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Content-derived column weights, in **dp of preferred width**, one entry per logical column.
 *
 * This is the whole column-sizing heuristic and it is deliberately a pure function of the model so
 * it can be asserted without pixels (see `ForumTableLayoutTest`).
 *
 * For each column `j` the *longest* cell that reaches it sets `len[j]` — the longest cell is what
 * decides whether the column can keep its text on a sensible number of lines. A `colspan` cell's
 * demanded width is divided across the columns it covers (so a merged cell does not inflate only
 * its first column). Then:
 *
 *  - `len[j] * CHAR_WIDTH_DP` is the width the column would like at the body font size;
 *  - it is clamped to `[MIN_COLUMN_WEIGHT_DP, MAX_COLUMN_WEIGHT_DP]`. The minimum keeps short
 *    labels and emoji (`❌ 否`, `小飞机网盘`) on a single line instead of one glyph per line; the
 *    maximum stops a long prose column from demanding an unbounded slice — it is allowed to wrap
 *    over a few lines instead.
 *  - If **every** column is long (`len >= LONG_COLUMN_CHARS`) the content no longer distinguishes
 *    the columns, so equal weights are returned (a wide, evenly split table that scrolls).
 *
 * A short 2/3-column table therefore sums well under the card width and is stretched to fill it;
 * the five-column 网盘 table sums past it and scrolls, with 备注 wider than 网盘名称.
 */
internal fun tableColumnWeights(rows: List<List<ForumBodyBlock.TableCell>>): List<Float> {
    if (rows.isEmpty()) return emptyList()
    val columns = rows.maxOf { row -> row.sumOf { it.span.coerceAtLeast(1) } }
    if (columns <= 0) return emptyList()
    val longest = IntArray(columns)
    rows.forEach { row ->
        var column = 0
        row.forEach { cell ->
            val span = cell.span.coerceAtLeast(1)
            val length = cell.text.text.length
            val perColumn = if (span <= 1) length else length / span
            for (offset in 0 until span) {
                if (column + offset < columns) {
                    longest[column + offset] = maxOf(longest[column + offset], perColumn)
                }
            }
            column += span
        }
    }
    val lengths = longest.toList()
    if (lengths.all { it >= LONG_COLUMN_CHARS }) {
        return List(columns) { EQUAL_COLUMN_WEIGHT_DP }
    }
    return lengths.map { length ->
        (length * CHAR_WIDTH_DP).coerceIn(MIN_COLUMN_WEIGHT_DP, MAX_COLUMN_WEIGHT_DP)
    }
}

/** Rough width of one body-font glyph (14sp plus the cell's horizontal padding share), in dp. */
private const val CHAR_WIDTH_DP = 16f

/** Minimum column width: keeps a short label or an emoji row on one line. */
private const val MIN_COLUMN_WEIGHT_DP = 64f

/** Maximum column width: a long prose column wraps over a few lines instead of hogging the row. */
private const val MAX_COLUMN_WEIGHT_DP = 200f

/** A column whose longest cell reaches this many characters stops discriminating width. */
private const val LONG_COLUMN_CHARS = 12

/** Equal weight used when every column is long: a wide, evenly split (scrolling) table. */
private const val EQUAL_COLUMN_WEIGHT_DP = 120f

/**
 * Attachment card with an explicit download affordance.
 *
 * The trailing download glyph and the tinted file-type badge are the "this is downloadable" cue;
 * tapping the card does not open the raw URL, it hands the block to the host so the user can pick
 * 「下载到本机」 or 「在浏览器打开」. The URL itself is cookie-gated, so a plain browser hop often
 * 403s — that choice is what makes both paths explicit rather than a silent no-op.
 *
 * An image attachment shows a real thumbnail instead of the generic file badge; it is still
 * downloadable (and its preview taps through to the image viewer), so the two affordances coexist.
 */
@Composable
private fun AttachmentCard(
    block: ForumBodyBlock.Attachment,
    contentColor: Color,
    onAttachmentClick: ((ForumBodyBlock.Attachment) -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.url) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable {
                if (onAttachmentClick != null) {
                    onAttachmentClick(block)
                } else {
                    // No host handler (e.g. a bare preview): copy rather than doing nothing, so a
                    // tap is never a silent no-op. The real pages always pass a handler.
                    clipboard.setText(AnnotatedString(block.url))
                    copied = true
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AttachmentBadge(block)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = block.name,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val caption = block.caption
            if (caption != null) {
                Text(
                    text = caption,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The card's size line already prints `下载积分: 金币 -1`; this reads it out in the
            // user's language so the cost is visible on the row before the chooser even opens.
            if (block.costCoins > 0) {
                Text(
                    text = stringResource(R.string.forum_attach_cost_hint, block.costCoins),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (copied) Icons.Rounded.AttachFile else Icons.Rounded.Download,
            contentDescription = stringResource(R.string.forum_attach_download),
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** File-type badge, or a real thumbnail when the attachment is an image. */
@Composable
private fun AttachmentBadge(block: ForumBodyBlock.Attachment) {
    val shape = RoundedCornerShape(6.dp)
    val preview = block.previewUrl
    // A badge is a courtesy, not content: when it cannot load (offline, CDN down) it must fall back
    // to the local icon rather than leaving a broken-image plate on the card.
    var badgeFailed by remember(block.iconUrl) { mutableStateOf(false) }
    when {
        preview != null -> AsyncImage(
            model = preview,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(MiuixTheme.colorScheme.surfaceContainer),
            contentScale = ContentScale.Crop,
        )

        block.iconUrl != null && !block.isImage && !badgeFailed -> AsyncImage(
            model = block.iconUrl,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            contentScale = ContentScale.Fit,
            onError = { badgeFailed = true },
        )

        else -> Box(
            modifier = Modifier
                .size(36.dp)
                .clip(shape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (block.isImage) Icons.Rounded.Image else Icons.Rounded.AttachFile,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * An honest card for a media embed the native renderer cannot play.
 *
 * The site's `[media]`/`[audio]`/`[flash]` become an iframe/video/script player. Instead of
 * silently dropping that (the old skip-tag behaviour), the label and the URL stay visible and the
 * card opens the URL in the platform browser. Content the client cannot play is never hidden.
 */
@Composable
private fun MediaCard(block: ForumBodyBlock.Media, contentColor: Color) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable { runCatching { uriHandler.openUri(block.url) } }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = block.label ?: "媒体",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )
            Text(
                text = block.url,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoChip(text: AnnotatedString, background: Color, foreground: Color) {    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, fontSize = 12.sp, lineHeight = 17.sp, color = foreground)
    }
}

@Composable
private fun PictureBlock(block: ForumBodyBlock.Picture, onImageClick: (String) -> Unit) {
    // A full-width AsyncImage with no intrinsic size collapses to zero height until the bitmap
    // arrives, and for images whose dimensions are unknown Coil never reserves space — the
    // picture then simply never appears. Constraining the height and using Fit keeps an unloaded
    // image visible as a placeholder block instead of nothing.
    //
    // A failed load retries once against the forum host for Discuz asset paths: cookie-gated
    // attachments (`/data/attachment/...`) are only reachable there, where the image client
    // carries the session. Anything still failing is logged (URL only, never cookies) so the
    // blank block is diagnosable.
    var fallbackUrl by remember(block.url) { mutableStateOf<String?>(null) }
    var failedHost by remember(block.url) { mutableStateOf<String?>(null) }
    Box(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = fallbackUrl ?: block.url,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 480.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .clickable { onImageClick(fallbackUrl ?: block.url) },
            contentScale = ContentScale.FillWidth,
            onError = {
                val retry = if (fallbackUrl == null) ForumUrls.imageFallback(block.url) else null
                if (retry != null) {
                    Timber.tag("ForumThread").i("picture %s failed, retrying %s", block.url, retry)
                    fallbackUrl = retry
                } else {
                    Timber.tag("ForumThread").w("picture failed: %s", block.url)
                    // Show the failing host so a screenshot alone identifies the culprit
                    // (CDN vs forum host vs a stale third-party domain).
                    failedHost = runCatching { java.net.URI(block.url).host }.getOrNull()
                        ?: block.url
                }
            },
        )
        val host = failedHost
        if (host != null) {
            Text(
                text = "⚠ $host",
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** Text of a block that has no dedicated nested renderer, used when degrading inside a quote. */
private fun ForumBodyBlock.flattenedText(): AnnotatedString = when (this) {
    is ForumBodyBlock.Paragraph -> text
    is ForumBodyBlock.Heading -> text
    is ForumBodyBlock.ModeratorNote -> text
    is ForumBodyBlock.EditNote -> text
    is ForumBodyBlock.Quote -> text
    is ForumBodyBlock.CodeBlock -> AnnotatedString(text)
    is ForumBodyBlock.Attachment -> AnnotatedString(name)
    is ForumBodyBlock.Media -> AnnotatedString(label ?: url)
    else -> AnnotatedString("")
}

/** A short plain-text preview of a post body, for rows that should not render pictures. */
@Composable
fun ForumHtmlPreview(
    html: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    val text = remember(html) { ForumBodyParser.plainText(html) }
    if (text.isBlank()) return
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = maxLines,
    )
}
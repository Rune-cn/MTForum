// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * heading sizes keep their hierarchy, quotes carry an author header, and lists/tables/attachments
 * are real widgets rather than `<li>`-per-paragraph prose.
 */
internal sealed interface ForumBodyBlock {
    /** A paragraph of styled text; blank entries are dropped by the parser. */
    data class Paragraph(
        val text: AnnotatedString,
        val textAlign: TextAlign? = null,
    ) : ForumBodyBlock

    /** A standalone picture (attachment or large image), rendered at full width. */
    data class Picture(val url: String) : ForumBodyBlock

    /** A run of consecutive inline smilies, rendered as small images on one row. */
    data class EmojiRow(val urls: List<String>) : ForumBodyBlock

    /** A quoted reply (`<blockquote>` / `div.comiis_quote`), inset behind a leading rule. */
    data class Quote(
        val text: AnnotatedString,
        val author: String? = null,
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

    /** A simple grid parsed from `table/tr/td`. */
    data class Table(val rows: List<List<AnnotatedString>>) : ForumBodyBlock

    /** An attachment card (`div.comiis_attach`), tappable to open the download URL. */
    data class Attachment(val name: String, val caption: String?, val url: String) : ForumBodyBlock

    /** The template's moderator-action note (`div.comiis_modact`). */
    data class ModeratorNote(val text: AnnotatedString) : ForumBodyBlock

    /** The template's edit remark (`i.pstatus`). */
    data class EditNote(val text: AnnotatedString) : ForumBodyBlock
}

/**
 * Renders the raw HTML body of a forum post natively.
 *
 * Bold / italic / underline / strike / link / font colour survive, inline smilies stay in the
 * text flow as small images, quotes are inset, code blocks keep their monospace layout, and
 * attachments go full width. Every URL is normalised against the site so protocol-relative CDN
 * sources still load.
 */
@Composable
fun ForumHtmlText(
    html: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    onImageClick: (String) -> Unit = {},
    onLinkClick: ((String) -> Unit)? = null,
) {
    val linkColor = MiuixTheme.colorScheme.primary
    val quoteColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val blocks = remember(html, linkColor) { ForumBodyParser.parse(html, linkColor) }
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = onLinkClick ?: { uriHandler.openUri(it) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
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

                is ForumBodyBlock.Quote -> QuoteBlock(block, quoteColor)

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

                is ForumBodyBlock.Attachment -> AttachmentCard(block, contentColor, openLink)

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
            }
        }
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

@Composable
private fun QuoteBlock(block: ForumBodyBlock.Quote, quoteColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(quoteColor.copy(alpha = 0.35f)),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
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
private fun BulletListRows(block: ForumBodyBlock.BulletList, contentColor: Color) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth().padding(start = (item.depth * 16).dp)) {
                Text(
                    text = item.marker,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = contentColor,
                    modifier = Modifier.width(22.dp),
                )
                Text(
                    text = item.text,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TableGrid(block: ForumBodyBlock.Table, contentColor: Color) {
    val border = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
    val columns = block.rows.maxOfOrNull { it.size } ?: 0
    if (columns == 0) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(0.5.dp, border, RoundedCornerShape(6.dp)),
    ) {
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(columns) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(0.5.dp, border)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        val cell = row.getOrNull(index)
                        if (cell != null) {
                            Text(
                                text = cell,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = contentColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentCard(
    block: ForumBodyBlock.Attachment,
    contentColor: Color,
    openLink: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable { openLink(block.url) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.AttachFile,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InfoChip(text: AnnotatedString, background: Color, foreground: Color) {
    Row(
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

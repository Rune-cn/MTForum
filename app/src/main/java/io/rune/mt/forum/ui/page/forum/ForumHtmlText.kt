// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One flattened piece of a Discuz post body.
 *
 * The site emits **HTML, not BBCode** — verified against several real threads, which carry
 * `<strong>`/`<i>`/`<u>`/`<font color>`/`<a>` inside `<div>` paragraphs plus `<img>` for smilies
 * and attachments. Rendering that with a WebView would pull in a whole browser engine, so the
 * body is flattened into these blocks and laid out natively; inline styling rides along in an
 * [AnnotatedString], which is Compose's own answer to rich text.
 */
internal sealed interface ForumBodyBlock {
    /** A paragraph of styled text; blank entries are dropped by the parser. */
    data class Paragraph(val text: AnnotatedString) : ForumBodyBlock

    /** A standalone picture (attachment or large image), rendered at full width. */
    data class Picture(val url: String) : ForumBodyBlock

    /** A run of consecutive inline smilies, rendered as small images on one row. */
    data class EmojiRow(val urls: List<String>) : ForumBodyBlock

    /** A quoted reply (`<blockquote>`), rendered inset behind a leading rule. */
    data class Quote(val text: AnnotatedString) : ForumBodyBlock
}

/**
 * Renders the raw HTML body of a forum post natively.
 *
 * Bold / italic / underline / strike / link / font colour survive, inline smilies stay in the
 * text flow as small images, quotes are inset, and attachments go full width. Every URL is
 * normalised against the site so protocol-relative CDN sources still load.
 */
@Composable
fun ForumHtmlText(
    html: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    onImageClick: (String) -> Unit = {},
) {
    val linkColor = MiuixTheme.colorScheme.primary
    val quoteColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val blocks = remember(html, linkColor) { ForumBodyParser.parse(html, linkColor) }

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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is ForumBodyBlock.Quote -> {
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
                        Text(
                            text = block.text,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = quoteColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp),
                        )
                    }
                }

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

                is ForumBodyBlock.Picture -> {
                    // A full-width AsyncImage with no intrinsic size collapses to zero height
                    // until the bitmap arrives, and for images whose dimensions are unknown
                    // Coil never reserves space — the picture then simply never appears.
                    // Constraining the height and using Fit keeps an unloaded image visible as
                    // a placeholder block instead of nothing.
                    AsyncImage(
                        model = block.url,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp, max = 480.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onImageClick(block.url) },
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
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


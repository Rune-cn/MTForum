// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** Where a picked picture goes. */
internal enum class ComposerImageTarget {
    /**
     * The MT image host (`img.binmt.cc`) — a **separate service**; the post gets its `[img]` line
     * and the picture keeps its original quality. This is the composer's **default**.
     */
    ImageHost,

    /** The forum's own attachment (`Filedata` on the thread POST), re-encoded to fit 1 MB. */
    Attachment,
}

/** Test tag of the image-host row in the choice sheet. */
internal const val COMPOSER_IMAGE_HOST_ROW_TAG = "composer_image_host_row"

/** Test tag of the attachment row in the choice sheet. */
internal const val COMPOSER_IMAGE_ATTACH_ROW_TAG = "composer_image_attach_row"

/**
 * The 添加图片 chooser: 「插入图床链接（清晰）」 or 「作为附件上传（≤1MB）」.
 *
 * The two paths are genuinely different services, and the difference is user-visible (full
 * resolution vs. the forum's 1 MB re-compression), so it is asked on every pick instead of being
 * decided silently. The image host is listed first and marked as the default because the whole
 * reason the host exists (bbs.binmt.cc thread 98890) is publishing pictures the forum would
 * otherwise compress; the attachment path stays available because it is the site's own storage and
 * works with one POST.
 *
 * Style split (rule 8): OS4 gets the inline glass sheet, OS3 the classic window sheet, exactly like
 * `ForumSignInSheet`. The OS4 variant is an inline overlay, so [NewThreadPage] composes it as a
 * sibling of the scaffold in the page root `Box` and passes `chrome.backdrop` — never the host-level
 * page backdrop (rules 23/29).
 */
@Composable
internal fun ComposerImageSourceSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onChoose: (ComposerImageTarget) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    val title = stringResource(R.string.composer_image_source_title)
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            ComposerImageSourceRow(
                icon = Icons.Rounded.Image,
                label = stringResource(R.string.composer_image_source_host),
                detail = stringResource(R.string.composer_image_source_host_detail),
                tag = COMPOSER_IMAGE_HOST_ROW_TAG,
                onClick = { onChoose(ComposerImageTarget.ImageHost) },
            )
            HorizontalDivider()
            ComposerImageSourceRow(
                icon = Icons.Rounded.AttachFile,
                label = stringResource(R.string.composer_image_source_attach),
                detail = stringResource(R.string.composer_image_source_attach_detail),
                tag = COMPOSER_IMAGE_ATTACH_ROW_TAG,
                onClick = { onChoose(ComposerImageTarget.Attachment) },
            )
            Spacer(Modifier.height(10.dp))
            // Says which one is the default, and why — never let the ordering alone imply it.
            Text(
                text = stringResource(R.string.composer_image_source_default_hint),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (AppTheme.useMiuixOs4) {
        GlassBottomSheet(
            onDismissRequest = onDismissRequest,
            show = show,
            backdrop = backdrop,
            title = title,
            insideMargin = DpSize(16.dp, 0.dp),
            modifier = modifier,
        ) {
            body()
        }
    } else {
        WindowBottomSheet(
            show = show,
            onDismissRequest = onDismissRequest,
            title = title,
            insideMargin = DpSize(16.dp, 0.dp),
            backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier,
        ) {
            body()
        }
    }
}

/** One chooser row: leading icon, label, and the one-line explanation of the trade-off. */
@Composable
private fun ComposerImageSourceRow(
    icon: ImageVector,
    label: String,
    detail: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
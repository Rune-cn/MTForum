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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.icons.AppMiuixIcons
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * The attachment chooser: 「下载到本机」 / 「在浏览器打开」 / 「复制链接」.
 *
 * A tap on an attachment card must not silently open the raw URL: that URL is cookie-gated, so a
 * plain browser hop frequently 403s or lands on the acw challenge page. Offering the choice — and
 * performing the download through the authenticated HTTP stack — is what makes the card useful.
 *
 * The sheet is a real dialog window (`WindowBottomSheet`), so it is reachable from the thread list
 * without being clipped by the `LazyColumn` item that owns the card. While a download runs the
 * row turns into its own progress/result panel: the sheet is where the user looks after tapping,
 * so a Snackbar-only report would be easy to miss. Progress is indeterminate when the server sends
 * no `Content-Length`, and a failure is shown with its reason instead of a silent no-op.
 *
 * OS4 gets the glass sheet (`GlassBottomSheet`) and OS3 the classic `WindowBottomSheet`, following
 * the project's rule 8 style split; `useMiuixOs4` comes from the theme, exactly as elsewhere.
 */
@Composable
internal fun AttachmentChoiceSheet(
    target: ForumBodyBlock.Attachment?,
    download: AttachmentDownloadState?,
    onDismissRequest: () -> Unit,
    onDownload: (ForumBodyBlock.Attachment) -> Unit,
    onCancelDownload: () -> Unit,
    onOpenInBrowser: (ForumBodyBlock.Attachment) -> Unit,
    onShareFile: (ForumBodyBlock.Attachment) -> Unit,
    onSaveFile: (ForumBodyBlock.Attachment) -> Unit,
) {
    val show = target != null
    val copiedMessage = stringResource(R.string.forum_attach_copied)
    val clipboard = LocalClipboardManager.current
    var copied by remember(target?.url) { mutableStateOf(false) }

    WindowBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.forum_attach_sheet_title),
        startAction = {
            val dismissState = LocalDismissState.current
            MiuixBackButton(
                icon = AppMiuixIcons.Close,
                onClick = { dismissState?.invoke() ?: onDismissRequest() },
            )
        },
        insideMargin = DpSize(16.dp, 0.dp),
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
    ) {
        val attachment = target ?: return@WindowBottomSheet
        Column(modifier = Modifier.fillMaxWidth()) {
            AttachmentHeader(attachment)

            // Paid attachments: say so before the user commits. The charge itself is enforced by
            // the site through the attachcredit handshake; the card value is only the advertised
            // price, so it is a hint and never a local balance check.
            if (attachment.costCoins > 0) {
                Text(
                    text = stringResource(R.string.forum_attach_cost_hint, attachment.costCoins),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (download != null) {
                Spacer(Modifier.height(12.dp))
                DownloadProgressPanel(
                    state = download,
                    onCancel = onCancelDownload,
                    onShare = { onShareFile(download.attachment) },
                    onSave = { onSaveFile(download.attachment) },
                    onDismiss = onDismissRequest,
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            if (download == null) {
                AttachmentSheetRow(
                    icon = Icons.Rounded.Download,
                    label = stringResource(R.string.forum_attach_download),
                    detail = stringResource(R.string.forum_attach_download_detail),
                    onClick = { onDownload(attachment) },
                )
            }
            AttachmentSheetRow(
                icon = Icons.Rounded.OpenInBrowser,
                label = stringResource(R.string.forum_attach_open_browser),
                detail = stringResource(R.string.forum_attach_open_browser_detail),
                onClick = { onOpenInBrowser(attachment) },
            )
            AttachmentSheetRow(
                icon = Icons.Rounded.ContentCopy,
                label = if (copied) copiedMessage else stringResource(R.string.thread_action_copy_link),
                detail = attachment.url,
                onClick = {
                    clipboard.setText(AnnotatedString(attachment.url))
                    copied = true
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** File name, size line and a type badge: what the user is about to download. */
@Composable
private fun AttachmentHeader(attachment: ForumBodyBlock.Attachment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (attachment.isImage) Icons.Rounded.Image else Icons.Rounded.AttachFile,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            attachment.caption?.let { caption ->
                Text(
                    text = caption,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * In-sheet progress and result.
 *
 * A determinate bar when the server reported a size, an indeterminate one otherwise. Failure shows
 * the reason (HTTP code, challenge page, truncated body) so "nothing happened" is never the whole
 * story; once finished the row offers the share hand-off instead of downloading again.
 */
@Composable
private fun DownloadProgressPanel(
    state: AttachmentDownloadState,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val error = state.error
    val done = error == null && state.progress == 1f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            error != null -> Text(
                text = stringResource(R.string.forum_attach_download_failed, error),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.error,
            )

            done -> Text(
                text = stringResource(R.string.forum_attach_download_done),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.primary,
            )

            else -> {
                Text(
                    text = stringResource(R.string.forum_attach_downloading),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                val fraction = state.progress
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                error != null -> TextButton(
                    text = stringResource(R.string.forum_attach_retry),
                    onClick = onDismiss,
                )

                done -> {
                    // Two exits for a finished download: a real copy the user can find later, and
                    // the system share sheet. Both used to be a single dead button because the
                    // share built a file:// URI (FileUriExposedException swallowed by runCatching).
                    TextButton(
                        text = stringResource(R.string.forum_attach_save),
                        onClick = onSave,
                    )
                    TextButton(
                        text = stringResource(R.string.forum_attach_share),
                        onClick = onShare,
                    )
                }

                else -> TextButton(text = stringResource(R.string.forum_attach_cancel), onClick = onCancel)
            }
        }
    }
}

/** One row of the chooser: leading icon, label, optional detail line. */
@Composable
private fun AttachmentSheetRow(
    icon: ImageVector,
    label: String,
    detail: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 15.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurface)
            if (detail != null) {
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
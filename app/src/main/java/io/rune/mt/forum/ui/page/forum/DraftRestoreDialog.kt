// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Restore prompt for a client-local draft (发帖 or 回复).
 *
 * It is deliberately *not* [io.rune.mt.forum.ui.page.miuix.widgets.MiuixConfirmDialog]: that one
 * tints its confirm button with the error colour for destructive actions, while restoring a draft
 * is the safe, primary choice here. 丢弃 is the secondary text button.
 *
 * Both composers share it so the wording and behaviour stay identical: 恢复草稿 fills the fields,
 * 丢弃 deletes the stored draft and leaves the composer empty. A back/outside dismissal
 * ([onDismiss]) hides the prompt *without* deleting anything — the draft comes back on the next
 * open — so an accidental tap can never destroy it.
 */
@Composable
internal fun DraftRestoreDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    discardText: String,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(show = visible, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = discardText,
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = confirmText)
                }
            }
        }
    }
}
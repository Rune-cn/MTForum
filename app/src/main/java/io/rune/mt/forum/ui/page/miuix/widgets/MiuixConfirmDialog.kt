// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Two-button confirmation dialog.
 *
 * Used for destructive actions that would otherwise fire on a single tap — signing out throws
 * away the saved session, so it must not be one accidental touch away. The confirm button is
 * tinted with the error colour to read as the dangerous choice.
 */
@Composable
fun MiuixConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MiuixStyleDialog(show = visible, onDismissRequest = onDismiss) {
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
                    text = dismissText,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError,
                    ),
                ) {
                    Text(text = confirmText)
                }
            }
        }
    }
}

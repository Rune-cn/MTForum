// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.data.forum.model.ForumSmiley
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Reply composer, opened from the floating button or from a floor's 回复 action.
 *
 * Discuz rejects a reply without `formhash`/`noticeauthor`, both of which come from the loaded
 * thread page, so the composer only opens once those are present (the view model enforces it).
 * Picking a smiley appends its BBCode token, which the server converts back into an image.
 */
@Composable
internal fun ReplyComposer(
    state: ReplyState,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    WindowDialog(show = state.visible, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (state.quoteAuthor != null) "回复 ${state.quoteAuthor}" else "回复",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )

            if (state.quoteText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.quoteText,
                    fontSize = 12.sp,
                    maxLines = 3,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            Spacer(Modifier.height(12.dp))
            TextField(
                value = state.text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 110.dp),
                singleLine = false,
                minLines = 4,
            )

            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(text = state.error, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                text = if (pickerOpen) "收起表情" else "表情",
                onClick = { pickerOpen = !pickerOpen },
            )
            if (pickerOpen) {
                SmileyPicker(
                    onPick = { smiley: ForumSmiley -> onTextChange(state.text + smiley.code) },
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSubmit,
                    enabled = state.text.isNotBlank() && !state.isSubmitting,
                ) {
                    if (state.isSubmitting) {
                        InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(text = "发送")
                }
            }
        }
    }
}

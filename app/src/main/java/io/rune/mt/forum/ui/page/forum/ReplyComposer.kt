// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.R
import io.rune.mt.forum.data.forum.model.ForumSmiley
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Reply composer, opened from the thread page's floating button (and, historically, from a
 * floor's 回复 action — the quote fields stay for that path).
 *
 * Discuz rejects a reply without `formhash`/`noticeauthor`, both of which come from the loaded
 * thread page, so the composer only opens once those are present (the view model enforces it).
 * Picking a smiley appends its BBCode token, which the server converts back into an image.
 *
 * The dialog is laid out as a compact sheet: a header with the target (`回复` / `回复 @author`)
 * and a close button, the quoted floor as a chip, a 4–8 line field that auto-focuses and scrolls
 * internally, the smiley picker, the error line, and a prominent send button that shows progress.
 * `imePadding()` keeps the field and buttons above the keyboard.
 *
 * Drafts are **client-local** (the site cannot store them; see docs/REVERSE_SPEC.md §16). The
 * footer's 保存草稿 writes [io.rune.mt.forum.domain.forum.repository.ForumDraftRepository] only,
 * dismissing a non-blank sheet auto-saves it, and an existing draft for the thread is offered as a
 * 恢复草稿/丢弃 prompt instead of silently filling the field.
 */
@Composable
internal fun ReplyComposer(
    state: ReplyState,
    onTextChange: (String) -> Unit,
    onSaveDraft: () -> Unit,
    onSubmit: () -> Unit,
    onRestoreDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    onDismissRestorePrompt: () -> Unit,
    onDraftSavedShown: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val draftSavedText = stringResource(R.string.draft_saved_toast)

    LaunchedEffect(state.draftSaved) {
        if (state.draftSaved) {
            android.widget.Toast.makeText(context, draftSavedText, android.widget.Toast.LENGTH_SHORT).show()
            onDraftSavedShown()
        }
    }

    // Focus after the dialog window is attached; requesting it in the same frame as `show` is
    // dropped by the dialog's own focus reset. Closing resets the smiley panel so the next open
    // starts at the field.
    LaunchedEffect(state.visible) {
        if (state.visible) {
            delay(120)
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        } else {
            pickerOpen = false
        }
    }

    val errorText = state.errorRes?.let { stringResource(it) } ?: state.error

    WindowDialog(show = state.visible, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.quoteAuthor.isNullOrBlank()) {
                        stringResource(R.string.reply_composer_title)
                    } else {
                        stringResource(R.string.reply_composer_title_to_format, state.quoteAuthor.orEmpty())
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = onDismiss,
                    enabled = !state.isSubmitting,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            if (!state.quoteText.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Text(
                        text = state.quoteText.orEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            TextField(
                value = state.text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp, max = 220.dp)
                    .focusRequester(focusRequester),
                singleLine = false,
                minLines = 4,
                maxLines = 8,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = if (pickerOpen) {
                        stringResource(R.string.reply_composer_smiley_hide)
                    } else {
                        stringResource(R.string.reply_composer_smiley)
                    },
                    onClick = { pickerOpen = !pickerOpen },
                )
            }
            if (pickerOpen) {
                SmileyPicker(
                    onPick = { smiley: ForumSmiley -> onTextChange(state.text + smiley.code) },
                )
            }

            if (!errorText.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = errorText,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    enabled = !state.isSubmitting,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.draft_save),
                    onClick = onSaveDraft,
                    enabled = state.text.isNotBlank() && !state.isSubmitting,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSubmit,
                    modifier = Modifier.weight(1f),
                    enabled = state.text.isNotBlank() && !state.isSubmitting,
                ) {
                    if (state.isSubmitting) {
                        InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = if (state.isSubmitting) {
                            stringResource(R.string.reply_composer_sending)
                        } else {
                            stringResource(R.string.reply_composer_send)
                        },
                    )
                }
            }
        }
    }

    // Second window on top of the sheet: a saved local draft for this thread is offered, never
    // silently applied. A back/outside dismissal keeps the draft for the next open.
    DraftRestoreDialog(
        visible = state.visible && state.pendingDraft != null,
        title = stringResource(R.string.draft_restore_title),
        message = stringResource(R.string.draft_restore_message),
        confirmText = stringResource(R.string.draft_restore_confirm),
        discardText = stringResource(R.string.draft_restore_discard),
        onConfirm = onRestoreDraft,
        onDiscard = onDiscardDraft,
        onDismiss = onDismissRestorePrompt,
    )
}
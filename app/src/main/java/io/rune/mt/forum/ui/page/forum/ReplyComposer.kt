// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.R
import io.rune.mt.forum.data.forum.model.ForumSmiley
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBottomSheet
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBottomSheetCloseAction
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Test tag of the reply sheet's body: lets Robolectric identify the panel (and capture it, since a
 * `WindowBottomSheet` is a separate dialog *window*) without reaching into composition internals.
 */
internal const val REPLY_SHEET_TAG = "reply_sheet"

/**
 * Reply composer, opened from the thread page's floating button (and, historically, from a
 * floor's 回复 action — the quote fields stay for that path).
 *
 * Discuz rejects a reply without `formhash`/`noticeauthor`, both of which come from the loaded
 * thread page, so the composer only opens once those are present (the view model enforces it).
 * Picking a smiley appends its BBCode token, which the server converts back into an image.
 *
 * It is a bottom sheet, not a centred dialog: the same container as the 签到 panel
 * ([`MiuixBottomSheet`]: OS4 glass / OS3 window sheet), with the sheet's own header row carrying
 * the title (回复 / 回复 @author) and a close affordance. Style split and placement therefore follow
 * rule 8 / rule 12 exactly like the sign-in sheet — the OS4 branch must be composed at the thread
 * page's root `BoxScope` (see [`ForumThreadPage`]).
 *
 * The body keeps every behaviour the old dialog had: the quoted floor as a chip, a 4–8 line field
 * that auto-focuses and shows the keyboard, the smiley picker, the error line and a prominent send
 * button that shows progress. Keyboard handling is rule 24's single
 * `navigationBars.union(ime)` pass — the sheet is told not to add its own inset padding
 * (`defaultWindowInsetsPadding = false`), so the two can never stack.
 *
 * Replies carry **no draft feature**: 保存草稿 / 恢复草稿 belong to the new-thread composer only,
 * because the user asked for 帖子 and 评论 to stay separate. Closing the sheet drops whatever was
 * typed, so the footer is just 取消 / 发送.
 *
 * @param backdrop Page-chrome content layer for the OS4 glass surface, or `null` (opaque
 *   fallback). Ignored by the OS3 window sheet.
 */
@Composable
internal fun ReplyComposer(
    state: ReplyState,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    val title = if (state.quoteAuthor.isNullOrBlank()) {
        stringResource(R.string.reply_composer_title)
    } else {
        stringResource(R.string.reply_composer_title_to_format, state.quoteAuthor.orEmpty())
    }

    MiuixBottomSheet(
        show = state.visible,
        onDismissRequest = onDismiss,
        title = title,
        modifier = modifier,
        backdrop = backdrop,
        startAction = { MiuixBottomSheetCloseAction(onDismissRequest = onDismiss) },
        // The body owns the IME inset (rule 24); letting the sheet add one too would stack them.
        defaultWindowInsetsPadding = false,
    ) {
        ReplyComposerBody(
            state = state,
            onTextChange = onTextChange,
            onSubmit = onSubmit,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The reply sheet's body: quote chip, field, smiley picker, error line, 取消 / 发送 footer.
 *
 * Split out from [ReplyComposer] so the screenshot test can render the exact body the sheet hosts
 * without going through the dialog window (Robolectric cannot capture a `WindowBottomSheet`'s
 * pixels — see `ForumRichBodyScreenshotTest`).
 *
 * The bottom inset is the single rule-24 pass: `navigationBars.union(ime)` is the larger of the
 * keyboard and the navigation bar, so the field and the footer sit above the keyboard while it is
 * open and above the navigation bar while it is not — never both (`imePadding()` +
 * `navigationBarsPadding()` would count the keyboard twice).
 */
@Composable
internal fun ReplyComposerBody(
    state: ReplyState,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Focus after the sheet is attached; requesting it in the same frame as `show` is dropped by
    // the sheet's own focus reset. Closing resets the smiley panel so the next open starts at the
    // field.
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(REPLY_SHEET_TAG)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        if (!state.quoteText.isNullOrBlank()) {
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
            Spacer(Modifier.height(10.dp))
        }

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
        Spacer(Modifier.height(8.dp))
    }
}
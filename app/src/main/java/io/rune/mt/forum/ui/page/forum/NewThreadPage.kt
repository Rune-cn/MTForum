// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixConfirmDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Test tag of the composer's 表情 action. */
internal const val COMPOSER_SMILEY_TAG = "composer_smiley"

/** Test tag of the composer's 添加图片 action. */
internal const val COMPOSER_ADD_IMAGE_TAG = "composer_add_images"

/** Test tag of the attached-images strip. */
internal const val COMPOSER_IMAGE_ROW_TAG = "composer_image_row"

/**
 * Compose a new thread (`forum.php?mod=post&action=newthread&fid=`), or edit an existing **site
 * draft** (`action=edit`).
 *
 * The body is BBCode, exactly like the reply composer, because that is what the site's own editor
 * posts. 添加图片 asks **where** a picture goes
 * (`ComposerImageSourceSheet`): the MT image host (`img.binmt.cc`, its own service — the returned
 * `[img]…[/img]` is inserted at the caret and the picture keeps its original quality) or the forum's
 * attachment path (multipart `Filedata` parts on the same POST, re-compressed to fit 1 MB). The
 * image host is the default because the reason it exists — bbs.binmt.cc thread 98890 — is exactly
 * avoiding that re-compression; see docs/REVERSE_SPEC.md §24 and docs/HANDOVER_CN.md §21. Every
 * pick therefore ends with visible feedback (accepted with name/size or the inserted link, or
 * rejected with the reason), because an earlier build dropped files over 1 MB without a word and
 * the button looked dead.
 *
 * The target board is changeable **inside** the composer: the 发布到 row opens a board sheet fed by
 * the site index, and choosing a board re-fetches its form (`formhash`/`posttime` are per board)
 * while 发布 stays disabled until that form is back. A draft keeps its own board — see
 * [ComposerBoardRow].
 *
 * Drafts are stored by the site: 保存草稿 POSTs the very same form with the hidden `save` field set
 * to `1`, creating (or updating) an author-only draft thread that is listed under 草稿. The
 * device store is only the fallback for text a failed save never delivered. Two actions are
 * deliberately explicit and confirmed:
 * - 发表 publishes a draft (`save` empty), i.e. it turns the draft into a real thread;
 * - 删除 drops it (`delete=1`).
 * Leaving the page never publishes and never posts anything: the `DisposableEffect` below only
 * writes the offline copy.
 */
@Composable
fun NewThreadPage(
    fid: Int,
    forumName: String,
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    draftTid: Int = 0,
    draftPid: Long? = null,
    viewModel: NewThreadViewModel = koinViewModel(),
) {
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }
    var boardPickerOpen by remember { mutableStateOf(false) }
    var imageSheetOpen by remember { mutableStateOf(false) }
    // Which destination the next pick uses: the image host (default) or a forum attachment.
    var imageTarget by remember { mutableStateOf(ComposerImageTarget.ImageHost) }
    var publishConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    // The preview toggle renders the draft through the same ForumBodyParser/ForumHtmlText pipeline
    // a received post uses; it never sends or mutates the draft.
    var previewOpen by remember { mutableStateOf(false) }
    val draftSavedText = stringResource(R.string.draft_saved_toast)
    val publishedText = stringResource(R.string.composer_published_toast)
    val draftDeletedText = stringResource(R.string.composer_draft_deleted_toast)

    // The field owns the caret; the view model owns the text (so a programmatic insert — image-host
    // `[img]`, emoji, BBCode tag — can land at the user's caret and hand the new caret back).
    var field by remember { mutableStateOf(TextFieldValue("")) }

    val pickImages = rememberLauncherForActivityResult(
        // OpenMultipleDocuments (not GetMultipleContents) so the read permission is persistable
        // and a draft restored after process death can still read its images.
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        when (imageTarget) {
            // One upload per picked file; each `[img]…[/img]` is inserted at the caret.
            ComposerImageTarget.ImageHost -> uris.forEach { uri -> viewModel.uploadImageToHost(context, uri) }
            ComposerImageTarget.Attachment -> viewModel.attachImages(context, uris)
        }
    }

    LaunchedEffect(fid, draftTid) { viewModel.open(fid, forumName, draftTid, draftPid) }
    LaunchedEffect(state.successTid, state.successMessage) {
        if (state.successTid != null || state.successMessage != null) {
            android.widget.Toast.makeText(context, publishedText, android.widget.Toast.LENGTH_SHORT).show()
            navigator.pop()
        }
    }
    LaunchedEffect(state.draftDeleted) {
        if (state.draftDeleted) {
            android.widget.Toast.makeText(context, draftDeletedText, android.widget.Toast.LENGTH_SHORT).show()
            navigator.pop()
        }
    }
    // Saved-draft confirmation (explicit 保存草稿 only; the offline copy stays silent).
    LaunchedEffect(state.draftSaved) {
        if (state.draftSaved) {
            android.widget.Toast.makeText(context, draftSavedText, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeDraftSaved()
        }
    }
    LaunchedEffect(state.restoreImageUris) {
        if (state.restoreImageUris.isNotEmpty()) viewModel.resolveRestoredImages(context)
    }
    // A programmatic edit (image-host insert, emoji, BBCode tag) changes the view model's text;
    // pull it back into the field together with the caret that edit left behind. Nothing happens
    // on a normal keystroke: the field was updated first, so `field.text == state.message`.
    LaunchedEffect(state.message, state.messageCaret) {
        if (field.text != state.message) {
            field = TextFieldValue(
                text = state.message,
                selection = TextRange(state.messageCaret.coerceIn(0, state.message.length)),
            )
        }
    }
    // Save-on-leave: covers the back button and a predictive-back dismissal of this entry. It only
    // writes the offline copy — never a network save, never a publish — and the VM skips it when
    // the composer is blank, when a draft is already un-actioned, or after publish/delete.
    DisposableEffect(Unit) {
        onDispose { viewModel.saveOnLeave() }
    }

    // The page root `Box` exists for the board sheet: the OS4 glass sheet is an inline overlay that
    // must be a sibling of the scaffold (rules 12/23), and it stays outside the content subtree
    // `chrome.backdrop` records, so the panel can never sample itself.
    Box(modifier = Modifier.fillMaxSize()) {
        MiuixPageScaffold(
            title = if (state.isDraftEdit) {
                stringResource(R.string.composer_title_edit_draft)
            } else {
                stringResource(R.string.composer_title_new)
            },
            chrome = chrome,
            outerPadding = outerPadding,
            horizontalPadding = 12.dp,
            verticalPadding = 12.dp,
            navigationIcon = {
                MiuixBackButton(onClick = {
                    viewModel.saveOnLeave()
                    navigator.pop()
                })
            },
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        // 发布到：<板块> — the target board is part of the composer, not a decision
                        // made for the user by the route that opened it.
                        ComposerBoardRow(
                            forumName = state.forumName,
                            fid = state.fid,
                            isLoadingForm = state.isLoadingForm,
                            isDraft = state.isDraftEdit,
                            onClick = { boardPickerOpen = true },
                        )
                        if (state.isLoadingForm) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.composer_form_loading),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        if (state.isDraftEdit) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.draft_edit_hint),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.composer_subject_label),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextField(
                            value = state.title,
                            onValueChange = viewModel::updateTitle,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.composer_message_label),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextField(
                            value = field,
                            onValueChange = { value ->
                                field = value
                                viewModel.updateMessage(value.text, value.selection.end)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                            singleLine = false,
                            minLines = 8,
                        )

                        // The same BBCode toolbar the PM composer has, inserting paired tags at the
                        // caret (wrappers leave the caret in the middle, [list]/[code] get
                        // skeletons). The dedicated 添加图片 row below keeps the 图床/附件 choice,
                        // so the toolbar carries no image button here.
                        Spacer(Modifier.height(8.dp))
                        ComposerBbcToolbar(
                            onWrapTag = viewModel::insertTag,
                            onInsertList = viewModel::insertList,
                            onInsertCode = viewModel::insertCode,
                            previewOpen = previewOpen,
                            onTogglePreview = { previewOpen = !previewOpen },
                        )

                        if (state.images.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().testTag(COMPOSER_IMAGE_ROW_TAG),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(items = state.images, key = { it.uri.toString() }) { image ->
                                    Box {
                                        AsyncImage(
                                            model = image.uri,
                                            contentDescription = image.fileName,
                                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                        TextButton(
                                            text = "×",
                                            onClick = { viewModel.removeImage(image.uri) },
                                            modifier = Modifier.align(Alignment.TopEnd),
                                        )
                                    }
                                }
                            }
                        }
                        // One line per picked file: what was attached (name + the size that will
                        // be uploaded) or why it was not. Silent drops are the bug this replaces.
                        state.imageFeedback.forEach { feedback ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = imageFeedbackText(feedback),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = if (feedback.accepted) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.error
                                },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.composer_image_hint),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (state.skippedRestoredImages > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.draft_images_skipped_format,
                                    state.skippedRestoredImages,
                                ),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }

                        // Action row: the two actions share the row's own 8dp arrangement instead of
                        // sitting flush against each other (the reported "表情和添加图片太近了").
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                text = if (pickerOpen) {
                                    stringResource(R.string.composer_smiley_hide)
                                } else {
                                    stringResource(R.string.composer_smiley)
                                },
                                onClick = { pickerOpen = !pickerOpen },
                                modifier = Modifier.testTag(COMPOSER_SMILEY_TAG),
                                minHeight = 48.dp,
                            )
                            TextButton(
                                text = stringResource(R.string.composer_add_images),
                                // Asks where the picture goes (image host vs attachment) instead of
                                // silently picking one; see ComposerImageSourceSheet.
                                onClick = { imageSheetOpen = true },
                                modifier = Modifier.testTag(COMPOSER_ADD_IMAGE_TAG),
                                minHeight = 48.dp,
                            )
                        }
                        // Its own line: the host upload takes a moment and the label is a sentence,
                        // which would squeeze the two buttons on a narrow screen.
                        if (state.isUploadingImage) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                InfiniteProgressIndicator(modifier = Modifier.size(16.dp))
                                Text(
                                    text = stringResource(R.string.composer_image_host_uploading),
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                        if (pickerOpen) {
                            // Insert at the caret, not at the end: the emoji belongs where the user
                            // is typing (the same rule the image-host insert follows).
                            SmileyPicker(onPick = { smiley -> viewModel.insertText(smiley.code) })
                        }

                        state.error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(text = it, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                        }
                        state.draftError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(text = it, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                        }
                        state.hostUploadError?.let { reason ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.composer_image_host_failed_format, reason),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (state.isDraftEdit) {
                                TextButton(
                                    text = stringResource(R.string.drafts_delete_action),
                                    onClick = { deleteConfirm = true },
                                    enabled = !state.isDraftBusy && !state.isSubmitting,
                                )
                            } else {
                                TextButton(text = stringResource(R.string.cancel), onClick = {
                                    viewModel.saveOnLeave()
                                    navigator.pop()
                                })
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                text = stringResource(R.string.draft_save),
                                onClick = { viewModel.saveDraft(context) },
                                enabled = !state.isSubmitting && !state.isDraftBusy &&
                                    (state.title.isNotBlank() || state.message.isNotBlank() || state.images.isNotEmpty()),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    // A brand-new thread posts directly; turning a draft into a thread
                                    // is destructive enough (it becomes public) to ask first.
                                    if (state.isDraftEdit) publishConfirm = true else viewModel.submit(context)
                                },
                                enabled = state.canSubmit,
                            ) {
                                if (state.isSubmitting || state.isDraftBusy) {
                                    InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    if (state.isDraftEdit) {
                                        stringResource(R.string.draft_publish_confirm)
                                    } else {
                                        stringResource(R.string.composer_publish)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        ComposerImageSourceSheet(
            show = imageSheetOpen,
            onDismissRequest = { imageSheetOpen = false },
            onChoose = { target ->
                imageSheetOpen = false
                imageTarget = target
                pickImages.launch(arrayOf("image/*"))
            },
            // Page-chrome content layer, never the app-wide page backdrop (rules 23/29).
            backdrop = chrome.backdrop,
        )

        ComposerBoardPickerSheet(
            show = boardPickerOpen,
            boards = state.boards,
            selectedFid = state.fid,
            isLoading = state.isLoadingBoards,
            error = state.boardsError,
            onRetry = { viewModel.loadBoards(force = true) },
            onSelect = { board ->
                boardPickerOpen = false
                viewModel.selectBoard(board.fid, board.name)
            },
            onDismissRequest = { boardPickerOpen = false },
            // Page-chrome content layer, never the app-wide page backdrop (rules 23/28).
            backdrop = chrome.backdrop,
        )
    }

    DraftRestoreDialog(
        visible = state.pendingDraft != null || state.pendingServerDraft != null,
        title = stringResource(R.string.draft_restore_title),
        // The site's draft wins when both exist; say which one is being offered.
        message = if (state.pendingServerDraft != null) {
            stringResource(R.string.draft_restore_message_server)
        } else {
            stringResource(R.string.draft_restore_message_local)
        },
        confirmText = stringResource(R.string.draft_restore_confirm),
        discardText = stringResource(R.string.draft_restore_discard),
        onConfirm = {
            if (state.pendingServerDraft != null) viewModel.restoreServerDraft() else viewModel.restoreDraft()
        },
        onDiscard = viewModel::discardDraft,
        onDismiss = viewModel::dismissRestorePrompt,
    )

    MiuixConfirmDialog(
        visible = publishConfirm,
        title = stringResource(R.string.draft_publish_title),
        message = stringResource(R.string.draft_publish_message),
        confirmText = stringResource(R.string.draft_publish_confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            publishConfirm = false
            viewModel.publishDraft()
        },
        onDismiss = { publishConfirm = false },
    )

    MiuixConfirmDialog(
        visible = deleteConfirm,
        title = stringResource(R.string.drafts_delete_title),
        message = stringResource(R.string.drafts_delete_message_format, state.title),
        confirmText = stringResource(R.string.drafts_delete_action),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteConfirm = false
            viewModel.deleteDraft()
        },
        onDismiss = { deleteConfirm = false },
    )
}

/** The readable line for one pick: accepted files say their size, rejections say the reason. */
@Composable
private fun imageFeedbackText(feedback: ImagePickFeedback): String {
    val name = feedback.fileName
    val size = ComposerImagePolicy.formatBytes(feedback.byteCount)
    return when {
        // The image host keeps the original bytes; the line says the link went in, not "attached".
        feedback.hostUrl != null -> stringResource(R.string.composer_image_host_added_format, name)
        feedback.reason == null && feedback.downscaled ->
            stringResource(R.string.composer_image_added_scaled_format, name, size)

        feedback.reason == null -> stringResource(R.string.composer_image_added_format, name, size)
        feedback.reason == ImageRejectReason.TooLarge ->
            stringResource(R.string.composer_image_too_large_format, name, size)

        feedback.reason == ImageRejectReason.Unreadable ->
            stringResource(R.string.composer_image_unreadable_format, name)

        feedback.reason == ImageRejectReason.UnsupportedFormat ->
            stringResource(R.string.composer_image_unsupported_format, name)

        else -> stringResource(R.string.composer_image_over_cap_format, name, ComposerImagePolicy.MAX_IMAGES)
    }
}
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Compose a new thread (`forum.php?mod=post&action=newthread&fid=`), or edit an existing **site
 * draft** (`action=edit`).
 *
 * The body is BBCode, exactly like the reply composer, because that is what the site's own editor
 * posts. Images are attached inline (multipart `Filedata`), which is how the mobile form works.
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
    var publishConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val draftSavedText = stringResource(R.string.draft_saved_toast)

    val pickImages = rememberLauncherForActivityResult(
        // OpenMultipleDocuments (not GetMultipleContents) so the read permission is persistable
        // and a draft restored after process death can still read its images.
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.attachImages(context, uris) }

    LaunchedEffect(fid, draftTid) { viewModel.open(fid, forumName, draftTid, draftPid) }
    LaunchedEffect(state.successTid, state.successMessage) {
        if (state.successTid != null || state.successMessage != null) {
            android.widget.Toast.makeText(context, "发布成功", android.widget.Toast.LENGTH_SHORT).show()
            navigator.pop()
        }
    }
    LaunchedEffect(state.draftDeleted) {
        if (state.draftDeleted) {
            android.widget.Toast.makeText(context, "草稿已删除", android.widget.Toast.LENGTH_SHORT).show()
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
    // Save-on-leave: covers the back button and a predictive-back dismissal of this entry. It only
    // writes the offline copy — never a network save, never a publish — and the VM skips it when
    // the composer is blank, when a draft is already un-actioned, or after publish/delete.
    DisposableEffect(Unit) {
        onDispose { viewModel.saveOnLeave() }
    }

    MiuixPageScaffold(
        title = if (state.isDraftEdit) "编辑草稿" else "发表主题",
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
        if (state.isLoadingForm) {
            item { ForumLoadingCard() }
            return@MiuixPageScaffold
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = forumName.ifBlank { state.forumName.ifBlank { "版块 $fid" } },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (state.isDraftEdit) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.draft_edit_hint),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("标题", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextField(
                        value = state.title,
                        onValueChange = viewModel::updateTitle,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("正文(支持 [b] [i] [quote] 与表情)", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextField(
                        value = state.message,
                        onValueChange = viewModel::updateMessage,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        singleLine = false,
                        minLines = 8,
                    )

                    if (state.images.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            text = if (pickerOpen) "收起表情" else "表情",
                            onClick = { pickerOpen = !pickerOpen },
                        )
                        TextButton(text = "添加图片", onClick = { pickImages.launch(arrayOf("image/*")) })
                    }
                    if (pickerOpen) {
                        SmileyPicker(onPick = { smiley -> viewModel.updateMessage(state.message + smiley.code) })
                    }

                    state.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                    }
                    state.draftError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
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
                            TextButton(text = "取消", onClick = {
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
                            Text(if (state.isDraftEdit) "发表" else "发布")
                        }
                    }
                }
            }
        }
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
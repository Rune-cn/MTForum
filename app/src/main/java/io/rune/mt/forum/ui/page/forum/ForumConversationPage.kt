// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumPrivateMessage
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
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

/** Test tag of the PM composer's image action. */
internal const val PM_COMPOSER_IMAGE_TAG = "pm_composer_image"

/** Test tag of the PM composer's preview toggle. */
internal const val PM_COMPOSER_PREVIEW_TAG = "pm_composer_preview"

/**
 * One private-message conversation (`home.php?mod=space&do=pm&subop=view&touid=`).
 *
 * Bubbles alternate sides: the peer's on the left, ours on the right tinted with the primary
 * container. A composer pinned under the list sends through the page's own `pmform` credentials
 * (formhash + pmid), which the view model carries over from the parsed page.
 *
 * ## The composer speaks BBCode (docs/REVERSE_SPEC.md §12.3, §24)
 *
 * The site's own PM box accepts BBCode and the server renders it, so this box offers a minimal tag
 * toolbar (b/i/u/quote/url), an 添加图片 action that uploads to the **MT image host** and inserts
 * the returned `[img]…[/img]` at the caret, and a cheap preview that runs the draft through the
 * same `ForumBodyParser`/`ForumHtmlText` pipeline as a received message. The send path is
 * untouched: the same `formhash`/`touid`/`message` fields go to the same endpoint — the tags are
 * ordinary text until the site renders them.
 */
@Composable
fun ForumConversationPage(
    uid: Int,
    name: String,
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumMessagesViewModel = koinViewModel(),
) {
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.conversation.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // The field owns the caret; the view model owns the text, so an image-host `[img]` or a toolbar
    // tag can be inserted where the user was typing and hand the new caret back (see ComposerText).
    var field by remember(uid) { mutableStateOf(TextFieldValue("")) }
    var previewOpen by remember(uid) { mutableStateOf(false) }
    // Measured height of the pinned composer *including* its window insets. The list's bottom
    // content padding mirrors it so the newest bubble always stops above the composer, and the
    // value grows with the IME automatically because the composer consumes the IME inset.
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val composerHeight = with(LocalDensity.current) { composerHeightPx.toDp() }

    // One picture at a time: the host answers with one `[img]` per upload, inserted at the caret.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.uploadImageToHost(context, uri)
    }

    LaunchedEffect(uid) {
        viewModel.openConversation(uid, name)
    }
    // A programmatic edit changes the view model's draft; pull it back into the field with its caret.
    LaunchedEffect(state.draft, state.draftCaret) {
        if (field.text != state.draft) {
            field = TextFieldValue(
                text = state.draft,
                selection = TextRange(state.draftCaret.coerceIn(0, state.draft.length)),
            )
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MiuixPageScaffold(
            title = state.name.ifBlank { name }.ifBlank { "私信" },
            chrome = chrome,
            // The scaffold copies only the bottom of outerPadding into the list's content padding,
            // so this is exactly "leave room for the composer" without touching the shared helper.
            outerPadding = PaddingValues(
                bottom = outerPadding.calculateBottomPadding() + composerHeight,
            ),
            horizontalPadding = 12.dp,
            verticalPadding = 12.dp,
            listState = listState,
            navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        ) {
            when {
                state.isLoading -> item { ForumLoadingCard() }
                state.error != null -> item {
                    ForumErrorCard(message = state.error.orEmpty(), onRetry = { viewModel.refreshConversation() })
                }
                state.messages.isEmpty() -> item { MessagesInfoCard("暂无消息", "发送第一条私信开始对话。") }
                else -> items(items = state.messages, key = { "pm_${it.hashCode()}" }) { message ->
                    MessageBubble(message = message, peerName = state.name.ifBlank { name })
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Report the full height (content + insets) to the list padding above.
                .onSizeChanged { composerHeightPx = it.height }
                // adjustResize does not shrink an edge-to-edge window; the IME is an inset.
                // union() takes the larger bottom inset of the two, so the composer sits above
                // the keyboard when it is open and above the navigation bar when it is not,
                // without ever counting both (#imePadding + #navigationBarsPadding would stack).
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(innerGutter),
        ) {
            state.sendError?.let {
                Text(text = it, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
            }
            state.imageHostError?.let { reason ->
                Text(
                    text = stringResource(R.string.pm_image_failed_format, reason),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
            }
            // Preview: the same parser that renders received messages converts the draft's BBCode,
            // so `[b]`/`[img]`/`[quote]` show roughly how the site will display the message. Toggle
            // only — it never sends anything and never mutates the draft.
            if (previewOpen && state.draft.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        ForumHtmlText(html = state.draft, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            // Minimal BBCode toolbar (no rich-text engine): the site renders these tags server-side.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BbcToolbarButton(stringResource(R.string.pm_toolbar_bold)) { viewModel.insertBbcTag("b") }
                BbcToolbarButton(stringResource(R.string.pm_toolbar_italic)) { viewModel.insertBbcTag("i") }
                BbcToolbarButton(stringResource(R.string.pm_toolbar_underline)) { viewModel.insertBbcTag("u") }
                BbcToolbarButton(stringResource(R.string.pm_toolbar_quote)) { viewModel.insertBbcTag("quote") }
                BbcToolbarButton(stringResource(R.string.pm_toolbar_link)) { viewModel.insertBbcTag("url") }
                TextButton(
                    text = stringResource(R.string.pm_toolbar_image),
                    onClick = { pickImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.testTag(PM_COMPOSER_IMAGE_TAG),
                    minHeight = 40.dp,
                )
                if (state.isUploadingImage) {
                    InfiniteProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.pm_image_uploading),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.width(6.dp))
                TextButton(
                    text = if (previewOpen) {
                        stringResource(R.string.pm_preview_hide)
                    } else {
                        stringResource(R.string.pm_preview_show)
                    },
                    onClick = { previewOpen = !previewOpen },
                    modifier = Modifier.testTag(PM_COMPOSER_PREVIEW_TAG),
                    minHeight = 40.dp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = field,
                    onValueChange = { value ->
                        field = value
                        viewModel.updateDraft(value.text, value.selection.end)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    minLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.sendDraft() },
                    enabled = state.canSend,
                ) {
                    if (state.isSending) {
                        InfiniteProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text(stringResource(R.string.pm_send))
                    }
                }
            }
            // The tags travel as text: the site resolves `[img]`/`[b]` when it displays the message. Only
            // shown once the draft actually uses markup (or the preview is open), so a plain message
            // does not pay for an extra line of explanation.
            if (previewOpen || state.draft.contains('[')) {
                Text(
                    text = stringResource(R.string.pm_bbcode_note),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                )
            }
        }
    }
}

/** One toolbar chip. Compact by design: the box is a message field, not a full editor. */
@Composable
private fun BbcToolbarButton(label: String, onClick: () -> Unit) {
    TextButton(text = label, onClick = onClick, minHeight = 40.dp)
}

/** Inner gutter shared by the lazy list and the pinned composer so they line up. */
private val innerGutter = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
private fun MessageBubble(message: ForumPrivateMessage, peerName: String) {
    val mine = message.isMine
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            ForumAvatar(url = message.avatarUrl, name = peerName, size = 32.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (mine) MiuixTheme.colorScheme.primaryContainer
                    else MiuixTheme.colorScheme.surfaceContainer,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (message.contentHtml.isNotBlank()) {
                ForumHtmlText(html = message.contentHtml)
            }
            message.timeText?.let { time ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = time,
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (mine) {
            Spacer(Modifier.width(8.dp))
            ForumAvatar(url = message.avatarUrl, name = peerName, size = 32.dp)
        }
    }
}

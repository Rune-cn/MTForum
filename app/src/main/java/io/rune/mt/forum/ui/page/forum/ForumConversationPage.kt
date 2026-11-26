// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.domain.forum.model.ForumPrivateMessage
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One private-message conversation (`home.php?mod=space&do=pm&subop=view&touid=`).
 *
 * Bubbles alternate sides: the peer's on the left, ours on the right tinted with the primary
 * container. A composer pinned under the list sends through the page's own `pmform` credentials
 * (formhash + pmid), which the view model carries over from the parsed page.
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
    val state by viewModel.conversation.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var draft by remember(uid) { mutableStateOf("") }
    // Measured height of the pinned composer *including* its window insets. The list's bottom
    // content padding mirrors it so the newest bubble always stops above the composer, and the
    // value grows with the IME automatically because the composer consumes the IME inset.
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val composerHeight = with(LocalDensity.current) { composerHeightPx.toDp() }

    LaunchedEffect(uid) {
        viewModel.openConversation(uid, name)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    minLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        viewModel.sendMessage(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !state.isSending,
                ) {
                    if (state.isSending) {
                        InfiniteProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text("发送")
                    }
                }
            }
        }
    }
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

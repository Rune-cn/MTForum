// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The 消息 tab: the list of private-message conversations.
 *
 * Tapping a row pushes [Route.PrivateConversation]; the conversation itself lives on its own page
 * so the list keeps its scroll position when the user comes back.
 */
@Composable
fun ForumMessagesPage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    viewModel: ForumMessagesViewModel = koinViewModel(),
) {
    val chrome = rememberMiuixPageChrome(enableBlur)
    val state by viewModel.listState.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    LaunchedEffect(Unit) {
        viewModel.loadConversationsIfNeeded()
    }

    MiuixPageScaffold(
        title = title,
        chrome = chrome,
        outerPadding = outerPadding,
        horizontalPadding = if (AppTheme.useMiuixOs4) 16.dp else 12.dp,
        verticalPadding = 12.dp,
    ) {
        if (!session.isLoggedIn) {
            item { MessagesInfoCard("登录后查看私信", "私信需要登录。") }
            return@MiuixPageScaffold
        }
        when {
            state.isLoading -> item { ForumLoadingCard() }
            state.error != null -> item {
                ForumErrorCard(message = state.error.orEmpty(), onRetry = { viewModel.refreshConversations() })
            }
            state.conversations.isEmpty() -> item { MessagesInfoCard("暂无会话", "还没有收到或发出私信。") }
            else -> items(items = state.conversations, key = { "pm_${it.uid}" }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    onClick = { navigator.push(Route.PrivateConversation(conversation.uid, conversation.name)) },
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ForumPrivateConversation,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        onClick = onClick,
        insideMargin = PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForumAvatar(url = conversation.avatarUrl, name = conversation.name, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.name.ifBlank { "用户 ${conversation.uid}" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    conversation.lastTimeText?.let {
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                conversation.lastPreview?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Simple centred card used for the login gate / empty state. */
@Composable
internal fun MessagesInfoCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

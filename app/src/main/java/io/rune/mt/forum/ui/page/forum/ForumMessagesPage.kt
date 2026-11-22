// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 消息 tab.
 *
 * Discuz serves private messages only through the logged-in `home.php?mod=space&do=pm` page,
 * which has not been reverse-engineered yet (it is a separate module from the forum screens).
 * Rather than invent a fake inbox, the tab states the limitation and shows the session state.
 */
@Composable
fun ForumMessagesPage(
    enableBlur: Boolean,
    title: String,
    outerPadding: PaddingValues,
    viewModel: io.rune.mt.forum.ui.page.login.LoginViewModel = koinViewModel(),
) {
    val chrome = rememberMiuixPageChrome(enableBlur)
    val session by viewModel.session.collectAsStateWithLifecycle()

    MiuixPageScaffold(
        title = title,
        chrome = chrome,
        outerPadding = outerPadding,
        horizontalPadding = 12.dp,
        verticalPadding = 12.dp,
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (session.isLoggedIn) "私信功能开发中" else "登录后查看私信",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (session.isLoggedIn) {
                            "站点的私信模块尚未接入，接入后可在此收发消息。"
                        } else {
                            "私信需要登录。"
                        },
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

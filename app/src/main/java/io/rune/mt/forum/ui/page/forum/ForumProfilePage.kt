// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumProfileField
import io.rune.mt.forum.domain.forum.model.ForumProfileStat
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One member's space profile (个人主页), reachable from the thread author link and from 我的.
 *
 * The site splits this information over several blocks: a header (avatar / nickname / level /
 * 人气·关注·粉丝), counters (帖子 / 回复 / 好友 / 粉丝), the credit block, the profile fields and a
 * separate 最近发表 listing. Every section is rendered only when the parser actually found it, so
 * members who hide parts of their profile simply show a shorter page instead of fake zeroes.
 */
@Composable
fun ForumProfilePage(
    uid: Int,
    fallbackName: String,
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumProfileViewModel = koinViewModel(),
) {
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gutter = if (AppTheme.useMiuixOs4) 16.dp else 12.dp

    LaunchedEffect(uid) { viewModel.loadProfile(uid, fallbackName) }

    val title = state.profile?.name?.takeIf { it.isNotBlank() }
        ?: fallbackName.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.profile_title)

    val context = LocalContext.current
    val actionsState = rememberThreadActionsState()
    val shareLabel = stringResource(R.string.profile_action_share)
    val followLabel = stringResource(
        if (state.profile?.isFollowing == true) R.string.profile_action_unfollow else R.string.profile_action_follow,
    )
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeActionMessage()
        }
    }
    val profileUrl = "https://bbs.binmt.cc/home.php?mod=space&uid=" + uid
    val profileActions = listOf(
        ThreadActionItem(shareLabel, Icons.Rounded.Share) {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                putExtra(android.content.Intent.EXTRA_TEXT, title + "\n" + profileUrl)
            }
            context.startActivity(android.content.Intent.createChooser(intent, shareLabel))
        },
        ThreadActionItem(followLabel, Icons.Rounded.PersonAdd) { viewModel.toggleFollow() },
    )

    Box(Modifier.fillMaxSize()) {
    MiuixPageScaffold(
        title = title,
        chrome = chrome,
        outerPadding = outerPadding,
        horizontalPadding = gutter,
        verticalPadding = 12.dp,
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        actions = { ThreadActions(items = profileActions, state = actionsState) },
    ) {
        val profile = state.profile
        when {
            state.isLoading -> item { ForumLoadingCard() }

            state.loginRequired -> item {
                ProfileLoginCard(onLogin = { navigator.push(Route.Login) })
            }

            state.error != null -> item {
                ForumErrorCard(message = state.error.orEmpty(), onRetry = viewModel::refresh)
            }

            profile == null || profile.name.isBlank() -> item {
                ForumEmptyCard(text = stringResource(R.string.profile_empty))
            }

            else -> {
                item { ProfileHeaderCard(profile) }
                item { ProfileStatsCard(profile) }
                if (profile.creditStats.isNotEmpty()) {
                    item { SmallTitle(stringResource(R.string.profile_credits_section)) }
                    item { ProfileCreditCard(profile.creditStats) }
                }
                if (profile.signature != null) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                        ) {
                            BasicComponent(
                                title = stringResource(R.string.profile_signature_label),
                                summary = profile.signature,
                            )
                        }
                    }
                }
                if (profile.fields.isNotEmpty()) {
                    item { SmallTitle(stringResource(R.string.profile_section_info)) }
                    item { ProfileFieldsCard(profile.fields) }
                }
                if (profile.recentThreads.isNotEmpty()) {
                    item { SmallTitle(stringResource(R.string.profile_recent_threads)) }
                    items(items = profile.recentThreads, key = { "profile_thread_${it.tid}" }) { thread ->
                        ForumThreadCard(
                            thread = thread,
                            onClick = { navigator.push(Route.ForumThread(thread.tid, thread.title)) },
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
    ThreadActionsGlassMenu(items = profileActions, state = actionsState, backdrop = chrome.backdrop)
    }
}

/** Avatar, nickname, level and the header counters (人气 / 关注 / 粉丝). */
@Composable
private fun ProfileHeaderCard(profile: ForumUserProfile) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ForumAvatar(url = profile.avatarUrl, name = profile.name, size = 64.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (profile.isFollowing == true) {
                        Spacer(Modifier.width(8.dp))
                        ForumBadge(text = stringResource(R.string.profile_following_badge))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    profile.levelText?.let { level ->
                        Text(
                            text = level,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    profile.levelTitle?.let { levelTitle ->
                        Text(
                            text = levelTitle,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                val summary = buildList {
                    add(stringResource(R.string.account_uid_format, profile.uid))
                    profile.credits?.let { add(stringResource(R.string.profile_credits_format, it)) }
                    profile.popularity?.let { value ->
                        add("${stringResource(R.string.profile_stat_popularity)} $value")
                    }
                }
                Text(
                    text = summary.joinToString(" · "),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 主题 / 回帖 / 关注 / 粉丝 / 好友 counters, three per row, skipping what the site omits. */
@Composable
private fun ProfileStatsCard(profile: ForumUserProfile) {
    val cells = buildList {
        profile.threadCount?.let { add(ProfileStatCell(stringResource(R.string.profile_stat_threads), it)) }
        profile.replyCount?.let { add(ProfileStatCell(stringResource(R.string.profile_stat_replies), it)) }
        profile.followingCount?.let { add(ProfileStatCell(stringResource(R.string.profile_stat_following), it)) }
        profile.followerCount?.let { add(ProfileStatCell(stringResource(R.string.profile_stat_followers), it)) }
        profile.friendCount?.let { add(ProfileStatCell(stringResource(R.string.profile_stat_friends), it)) }
        profile.popularity?.let { add(ProfileStatCell(stringResource(R.string.profile_stat_popularity), it)) }
    }
    if (cells.isEmpty()) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            cells.chunked(3).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { cell ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = cell.value.toString(),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = cell.label,
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The site's credit block (积分 / 好评 / 金币 / 信誉). */
@Composable
private fun ProfileCreditCard(stats: List<ForumProfileStat>) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column {
            stats.forEach { stat ->
                BasicComponent(
                    title = stat.label,
                    endActions = {
                        Text(text = stat.value, fontSize = 14.sp)
                    },
                )
            }
        }
    }
}

/** The profile's detail rows (用户ID / 生日 / 性别 / 在线时间 / 注册时间 / …) in site order. */
@Composable
private fun ProfileFieldsCard(fields: List<ForumProfileField>) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column {
            fields.forEach { field ->
                BasicComponent(
                    title = field.label,
                    endActions = {
                        Text(
                            text = field.value.ifBlank { "—" },
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    },
                )
            }
        }
    }
}

/** One label/counter pair of the stats card. */
private data class ProfileStatCell(val label: String, val value: Int)

/** Login gate: the site serves space profiles to members only. */
@Composable
private fun ProfileLoginCard(onLogin: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.profile_login_required_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.profile_login_required_desc),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onLogin) {
                Text(text = stringResource(R.string.profile_login_action))
            }
        }
    }
}
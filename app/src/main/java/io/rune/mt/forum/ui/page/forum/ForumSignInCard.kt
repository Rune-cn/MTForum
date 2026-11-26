// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.ForumSignInState
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.navigation.Route
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 每日签到 card on the account/settings surface.
 *
 * The site's 签到 page is a single screen of three numbers and one button, so it is rendered as a
 * card next to the account block instead of a dedicated page: the numbers are the whole feature,
 * and a page would add a navigation hop for one tap. Everything visible comes from
 * [ForumSignInViewModel] (one page fetch), and the sign-in GET's CDATA answer is shown verbatim in
 * the enclosing page's [SnackbarHostState] — the site's own wording is the only honest one.
 *
 * Guests (and an expired cookie) get a sign-in prompt instead of the numbers: the page is
 * member-only and the repository reports that as a login requirement, never as "no data".
 *
 * @param snackbarHostState Host owned by the settings page; the server's result text is shown there.
 */
@Composable
fun ForumSignInCard(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: ForumSignInViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val guest = !session.isLoggedIn || state.loginRequired

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    // Signing in elsewhere (the login page) must let the card fetch its numbers.
    LaunchedEffect(session.isLoggedIn) {
        if (session.isLoggedIn) viewModel.loadIfNeeded()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (AppTheme.useMiuixOs4) 16.dp else 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (guest) {
                GuestRow(onLogin = { navigator.push(Route.Login) })
            } else {
                HeaderRow(
                    alreadySigned = state.alreadySignedToday,
                    level = state.signIn.signInLevel,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    state.isLoading && !state.loaded -> LoadingRow()
                    state.error != null && !state.loaded -> ErrorRow(
                        message = state.error.orEmpty(),
                        onRetry = viewModel::refresh,
                    )

                    else -> {
                        StatsRow(signIn = state.signIn)
                        state.error?.let { error ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = error,
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        ActionRow(
                            alreadySigned = state.alreadySignedToday,
                            isSigningIn = state.isSigningIn,
                            canRefresh = state.loaded,
                            onSignIn = viewModel::signIn,
                            onRefresh = viewModel::refresh,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Status line plus 签到等级.
 *
 * The card sits under the page's 「每日签到」section title, so it does not repeat the title: this
 * row answers the one question the section title cannot — is today done.
 */
@Composable
private fun HeaderRow(alreadySigned: Boolean, level: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                if (alreadySigned) R.string.sign_in_status_done else R.string.sign_in_status_pending,
            ),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (alreadySigned) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            modifier = Modifier.weight(1f),
        )
        level?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.sign_in_level_format, it),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * The site's three 签到 counters.
 *
 * A rank of 0 is the template's placeholder before the day's sign-in (`今日排名0`), so it is shown
 * as a dash instead of a meaningless "0"; the streak and total are real numbers either way.
 */
@Composable
private fun StatsRow(signIn: ForumSignInState) {
    Row(modifier = Modifier.fillMaxWidth()) {
        SignInStat(
            value = signIn.todayRank?.takeIf { it > 0 }?.toString() ?: "—",
            label = stringResource(R.string.sign_in_stat_rank),
            modifier = Modifier.weight(1f),
        )
        SignInStat(
            value = signIn.streakDays?.let { stringResource(R.string.sign_in_days_format, it) } ?: "—",
            label = stringResource(R.string.sign_in_stat_streak),
            modifier = Modifier.weight(1f),
        )
        SignInStat(
            value = signIn.totalDays?.let { stringResource(R.string.sign_in_days_format, it) } ?: "—",
            label = stringResource(R.string.sign_in_stat_total),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SignInStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * The action row: 签到 while the day is open, a disabled 今日已签到 afterwards, plus a manual
 * refresh (the numbers also change when another client signs in on the same account).
 */
@Composable
private fun ActionRow(
    alreadySigned: Boolean,
    isSigningIn: Boolean,
    canRefresh: Boolean,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onSignIn,
            enabled = !alreadySigned && !isSigningIn,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = if (alreadySigned) {
                    stringResource(R.string.sign_in_status_done)
                } else {
                    stringResource(R.string.sign_in_action)
                },
            )
        }
        if (canRefresh) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = stringResource(R.string.sign_in_refresh),
                onClick = onRefresh,
                enabled = !isSigningIn,
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.forum_loading),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.sign_in_failed),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(text = stringResource(R.string.sign_in_refresh), onClick = onRetry)
    }
}

/** Guest state: the page is member-only, so the card asks for the login route (rule 18). */
@Composable
private fun GuestRow(onLogin: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.sign_in_login_required),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Button(onClick = onLogin) {
            Text(text = stringResource(R.string.profile_login_action))
        }
    }
}

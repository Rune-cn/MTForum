// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Placeholder shown while a forum screen performs its first load. */
@Composable
internal fun ForumLoadingCard(modifier: Modifier = Modifier, text: String = stringResource(R.string.forum_loading)) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** Failure card with a tap-to-retry affordance. */
@Composable
internal fun ForumErrorCard(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), onClick = onRetry) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.forum_load_failed),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.forum_tap_to_retry),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** Shown when the server returned a valid but empty page. */
@Composable
internal fun ForumEmptyCard(text: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
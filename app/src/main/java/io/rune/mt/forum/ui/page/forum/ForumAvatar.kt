// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The single avatar used everywhere in the forum UI.
 *
 * Card rows and post floors previously had two near-identical implementations that disagreed:
 * the card one treated any `noavatar` URL as "no avatar" and drew a letter, while the post one
 * loaded the site's placeholder image. That is why the same author looked broken in a list and
 * correct inside the thread. Now both go through this composable: it always attempts the real
 * image and only falls back to a letter when the URL is blank or Coil actually fails.
 */
@Composable
fun ForumAvatar(
    url: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    var failed by remember(url) { mutableStateOf(false) }
    val showFallback = url.isNullOrBlank() || failed

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (showFallback) {
            Text(
                text = name.trim().firstOrNull()?.toString() ?: "?",
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = name,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        }
    }
}

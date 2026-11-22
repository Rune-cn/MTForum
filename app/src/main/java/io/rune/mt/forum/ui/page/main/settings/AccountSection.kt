// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixConfirmDialog
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val avatarClient by lazy { OkHttpClient() }

/**
 * Account block shared by the settings page.
 *
 * Signed out it advertises the login entry point; signed in it shows the avatar, nickname,
 * level and uid and offers logout. Styling follows the ambient Miuix presentation, so the
 * same composable works under both OS3 and OS4 without a style flag.
 */
@Composable
fun AccountSection(
    session: ForumSession,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val useMiuixOs4 = AppTheme.useMiuixOs4
    var confirmLogout by remember { mutableStateOf(false) }
    val user = session.user
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (useMiuixOs4) 16.dp else 12.dp)
            .padding(bottom = 12.dp),
    ) {
        if (!session.isLoggedIn || user == null) {
            SignedOutRow(onLogin = onLogin)
        } else {
            SignedInRow(user = user, onLogout = { confirmLogout = true })
        }
    }

    // Signing out discards the saved session, so it must not fire on a single stray tap.
    MiuixConfirmDialog(
        visible = confirmLogout,
        title = stringResource(R.string.logout_confirm_title),
        message = stringResource(R.string.logout_confirm_message),
        confirmText = stringResource(R.string.logout_confirm_action),
        dismissText = stringResource(R.string.logout_cancel),
        onConfirm = {
            confirmLogout = false
            onLogout()
        },
        onDismiss = { confirmLogout = false },
    )
}

@Composable
private fun SignedOutRow(onLogin: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(avatarUrl = null, displayName = "")
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.account_not_signed_in),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.account_not_signed_in_desc),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Button(onClick = onLogin) {
            Text(text = stringResource(R.string.account_sign_in_now))
        }
    }
}

@Composable
private fun SignedInRow(user: ForumUser, onLogout: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(avatarUrl = user.avatarUrl, displayName = user.name)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.account_level_format,
                        user.level?.trim()?.removePrefix("Lv.")?.removePrefix("lv.")?.takeIf { it.isNotBlank() }
                            ?: "—",
                    ),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.account_uid_format, user.uid),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        TextButton(
            text = stringResource(R.string.account_sign_out),
            onClick = onLogout,
        )
    }
}

@Composable
private fun AccountAvatar(avatarUrl: String?, displayName: String) {
    val useMiuixOs4 = AppTheme.useMiuixOs4
    val avatarSize = if (useMiuixOs4) 52.dp else 48.dp
    val bitmap by produceState<ImageBitmap?>(initialValue = null, avatarUrl) {
        value = if (avatarUrl.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(avatarUrl)
                        .header("User-Agent", ForumHttpClient.MOBILE_UA)
                        .build()
                    avatarClient.newCall(request).execute().use { response ->
                        response.body?.bytes()?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }
                    }
                }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(avatarSize)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = displayName.trim().firstOrNull()?.toString() ?: "?",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

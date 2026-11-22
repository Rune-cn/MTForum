// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.R
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Read-only browsing guard.
 *
 * Reply / post entry points should call this dialog when
 * [io.rune.mt.forum.domain.forum.model.ForumSession.isLoggedIn] is false instead of attempting
 * the write request. The caller owns navigation so this composable stays free of routing policy.
 */
@Composable
fun LoginRequiredDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onLogin: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.login_required_title),
        summary = stringResource(R.string.login_required_message),
        onDismissRequest = onDismissRequest,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.login_required_action),
                onClick = onLogin,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
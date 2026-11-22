// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBlockingLoadingDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import io.rune.mt.forum.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Login screen: Discuz username/password only.
 *
 * The cookie-paste entry point was removed on request; the credential flow is the one path that
 * is verified end to end against the live site (see docs/REVERSE_SPEC.md §8).
 */
@Composable
fun MiuixLoginPage(
    enableBlur: Boolean = true,
    viewModel: LoginViewModel = koinViewModel(),
) {
    val navigator = LocalNavigator.current
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val useMiuixOs4 = AppTheme.useMiuixOs4
    val chrome = rememberMiuixPageChrome(enableBlur)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                LoginEvent.Success -> navigator.pop()
            }
        }
    }

    MiuixPageScaffold(
        title = stringResource(R.string.login_title),
        chrome = chrome,
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        horizontalPadding = 12.dp,
        verticalPadding = 12.dp,
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.login_account_desc),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextField(
                        value = uiState.username,
                        onValueChange = viewModel::updateUsername,
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.login_username_label),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = uiState.password,
                        onValueChange = viewModel::updatePassword,
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.login_password_label),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { viewModel.submitPassword() }),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LoginErrorText(error = uiState.error, serverMessage = uiState.serverMessage)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = viewModel::submitPassword,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSubmitting,
                    ) {
                        Text(text = stringResource(R.string.login_password_button))
                    }
                }
            }
        }
    }

    MiuixBlockingLoadingDialog(
        visible = uiState.isSubmitting,
        text = stringResource(R.string.login_in_progress),
    )
}

@Composable
private fun LoginErrorText(error: LoginError?, serverMessage: String? = null) {
    if (error == null) return
    Text(
        // The server's own wording wins: it reports remaining login attempts on a bad password.
        text = serverMessage ?: stringResource(error.messageRes),
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
    )
}

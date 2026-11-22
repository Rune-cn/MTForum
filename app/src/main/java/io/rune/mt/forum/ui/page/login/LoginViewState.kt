// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.login

import androidx.annotation.StringRes
import io.rune.mt.forum.R

enum class LoginError(@StringRes val messageRes: Int) {
    EMPTY_CREDENTIALS(R.string.login_error_empty_credentials),
    CREDENTIALS_REJECTED(R.string.login_error_credentials_rejected),
    NETWORK(R.string.login_error_network),
}

data class LoginViewState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: LoginError? = null,
    /**
     * The server's own rejection text ("登录失败，您还可以尝试 4 次"). Shown verbatim because it
     * carries information the enum cannot: how many attempts are left before a lockout.
     */
    val serverMessage: String? = null,
)

sealed interface LoginEvent {
    data object Success : LoginEvent
}

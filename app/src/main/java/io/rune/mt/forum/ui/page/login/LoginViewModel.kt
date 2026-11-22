// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.data.forum.repository.ForumPasswordAuthenticator
import io.rune.mt.forum.data.forum.repository.ForumSessionStore
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Drives the credential login form.
 *
 * A password login only returns cookies; the session is confirmed separately by
 * [ForumRepository.restoreSession], because a 200 from the site is not proof of success.
 */
class LoginViewModel(
    private val forumRepository: ForumRepository,
    private val sessionStore: ForumSessionStore,
    private val passwordAuthenticator: ForumPasswordAuthenticator,
) : ViewModel() {

    private companion object {
        const val TAG = "LoginViewModel"
    }

    private val _state = MutableStateFlow(LoginViewState())
    val state: StateFlow<LoginViewState> = _state.asStateFlow()

    val session = forumRepository.session

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun updateUsername(value: String) {
        _state.update { it.copy(username = value, error = null, serverMessage = null) }
    }

    fun updatePassword(value: String) {
        _state.update { it.copy(password = value, error = null, serverMessage = null) }
    }

    fun submitPassword() {
        val current = _state.value
        val username = current.username.trim()
        val password = current.password
        if (username.isEmpty() || password.isEmpty()) {
            _state.update { it.copy(error = LoginError.EMPTY_CREDENTIALS) }
            return
        }
        if (current.isSubmitting) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null, serverMessage = null) }
            val outcome = try {
                passwordAuthenticator.authenticate(username, password)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).i("password login failed: %s", error.javaClass.simpleName)
                ForumPasswordAuthenticator.Outcome.Failure("登录请求失败，请检查网络")
            }

            val cookies = (outcome as? ForumPasswordAuthenticator.Outcome.Success)?.cookies
            if (cookies == null) {
                val reason = (outcome as? ForumPasswordAuthenticator.Outcome.Failure)?.reason
                Timber.tag(TAG).i("password login rejected by server")
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = LoginError.CREDENTIALS_REJECTED,
                        serverMessage = reason,
                    )
                }
                return@launch
            }

            // Cookies alone are not proof: the site hands them out before the session is usable,
            // so confirm by reading the profile back.
            val success = try {
                forumRepository.restoreSession(cookies)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).i("password session restore failed: %s", error.javaClass.simpleName)
                false
            }

            if (success) {
                sessionStore.save(forumRepository.session.value.cookies)
                Timber.tag(TAG).i(
                    "password login succeeded: uid=%s",
                    forumRepository.session.value.user?.uid,
                )
                _state.update { it.copy(isSubmitting = false, password = "") }
                _events.send(LoginEvent.Success)
            } else {
                // Credentials were accepted but the session could not be confirmed. Reporting
                // "wrong password" here would be a lie; surface the real reason.
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = LoginError.CREDENTIALS_REJECTED,
                        serverMessage = "服务器已接受凭据，但会话校验失败，请稍后重试",
                    )
                }
            }
        }
    }
}

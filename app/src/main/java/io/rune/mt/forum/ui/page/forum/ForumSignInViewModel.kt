// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import io.rune.mt.forum.domain.forum.model.ForumSignInState
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State of the 每日签到 card.
 *
 * [signIn] holds the numbers the site printed, including the `formhash` the sign-in GET needs;
 * [loginRequired] is the guest / expired-cookie case and drives the login card instead of the
 * numbers. [message] is the server's own CDATA wording after an attempt, kept until the screen has
 * shown it, so the user reads `签到成功` / `已签到` rather than a client-side rewording.
 */
data class ForumSignInUiState(
    val isLoading: Boolean = true,
    val isSigningIn: Boolean = false,
    val signIn: ForumSignInState = ForumSignInState(),
    val loginRequired: Boolean = false,
    val error: String? = null,
    /** One-shot snackbar text: the server's message for the last attempt. */
    val message: String? = null,
    /** True when [message] reports a rejection, so the screen can style it as an error. */
    val messageIsError: Boolean = false,
    val loaded: Boolean = false,
) {
    val alreadySignedToday: Boolean get() = signIn.alreadySignedToday
}

/**
 * Backs the 每日签到 card: one page read plus the optional sign-in call.
 *
 * Signing in is deliberately two requests: the plugin's GET only answers with a result string, so
 * the new 今日排名 / 连续签到 / 累计签到 numbers have to be re-read from the page (the site itself
 * reloads the page after signing). The refreshed numbers replace the old ones, and the outcome
 * text travels to the screen verbatim.
 */
class ForumSignInViewModel(
    private val repository: ForumRepository,
) : ViewModel() {

    val session = repository.session

    private val _state = MutableStateFlow(ForumSignInUiState())
    val state: StateFlow<ForumSignInUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var signJob: Job? = null

    /** Reads the page once per process, unless a sign-in or a manual refresh already re-read it. */
    fun loadIfNeeded() {
        if (_state.value.loaded || loadJob?.isActive == true) return
        load(refresh = false)
    }

    fun refresh() {
        if (_state.value.isLoading && !_state.value.loaded) return
        load(refresh = true)
    }

    /**
     * Signs in with the page's own `formhash`, then re-reads the page for the refreshed counters.
     *
     * A null hash means the page was never parsed (still loading, or the session already gone);
     * asking the page again is the honest recovery instead of sending a request that cannot work.
     */
    fun signIn() {
        val current = _state.value
        if (current.isSigningIn) return
        val formhash = current.signIn.formhash
        if (formhash.isNullOrBlank()) {
            load(refresh = true)
            return
        }
        signJob = viewModelScope.launch {
            _state.update { it.copy(isSigningIn = true, error = null, message = null, messageIsError = false) }
            try {
                when (val outcome = repository.signIn(formhash)) {
                    is ForumSignInOutcome.Success -> {
                        // The site reloads the page after a successful sign-in; do the same so
                        // 排名/连续/累计 are the post-sign-in numbers, not the pre-sign-in ones.
                        _state.update {
                            it.copy(
                                isSigningIn = false,
                                signIn = it.signIn.copy(
                                    alreadySignedToday = true,
                                    resultMessage = outcome.message,
                                ),
                                message = outcome.message,
                                messageIsError = false,
                            )
                        }
                        Timber.tag(TAG).i("sign-in ok: %s", outcome.message)
                        refreshCounters(forceSigned = true)
                    }

                    is ForumSignInOutcome.AlreadySigned -> {
                        _state.update {
                            it.copy(
                                isSigningIn = false,
                                signIn = it.signIn.copy(
                                    alreadySignedToday = true,
                                    resultMessage = outcome.message,
                                ),
                                message = outcome.message,
                                messageIsError = false,
                            )
                        }
                        Timber.tag(TAG).i("sign-in already done: %s", outcome.message)
                        refreshCounters(forceSigned = true)
                    }

                    is ForumSignInOutcome.Failure -> {
                        _state.update {
                            it.copy(
                                isSigningIn = false,
                                error = outcome.message,
                                message = outcome.message,
                                messageIsError = true,
                            )
                        }
                        Timber.tag(TAG).w("sign-in rejected: %s", outcome.message)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                _state.update { it.copy(isSigningIn = false, loginRequired = true, loaded = true) }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "sign-in failed")
                _state.update {
                    it.copy(
                        isSigningIn = false,
                        error = error.message ?: error.javaClass.simpleName,
                        message = error.message ?: error.javaClass.simpleName,
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /** Clears the one-shot snackbar text once the screen has shown it. */
    fun clearMessage() {
        _state.update { it.copy(message = null, messageIsError = false) }
    }

    private fun load(refresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(isLoading = !refresh || !it.loaded, error = null, loginRequired = false)
            }
            try {
                val page = repository.getSignInState()
                _state.update {
                    it.copy(isLoading = false, signIn = page, loaded = true, error = null)
                }
                Timber.tag(TAG).i(
                    "sign-in state: signed=%s rank=%s streak=%s total=%s",
                    page.alreadySignedToday,
                    page.todayRank,
                    page.streakDays,
                    page.totalDays,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                _state.update { it.copy(isLoading = false, loginRequired = true, loaded = true) }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "sign-in state failed")
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: error.javaClass.simpleName,
                        loaded = true,
                    )
                }
            }
        }
    }

    /**
     * Re-reads the page after a successful sign-in. The counters are the reason for the request,
     * so a failing refresh must not undo the signed flag or hide the server's message.
     */
    private suspend fun refreshCounters(forceSigned: Boolean) {
        val page = runCatching { repository.getSignInState() }.getOrNull() ?: return
        _state.update { current ->
            current.copy(
                signIn = page.copy(
                    alreadySignedToday = page.alreadySignedToday || forceSigned,
                    resultMessage = current.signIn.resultMessage,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "ForumSignInVM"
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
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

/** State of one user's space profile screen. */
data class ForumProfileState(
    val uid: Int = 0,
    /** Nickname carried by the route, so the bar has a title before the page resolves. */
    val fallbackName: String = "",
    val isLoading: Boolean = true,
    val profile: ForumUserProfile? = null,
    val error: String? = null,
    /** True when the site demanded a login instead of serving the profile. */
    val loginRequired: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * Backs the space profile (个人主页).
 *
 * One call loads the profile page and (best-effort) the member's recent thread listing, so the
 * screen renders header + counters + threads from a single state. A login bounce is reported as
 * [ForumProfileState.loginRequired] instead of a generic error, because retrying without a
 * session would fail the same way.
 */
class ForumProfileViewModel(
    private val repository: ForumRepository,
) : ViewModel() {

    val session = repository.session

    private val _state = MutableStateFlow(ForumProfileState())
    val state: StateFlow<ForumProfileState> = _state.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private var job: Job? = null

    /** Loads [uid] once per target; re-entering the same profile keeps the loaded data. */
    fun loadProfile(uid: Int, fallbackName: String) {
        if (uid <= 0) {
            _state.value = ForumProfileState(
                uid = uid,
                fallbackName = fallbackName,
                isLoading = false,
                error = "缺少用户 ID",
                loaded = true,
            )
            return
        }
        val current = _state.value
        if (current.uid == uid && current.loaded && current.profile != null) {
            if (fallbackName.isNotBlank() && current.fallbackName != fallbackName) {
                _state.update { it.copy(fallbackName = fallbackName) }
            }
            return
        }
        if (current.uid != uid) {
            _state.value = ForumProfileState(uid = uid, fallbackName = fallbackName)
        }
        load(uid, refresh = false)
    }

    /** Consumes the one-shot toast emitted by [toggleFollow]. */
    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    /**
     * Follows or unfollows the profile owner. The follow POST needs the session formhash printed on
     * this very page, so it is taken from the loaded profile instead of a second request.
     */
    fun toggleFollow() {
        val profile = _state.value.profile ?: return
        if (session.value.user == null) {
            _actionMessage.value = FOLLOW_NEED_LOGIN
            return
        }
        val formhash = profile.formhash
        if (formhash.isNullOrBlank()) {
            _actionMessage.value = FOLLOW_FAILED
            return
        }
        val following = profile.isFollowing == true
        viewModelScope.launch {
            runCatching {
                if (following) repository.unfollowUser(profile.uid, formhash) else repository.followUser(profile.uid, formhash)
            }.onSuccess { ok ->
                if (!ok) {
                    _actionMessage.value = FOLLOW_FAILED
                } else {
                    _actionMessage.value = if (following) FOLLOW_UNFOLLOWED else FOLLOW_DONE
                    refresh()
                }
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "follow uid=%d failed", profile.uid)
                _actionMessage.value = FOLLOW_FAILED
            }
        }
    }

    fun refresh() {
        val current = _state.value
        if (current.uid <= 0) return
        load(current.uid, refresh = true)
    }

    private fun load(uid: Int, refresh: Boolean) {
        if (refresh && _state.value.isLoading) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update {
                it.copy(
                    uid = uid,
                    isLoading = !refresh || !it.loaded,
                    error = null,
                    loginRequired = false,
                )
            }
            try {
                val profile = repository.getUserProfileDetail(uid)
                _state.update {
                    it.copy(
                        isLoading = false,
                        profile = profile,
                        loginRequired = false,
                        loaded = true,
                    )
                }
                Timber.tag(TAG).i(
                    "profile ok uid=%d threads=%d fields=%d",
                    uid,
                    profile.recentThreads.size,
                    profile.fields.size,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (login: ForumLoginRequiredException) {
                Timber.tag(TAG).i("profile uid=%d login required", uid)
                _state.update {
                    it.copy(isLoading = false, loginRequired = true, loaded = true)
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "profile uid=%d failed", uid)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "ForumProfileVM"

        // Follow feedback is a one-shot toast, not screen state; the wording mirrors the thread
        // page's action messages (which are also plain strings, not resources).
        const val FOLLOW_NEED_LOGIN = "请先登录"
        const val FOLLOW_FAILED = "操作失败，请稍后重试"
        const val FOLLOW_DONE = "已关注"
        const val FOLLOW_UNFOLLOWED = "已取消关注"
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.data.forum.repository.ForumSessionStore
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.launch
import timber.log.Timber

/** Owns the account row state and the persisted-session side of logout. */
class AccountViewModel(
    private val forumRepository: ForumRepository,
    private val forumSessionStore: ForumSessionStore,
) : ViewModel() {

    val session = forumRepository.session

    fun logout() {
        viewModelScope.launch {
            forumRepository.logout()
            forumSessionStore.clear()
            Timber.tag(TAG).i(
                "forum logout: isLoggedIn=%s uid=%s",
                forumRepository.session.value.isLoggedIn,
                forumRepository.session.value.user?.uid,
            )
        }
    }

    private companion object {
        const val TAG = "ForumAccount"
    }
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumPrivateMessage
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** State of the private-message conversation list (the 消息 tab). */
data class MessagesListState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val conversations: List<ForumPrivateConversation> = emptyList(),
    val error: String? = null,
    val loaded: Boolean = false,
)

/** State of one open conversation. */
data class ConversationState(
    val uid: Int = 0,
    val name: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val messages: List<ForumPrivateMessage> = emptyList(),
    val formhash: String? = null,
    val pmid: String? = null,
    val error: String? = null,
    val isSending: Boolean = false,
    val sendError: String? = null,
)

/**
 * Backs the private-message tab and a single conversation.
 *
 * Two screens share this VM: the tab lists conversations ([MessagesListState]); tapping one pushes
 * the conversation route which calls [openConversation]. Keeping both here means the list does not
 * refetch when returning from a conversation.
 */
class ForumMessagesViewModel(
    private val repository: ForumRepository,
) : ViewModel() {

    val session = repository.session

    private val _listState = MutableStateFlow(MessagesListState())
    val listState: StateFlow<MessagesListState> = _listState.asStateFlow()

    private val _conversation = MutableStateFlow(ConversationState())
    val conversation: StateFlow<ConversationState> = _conversation.asStateFlow()

    private var listJob: Job? = null
    private var conversationJob: Job? = null

    fun loadConversationsIfNeeded() {
        if (_listState.value.loaded) return
        loadConversations(refresh = false)
    }

    fun refreshConversations() = loadConversations(refresh = true)

    private fun loadConversations(refresh: Boolean) {
        if (!refresh && listJob?.isActive == true) return
        if (refresh && _listState.value.isRefreshing) return
        listJob = viewModelScope.launch {
            _listState.update {
                it.copy(isLoading = !refresh && !it.loaded, isRefreshing = refresh, error = null)
            }
            try {
                val items = repository.getConversations()
                _listState.value = MessagesListState(
                    isLoading = false,
                    isRefreshing = false,
                    conversations = items,
                    loaded = true,
                )
                Timber.tag(TAG).i("conversations ok count=%d", items.size)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "conversations failed")
                _listState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun openConversation(uid: Int, name: String) {
        val current = _conversation.value
        if (current.uid != uid) {
            _conversation.value = ConversationState(uid = uid, name = name)
        } else if (name.isNotBlank() && current.name.isBlank()) {
            _conversation.update { it.copy(name = name) }
        }
        loadConversation(uid, refresh = false)
    }

    fun refreshConversation() {
        val state = _conversation.value
        if (state.uid == 0) return
        loadConversation(state.uid, refresh = true)
    }

    private fun loadConversation(uid: Int, refresh: Boolean) {
        if (refresh && _conversation.value.isRefreshing) return
        conversationJob?.cancel()
        conversationJob = viewModelScope.launch {
            _conversation.update {
                it.copy(
                    uid = uid,
                    isLoading = !refresh && it.messages.isEmpty(),
                    isRefreshing = refresh,
                    error = null,
                    sendError = null,
                )
            }
            try {
                val detail: ForumConversationDetail = repository.getConversation(uid)
                _conversation.update {
                    it.copy(
                        uid = uid,
                        name = it.name.ifBlank { detail.withName },
                        isLoading = false,
                        isRefreshing = false,
                        messages = detail.messages,
                        formhash = detail.formhash ?: it.formhash,
                        pmid = detail.pmid,
                    )
                }
                Timber.tag(TAG).i("conversation uid=%d messages=%d", uid, detail.messages.size)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "conversation uid=%d failed", uid)
                _conversation.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val state = _conversation.value
        if (text.isBlank() || state.isSending) return
        if (session.value.user == null) {
            _conversation.update { it.copy(sendError = "请先登录") }
            return
        }
        val formhash = state.formhash ?: run {
            _conversation.update { it.copy(sendError = "会话尚未加载完成") }
            return
        }
        viewModelScope.launch {
            _conversation.update { it.copy(isSending = true, sendError = null) }
            runCatching { repository.sendPrivateMessage(state.uid, text.trim(), formhash, state.pmid) }
                .onSuccess { ok ->
                    if (ok) {
                        _conversation.update { it.copy(isSending = false, sendError = null) }
                        loadConversation(state.uid, refresh = true)
                    } else {
                        _conversation.update { it.copy(isSending = false, sendError = "发送失败") }
                    }
                }
                .onFailure { e ->
                    _conversation.update { it.copy(isSending = false, sendError = e.message ?: "发送失败") }
                }
        }
    }

    fun clearSendError() {
        _conversation.update { it.copy(sendError = null) }
    }

    private companion object {
        const val TAG = "ForumMessagesVM"
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumPrivateMessage
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import io.rune.mt.forum.domain.forum.model.ImageHostResult
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
    /**
     * The outgoing message body — **BBCode**, exactly what the site's own box accepts.
     *
     * Its own tags (`[b]`, `[quote]`, `[img]`) are resolved **server-side** when the message is
     * displayed, so the client sends the markup untouched. The text lives here (not in the page)
     * because a programmatic insert — image-host `[img]`, a toolbar tag — must land at the caret and
     * hand the new caret back to the field.
     */
    val draft: String = "",
    /** Caret of [draft] as a UTF-16 index (the field's `selection.end`). */
    val draftCaret: Int = 0,
    val error: String? = null,
    val isSending: Boolean = false,
    val sendError: String? = null,
    /** True while a picked picture is being uploaded to the MT image host. */
    val isUploadingImage: Boolean = false,
    /** Last image-host failure, with its reason; the typed draft is never cleared by it. */
    val imageHostError: String? = null,
) {
    /** A message with at least one non-space character — the send button's own gate. */
    val canSend: Boolean get() = draft.isNotBlank() && !isSending
}

/**
 * Backs the private-message tab and a single conversation.
 *
 * Two screens share this VM: the tab lists conversations ([MessagesListState]); tapping one pushes
 * the conversation route which calls [openConversation]. Keeping both here means the list does not
 * refetch when returning from a conversation.
 *
 * ## The PM composer is a BBCode box (docs/REVERSE_SPEC.md §12.3, §24)
 *
 * The site's own private-message box accepts BBCode and renders it server-side, so the composer
 * offers a minimal tag toolbar (`[b]`/`[i]`/`[u]`/`[quote]`/`[url]`/`[img]`) and an image action
 * that uploads to the **MT image host** and inserts its `[img]…[/img]` at the caret. **Sending is
 * unchanged**: the same POST fields (`formhash`, `touid`, `message`) go to the same
 * `home.php?mod=spacecp&ac=pm&op=send…` endpoint — the tags are only text in `message`, resolved by
 * the site when the message is displayed.
 */
class ForumMessagesViewModel(
    private val repository: ForumRepository,
    /** The MT image host — a separate service from the forum (own cookie jar, own host). */
    private val imageHost: ImageHostGateway,
) : ViewModel() {

    /**
     * Image preparation seam: production uses [AndroidImagePreparer]; tests substitute a fake
     * because Robolectric's `BitmapFactory` cannot decode real bytes in this environment.
     */
    internal var imagePreparer: ImagePreparer = AndroidImagePreparer()

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

    /** Mirrors the message box's text and caret on every keystroke. */
    fun updateDraft(value: String, caret: Int) {
        _conversation.update {
            it.copy(
                draft = value,
                draftCaret = caret,
                sendError = null,
                imageHostError = null,
            )
        }
    }

    /** Inserts an empty BBCode pair at the caret, leaving the caret between the tags. */
    fun insertBbcTag(name: String) {
        val state = _conversation.value
        val edit = ComposerText.wrapTag(state.draft, state.draftCaret, name)
        _conversation.update {
            it.copy(draft = edit.text, draftCaret = edit.caret, imageHostError = null)
        }
    }

    /**
     * Uploads one picked picture to the MT image host and inserts its `[img]…[/img]` at the caret.
     *
     * The host is a **separate service** (`img.binmt.cc`): it keeps the original bytes, so a PM
     * picture is not re-compressed the way a forum attachment would be. A failure sets
     * [ConversationState.imageHostError] and leaves the draft exactly as typed.
     */
    fun uploadImageToHost(context: Context, uri: Uri) {
        if (_conversation.value.isUploadingImage) return
        viewModelScope.launch {
            _conversation.update { it.copy(isUploadingImage = true, imageHostError = null) }
            when (val prepared = imagePreparer.prepareForHost(context, uri)) {
                is ImagePrepResult.Rejected -> _conversation.update {
                    it.copy(isUploadingImage = false, imageHostError = rejectionReason(prepared))
                }

                is ImagePrepResult.Ready -> {
                    val image = prepared.image
                    when (val result = imageHost.upload(image.bytes, image.fileName, image.contentType)) {
                        is ImageHostResult.Success -> {
                            Timber.tag(TAG).i(
                                "pm image host ok host=%s bytes=%d",
                                result.url.substringBefore('/', result.url),
                                image.bytes.size,
                            )
                            val state = _conversation.value
                            val edit = ComposerText.insert(state.draft, state.draftCaret, result.bbcode)
                            _conversation.update {
                                it.copy(
                                    draft = edit.text,
                                    draftCaret = edit.caret,
                                    isUploadingImage = false,
                                    imageHostError = null,
                                )
                            }
                        }

                        is ImageHostResult.Failure -> {
                            Timber.tag(TAG).w("pm image host failed: %s", result.reason)
                            _conversation.update {
                                it.copy(isUploadingImage = false, imageHostError = result.reason)
                            }
                        }
                    }
                }
            }
        }
    }

    fun clearImageHostError() = _conversation.update { it.copy(imageHostError = null) }

    /** Sends [ConversationState.draft] exactly as it stands (BBCode included). */
    fun sendDraft() {
        val state = _conversation.value
        val text = state.draft
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
                        // Cleared only after the site accepted: a failed send keeps the text.
                        _conversation.update {
                            it.copy(
                                isSending = false,
                                sendError = null,
                                draft = "",
                                draftCaret = 0,
                                imageHostError = null,
                            )
                        }
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

    /** The reason line for a rejected pick (the screen renders it, never drops it silently). */
    private fun rejectionReason(rejected: ImagePrepResult.Rejected): String = when (rejected.reason) {
        ImageRejectReason.TooLarge -> "图片太大，无法上传"
        ImageRejectReason.UnsupportedFormat -> "图床只接受 jpg/png/gif/webp"
        ImageRejectReason.Unreadable -> "无法读取这张图片"
        ImageRejectReason.OverCap -> "图片数量已达上限"
    }

    fun clearSendError() {
        _conversation.update { it.copy(sendError = null) }
    }

    private companion object {
        const val TAG = "ForumMessagesVM"
    }
}

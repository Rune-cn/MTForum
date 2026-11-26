// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** One image picked for a new thread, kept as a URI until submit. */
data class PickedImage(
    val uri: Uri,
    val fileName: String,
    val byteCount: Long,
)

data class NewThreadState(
    val fid: Int = 0,
    val forumName: String = "",
    val title: String = "",
    val message: String = "",
    val images: List<PickedImage> = emptyList(),
    val form: ForumNewThreadForm? = null,
    val isLoadingForm: Boolean = true,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** Set once the server accepted the thread; the screen pops afterwards. */
    val successTid: Int? = null,
    val successMessage: String? = null,
) {
    val canSubmit: Boolean
        get() = title.isNotBlank() && message.isNotBlank() && !isSubmitting &&
            form?.formhash != null && form.needsCaptcha.not()
}

/**
 * Composes and submits a new thread (`forum.php?mod=post&action=newthread`).
 *
 * Discuz's mobile form is multipart and takes the pictures inline as `Filedata`, so there is no
 * separate upload round-trip. The hidden `formhash`/`posttime` must be fetched from the form page
 * first; a captcha in that form is surfaced instead of being guessed (we cannot solve it).
 */
class NewThreadViewModel(
    private val repository: ForumRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NewThreadState())
    val state: StateFlow<NewThreadState> = _state.asStateFlow()

    fun open(fid: Int, forumName: String) {
        if (_state.value.fid == fid && _state.value.form != null) return
        _state.value = NewThreadState(fid = fid, forumName = forumName)
        viewModelScope.launch {
            runCatching { repository.getNewThreadForm(fid) }
                .onSuccess { form ->
                    _state.update {
                        it.copy(
                            form = form,
                            isLoadingForm = false,
                            error = if (form.needsCaptcha) "该版块发帖需要验证码，客户端暂不支持" else null,
                        )
                    }
                }
                .onFailure { e ->
                    Timber.tag(TAG).e(e, "new thread form fid=%d failed", fid)
                    _state.update {
                        it.copy(isLoadingForm = false, error = e.message ?: "无法读取发帖表单")
                    }
                }
        }
    }

    fun updateTitle(value: String) = _state.update { it.copy(title = value, error = null) }

    fun updateMessage(value: String) = _state.update { it.copy(message = value, error = null) }

    /** Resolves a picked content URI into bytes so it can be attached to the multipart POST. */
    fun attachImages(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> readPicked(context, uri) }
            }
            _state.update { it.copy(images = (it.images + picked).take(MAX_IMAGES)) }
        }
    }

    fun removeImage(uri: Uri) = _state.update { it.copy(images = it.images.filterNot { i -> i.uri == uri }) }

    private fun readPicked(context: Context, uri: Uri): PickedImage? = runCatching {
        val resolver = context.contentResolver
        val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "image.jpg"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.size > MAX_BYTES) null
        else PickedImage(uri, name, bytes.size.toLong()).also { cache[uri] = bytes }
    }.getOrNull()

    /** Byte cache for picked images; small enough (≤1 MB each) to keep in memory until submit. */
    private val cache = mutableMapOf<Uri, ByteArray>()

    fun submit(context: Context) {
        val current = _state.value
        if (!current.canSubmit) return
        val form = current.form ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            val images = withContext(Dispatchers.IO) {
                current.images.mapNotNull { picked ->
                    val bytes = cache[picked.uri] ?: readPicked(context, picked.uri)?.let { cache[picked.uri] }
                    bytes?.let {
                        NewThreadImage(
                            fileName = picked.fileName,
                            contentType = guessContentType(picked.fileName),
                            bytes = it,
                        )
                    }
                }
            }
            when (val result = repository.createThread(
                fid = current.fid,
                subject = current.title.trim(),
                message = current.message.trim(),
                form = form,
                images = images,
            )) {
                is NewThreadResult.Success -> {
                    Timber.tag(TAG).i("new thread ok fid=%d tid=%s", current.fid, result.tid)
                    _state.update {
                        it.copy(isSubmitting = false, successTid = result.tid, successMessage = result.message)
                    }
                }

                is NewThreadResult.Failure -> {
                    Timber.tag(TAG).i("new thread failed: %s", result.reason)
                    _state.update { it.copy(isSubmitting = false, error = result.reason) }
                }
            }
        }
    }

    private fun guessContentType(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".webp", true) -> "image/webp"
        else -> "image/jpeg"
    }

    private companion object {
        const val TAG = "NewThreadVM"

        /** The site's own attachment box says "大小限制: 1MB". */
        const val MAX_BYTES = 1024 * 1024
        const val MAX_IMAGES = 5
    }
}

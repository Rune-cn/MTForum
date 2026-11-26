// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    /**
     * Local draft found for [fid] when the composer opened. While it is non-null the page shows the
     * 恢复草稿/丢弃 prompt, and auto-save refuses to overwrite it (restore or discard first).
     */
    val pendingDraft: ForumNewThreadDraft? = null,
    /** URIs of a restored draft waiting to be re-read off the main thread. */
    val restoreImageUris: List<String> = emptyList(),
    /** Restored draft images whose URI could no longer be opened (deleted/permission lost). */
    val skippedRestoredImages: Int = 0,
    /** Transient: a manual save just finished; the page toasts it and calls [NewThreadViewModel.consumeDraftSaved]. */
    val draftSaved: Boolean = false,
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
 *
 * **Drafts are client-local.** The site's own 保存草稿 button sets `save=1` on the same newthread
 * POST; measuring it published the thread instead of storing a draft, so nothing is sent for
 * "save draft". Instead the composer stores `fid`/board name/subject/body/image URIs in
 * [ForumDraftRepository], restores them behind an explicit prompt, and deletes the draft on a
 * successful publish. Auto-save happens when the page is left (back button or a predictive-back
 * dismissal), never when the composer is blank and never over an un-restored draft.
 */
class NewThreadViewModel(
    private val repository: ForumRepository,
    private val drafts: ForumDraftRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NewThreadState())
    val state: StateFlow<NewThreadState> = _state.asStateFlow()

    /** Signature of the last auto/manual save; keeps a back-button save + dispose save from writing twice. */
    private var lastSavedSignature: String? = null

    fun open(fid: Int, forumName: String) {
        if (_state.value.fid == fid && _state.value.form != null) return
        _state.value = NewThreadState(fid = fid, forumName = forumName)
        lastSavedSignature = null
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
        viewModelScope.launch {
            val draft = runCatching { drafts.newThreadDraft(fid) }.getOrNull()
            if (draft == null) return@launch
            _state.update { current ->
                // Only prompt while the composer is still untouched; a draft must never silently
                // replace something the user already typed.
                if (current.fid == fid && current.title.isBlank() && current.message.isBlank() &&
                    current.images.isEmpty() && current.pendingDraft == null
                ) {
                    current.copy(pendingDraft = draft)
                } else {
                    current
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
                uris.mapNotNull { uri ->
                    // Open-document URIs carry a persistable read grant; without it a draft
                    // restored after a restart would lose every image.
                    persistReadPermission(context, uri)
                    readPicked(context, uri)
                }
            }
            _state.update { it.copy(images = (it.images + picked).take(MAX_IMAGES)) }
        }
    }

    fun removeImage(uri: Uri) = _state.update { it.copy(images = it.images.filterNot { i -> i.uri == uri }) }

    // ---- Local drafts -------------------------------------------------------

    /** Fills the composer from the draft the restore prompt offered. */
    fun restoreDraft() {
        val draft = _state.value.pendingDraft ?: return
        _state.update {
            it.copy(
                title = draft.subject,
                message = draft.message,
                pendingDraft = null,
                restoreImageUris = draft.imageUris,
                skippedRestoredImages = 0,
                error = null,
            )
        }
    }

    /** Re-reads the image URIs of a restored draft; unreadable ones are counted as skipped. */
    fun resolveRestoredImages(context: Context) {
        val uris = _state.value.restoreImageUris
        if (uris.isEmpty()) return
        _state.update { it.copy(restoreImageUris = emptyList()) }
        viewModelScope.launch {
            var skipped = 0
            val restored = withContext(Dispatchers.IO) {
                uris.mapNotNull { raw ->
                    val uri = runCatching { Uri.parse(raw) }.getOrNull()
                    val picked = uri?.let {
                        persistReadPermission(context, it)
                        readPicked(context, it)
                    }
                    if (picked == null) skipped++
                    picked
                }
            }
            _state.update {
                it.copy(
                    images = (it.images + restored).take(MAX_IMAGES),
                    skippedRestoredImages = it.skippedRestoredImages + skipped,
                )
            }
        }
    }

    /** Explicit 保存草稿 action: never touches the network, only the local store. */
    fun saveDraft() = persistDraft(manual = true)

    /**
     * Saves on leaving the page (back button or composition disposal) when the composer holds
     * something worth keeping. It is a no-op over an un-restored draft so an "open then leave"
     * visit cannot overwrite an older save.
     */
    fun saveOnLeave() = persistDraft(manual = false)

    fun consumeDraftSaved() = _state.update { it.copy(draftSaved = false) }

    /** Hides the restore prompt without deleting the stored draft (accidental dismissal). */
    fun dismissRestorePrompt() = _state.update { it.copy(pendingDraft = null) }

    /** Deletes the stored draft and leaves the composer empty. */
    fun discardDraft() {
        val fid = _state.value.fid
        _state.update { it.copy(pendingDraft = null) }
        if (fid == 0) return
        viewModelScope.launch { runCatching { drafts.deleteNewThreadDraft(fid) } }
    }

    private fun persistDraft(manual: Boolean) {
        val current = _state.value
        if (current.fid == 0 || current.isSubmitting || current.successTid != null) return
        if (current.pendingDraft != null) return
        if (current.title.isBlank() && current.message.isBlank() && current.images.isEmpty()) return
        val signature = listOf(
            current.fid,
            current.title,
            current.message,
            current.images.joinToString("|") { it.uri.toString() },
        ).toString()
        if (signature == lastSavedSignature) {
            if (manual) _state.update { it.copy(draftSaved = true) }
            return
        }
        lastSavedSignature = signature
        val draft = ForumNewThreadDraft(
            fid = current.fid,
            forumName = current.forumName,
            subject = current.title,
            message = current.message,
            imageUris = current.images.map { it.uri.toString() },
        )
        // A save-on-leave must still land after the entry leaves the back stack and the
        // viewModelScope is cancelled, so this particular write is non-cancellable.
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            runCatching { drafts.saveNewThreadDraft(draft) }
                .onFailure { Timber.tag(TAG).e(it, "save draft fid=%d failed", draft.fid) }
            if (manual) _state.update { it.copy(draftSaved = true) }
        }
    }

    private fun persistReadPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

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
                    // The thread exists now: the local draft has served its purpose.
                    runCatching { drafts.deleteNewThreadDraft(current.fid) }
                        .onFailure { Timber.tag(TAG).e(it, "delete draft after publish fid=%d failed", current.fid) }
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
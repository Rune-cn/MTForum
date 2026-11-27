// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumDraft
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
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

/** Where the composer's content currently lives: a fresh thread, or a draft the site already has. */
sealed interface ComposerMode {
    /** A thread that does not exist yet; 保存草稿 creates the site's draft for it. */
    data object NewThread : ComposerMode

    /**
     * Editing a draft the site already stores ([tid], [fid], [pid] and the harvested [form]).
     *
     * In this mode 保存草稿 re-saves the *same* draft (`save=1` on the edit form) instead of
     * creating a second one, 发表 publishes it, and 删除 drops it.
     */
    data class DraftEdit(
        val tid: Int,
        val fid: Int,
        val pid: Long?,
        val form: ForumEditForm,
    ) : ComposerMode
}

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
    /** Current editor target; [ComposerMode.NewThread] until a site draft is opened or created. */
    val mode: ComposerMode = ComposerMode.NewThread,
    /**
     * Local fallback draft found for [fid] when the composer opened. While it is non-null the page
     * shows the 恢复草稿/丢弃 prompt, and auto-save refuses to overwrite it (restore or discard).
     */
    val pendingDraft: ForumNewThreadDraft? = null,
    /**
     * Site draft of the same board, offered instead of the local copy when both exist. Restoring it
     * puts the composer in [ComposerMode.DraftEdit] so the site draft — not a second one — is what
     * the next 保存草稿 updates.
     */
    val pendingServerDraft: ForumDraft? = null,
    /** True while a draft write is in flight (保存草稿 / 删除 / 发表 of a draft). */
    val isDraftBusy: Boolean = false,
    /** URIs of a restored draft waiting to be re-read off the main thread. */
    val restoreImageUris: List<String> = emptyList(),
    /** Restored draft images whose URI could no longer be opened (deleted/permission lost). */
    val skippedRestoredImages: Int = 0,
    /** Transient: a manual draft save just finished; the page toasts it and consumes the flag. */
    val draftSaved: Boolean = false,
    /** Set when the last draft write failed; kept so the text is never cleared on failure. */
    val draftError: String? = null,
    /** Set once the open draft was deleted on the site; the screen pops afterwards. */
    val draftDeleted: Boolean = false,
) {
    val isDraftEdit: Boolean get() = mode is ComposerMode.DraftEdit

    /** Tid of the open draft (0 when composing a brand-new thread). */
    val draftTid: Int get() = (mode as? ComposerMode.DraftEdit)?.tid ?: 0

    val canSubmit: Boolean
        get() = title.isNotBlank() && message.isNotBlank() && !isSubmitting && !isDraftBusy &&
            when (val current = mode) {
                is ComposerMode.DraftEdit -> current.form.formhash.isNotBlank()
                ComposerMode.NewThread ->
                    form?.formhash != null && form.needsCaptcha.not()
            }
}

/**
 * Composes and submits a new thread (`forum.php?mod=post&action=newthread`), and edits/ publishes/
 * deletes a **site-side draft** (`action=edit`).
 *
 * Discuz's mobile form is multipart and takes the pictures inline as `Filedata`, so there is no
 * separate upload round-trip. The hidden `formhash`/`posttime` must be fetched from the form page
 * first; a captcha in that form is surfaced instead of being guessed (we cannot solve it).
 *
 * **Drafts are stored by the site.** 保存草稿 POSTs the same form with the hidden `save` field set
 * to `1`, which creates a real (author-only) draft thread — the measured flow, see
 * docs/REVERSE_SPEC.md §16.1. It is *not* a publish: publishing needs `save` empty, and that only
 * happens through the page's explicit 发表 confirmation. The device-side
 * [ForumDraftRepository] is kept as an offline safety net: it is written when a server save fails
 * and when the page is left, so text the server never accepted is not lost with the process.
 */
class NewThreadViewModel(
    private val repository: ForumRepository,
    private val drafts: ForumDraftRepository,
    private val draftService: ForumDraftService,
) : ViewModel() {

    private val _state = MutableStateFlow(NewThreadState())
    val state: StateFlow<NewThreadState> = _state.asStateFlow()

    /** Signature of the last auto/manual local save; avoids a back-button + dispose double write. */
    private var lastSavedSignature: String? = null

    /**
     * Opens the composer for [fid].
     *
     * [draftTid]/[draftPid] open an existing site draft (the 草稿 list's row): the edit form is
     * fetched first, because every submit path replays its full field set.
     */
    fun open(fid: Int, forumName: String, draftTid: Int = 0, draftPid: Long? = null) {
        if (draftTid <= 0 && _state.value.fid == fid && _state.value.form != null &&
            !_state.value.isDraftEdit
        ) {
            return
        }
        _state.value = NewThreadState(fid = fid, forumName = forumName)
        lastSavedSignature = null
        if (draftTid > 0) {
            loadDraftForEdit(draftTid, fid, draftPid)
            return
        }
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
            offerDraft(fid)
        }
    }

    /**
     * Offers the draft that already exists for this board.
     *
     * The **site's** draft wins when both exist (it is the one that survives a device change, and
     * it is what this composer will keep updating); the device copy — written only when a site save
     * failed — is offered only when the site has none. Nothing is applied silently: the caller gets
     * a prompt and decides. Best-effort: a failed list leaves the composer blank.
     */
    private suspend fun offerDraft(fid: Int) {
        val serverDraft = runCatching { draftService.getDrafts() }.getOrNull()
            ?.drafts?.firstOrNull { it.fid == fid }
        val localDraft = if (serverDraft == null) {
            runCatching { drafts.newThreadDraft(fid) }.getOrNull()
        } else {
            null
        }
        if (serverDraft == null && localDraft == null) return
        _state.update { current ->
            // Only prompt while the composer is still untouched; a draft must never silently
            // replace something the user already typed.
            if (current.fid == fid && current.title.isBlank() && current.message.isBlank() &&
                current.images.isEmpty() && current.pendingDraft == null &&
                current.pendingServerDraft == null
            ) {
                current.copy(pendingDraft = localDraft, pendingServerDraft = serverDraft)
            } else {
                current
            }
        }
        if (serverDraft != null) {
            Timber.tag(TAG).i("site draft tid=%d offered over the device copy fid=%d", serverDraft.tid, fid)
        }
    }

    /** Fetches a site draft's edit form and fills the composer from it. */
    private fun loadDraftForEdit(tid: Int, fid: Int, pid: Long?) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingForm = true, error = null) }
            runCatching { draftService.loadDraft(tid, fid, pid) }
                .onSuccess { edit ->
                    _state.update {
                        it.copy(
                            fid = edit.fid.takeIf { value -> value > 0 } ?: fid,
                            title = edit.subject,
                            message = edit.message,
                            form = null,
                            isLoadingForm = false,
                            mode = ComposerMode.DraftEdit(
                                tid = edit.tid,
                                fid = edit.fid,
                                pid = edit.pid,
                                form = edit.form,
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    Timber.tag(TAG).e(e, "load draft tid=%d failed", tid)
                    _state.update {
                        it.copy(isLoadingForm = false, error = e.message ?: "无法打开草稿")
                    }
                }
        }
    }

    fun updateTitle(value: String) = _state.update { it.copy(title = value, error = null, draftError = null) }

    fun updateMessage(value: String) = _state.update { it.copy(message = value, error = null, draftError = null) }

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

    // ---- Drafts (site first, device as the safety net) -----------------------

    /**
     * Explicit 保存草稿 action.
     *
     * Writes to the **site** (`save=1`), which is the forum's own draft storage; a fresh draft is
     * created for a new thread, while an already-open draft is updated in place (`updateDraft`) so
     * repeated saves cannot pile up duplicate drafts. On failure the composer keeps every character
     * and the text is additionally written to the local store, so a failed save is never data loss.
     *
     * [context] is only used to re-read attached image bytes for the multipart upload.
     */
    fun saveDraft(context: Context) {
        val current = _state.value
        if (current.fid == 0 || current.isSubmitting || current.isDraftBusy) return
        if (current.title.isBlank() && current.message.isBlank() && current.images.isEmpty()) return
        val mode = current.mode
        if (mode is ComposerMode.DraftEdit) {
            updateSiteDraft(current, mode)
            return
        }
        val form = current.form
        if (form == null) {
            _state.update { it.copy(draftError = "发帖表单尚未加载，无法保存草稿") }
            persistLocalDraft(current)
            return
        }
        _state.update { it.copy(isDraftBusy = true, draftError = null) }
        viewModelScope.launch {
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
            val result = runCatching {
                draftService.saveNewThreadDraft(
                    fid = current.fid,
                    subject = current.title.trim(),
                    message = current.message.trim(),
                    form = form,
                    images = images,
                )
            }.getOrElse { DraftActionResult.Failure(it.message ?: "保存草稿失败") }
            handleDraftWrite(
                result = result,
                kind = "save-new",
                onSuccess = { success ->
                    // The server now holds the draft. When it told us the draft's tid, switch to
                    // editing that draft so the next 保存草稿 updates it instead of creating another.
                    if (success.tid != null) {
                        runCatching { draftService.loadDraft(success.tid, current.fid) }
                            .onSuccess { edit ->
                                _state.update {
                                    it.copy(
                                        mode = ComposerMode.DraftEdit(
                                            tid = edit.tid,
                                            fid = edit.fid,
                                            pid = edit.pid,
                                            form = edit.form,
                                        ),
                                    )
                                }
                            }
                            .onFailure { Timber.tag(TAG).w(it, "draft edit form unavailable tid=%d", success.tid) }
                    }
                    // The device copy has served its purpose.
                    runCatching { drafts.deleteNewThreadDraft(current.fid) }
                },
                onFailure = { persistLocalDraft(current) },
            )
        }
    }

    /** Saves the open site draft in place (the edit page's 保存草稿, `save=1`). */
    private fun updateSiteDraft(current: NewThreadState, mode: ComposerMode.DraftEdit) {
        _state.update { it.copy(isDraftBusy = true, draftError = null) }
        viewModelScope.launch {
            val result = runCatching {
                draftService.updateDraft(mode.form, current.title.trim(), current.message.trim())
            }.getOrElse { DraftActionResult.Failure(it.message ?: "保存草稿失败") }
            handleDraftWrite(
                result = result,
                kind = "update",
                onSuccess = { runCatching { drafts.deleteNewThreadDraft(current.fid) } },
                onFailure = { persistLocalDraft(current) },
            )
        }
    }

    /**
     * Deletes the open site draft (the edit page's 删除, `delete=1`).
     *
     * Only ever called from the page's explicit confirmation; [NewThreadState.draftDeleted] pops
     * the screen on success.
     */
    fun deleteDraft() {
        val current = _state.value
        val mode = current.mode as? ComposerMode.DraftEdit ?: return
        if (current.isDraftBusy || current.isSubmitting) return
        _state.update { it.copy(isDraftBusy = true, draftError = null) }
        viewModelScope.launch {
            val result = runCatching { draftService.deleteDraft(mode.form) }
                .getOrElse { DraftActionResult.Failure(it.message ?: "删除草稿失败") }
            when (result) {
                is DraftActionResult.Success -> {
                    Timber.tag(TAG).i("draft deleted tid=%d", mode.tid)
                    runCatching { drafts.deleteNewThreadDraft(current.fid) }
                    _state.update { it.copy(isDraftBusy = false, draftDeleted = true) }
                }

                is DraftActionResult.Failure -> {
                    Timber.tag(TAG).w("draft delete tid=%d failed: %s", mode.tid, result.reason)
                    _state.update { it.copy(isDraftBusy = false, draftError = result.reason) }
                }
            }
        }
    }

    /**
     * Publishes the open site draft (the edit page's 发表, `save` empty).
     *
     * The page only calls this from its explicit confirmation dialog; publishing is never a side
     * effect of leaving the composer.
     */
    fun publishDraft() {
        val current = _state.value
        val mode = current.mode as? ComposerMode.DraftEdit ?: return
        if (current.isSubmitting || current.isDraftBusy) return
        if (current.title.isBlank() || current.message.isBlank()) return
        _state.update { it.copy(isSubmitting = true, error = null, draftError = null) }
        viewModelScope.launch {
            // The edit form's own subject/message are what got harvested; overwrite them with the
            // composer's current text so the published thread is what the user sees.
            val form = withContext(Dispatchers.IO) { mode.form }
            val fields = form.copy(
                fields = form.fields + mapOf("subject" to current.title.trim(), "message" to current.message.trim()),
            )
            val result = runCatching { draftService.publishDraft(fields) }
                .getOrElse { DraftActionResult.Failure(it.message ?: "发布失败") }
            when (result) {
                is DraftActionResult.Success -> {
                    Timber.tag(TAG).i("draft published tid=%s", result.tid ?: mode.tid)
                    runCatching { drafts.deleteNewThreadDraft(current.fid) }
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            successTid = result.tid ?: mode.tid,
                            successMessage = result.message,
                        )
                    }
                }

                is DraftActionResult.Failure -> {
                    Timber.tag(TAG).w("draft publish tid=%d failed: %s", mode.tid, result.reason)
                    // The text stays exactly where it is; only the error line changes.
                    _state.update { it.copy(isSubmitting = false, error = result.reason) }
                }
            }
        }
    }

    /**
     * Applies the outcome of a draft write.
     *
     * [onSuccess] may suspend (it loads the draft's edit form after a first save), so it is called
     * inside the caller's coroutine instead of being launched separately.
     */
    private suspend fun handleDraftWrite(
        result: DraftActionResult,
        kind: String,
        onSuccess: suspend (DraftActionResult.Success) -> Unit,
        onFailure: () -> Unit,
    ) {
        when (result) {
            is DraftActionResult.Success -> {
                Timber.tag(TAG).i("draft %s ok tid=%s", kind, result.tid)
                _state.update { it.copy(isDraftBusy = false, draftSaved = true, draftError = null) }
                onSuccess(result)
            }

            is DraftActionResult.Failure -> {
                Timber.tag(TAG).w("draft %s failed: %s", kind, result.reason)
                // Never clear the composer on a failed save: the text is the only copy left.
                onFailure()
                _state.update {
                    it.copy(isDraftBusy = false, draftError = result.reason, draftSaved = false)
                }
            }
        }
    }

    fun consumeDraftSaved() = _state.update { it.copy(draftSaved = false, draftError = null) }

    // ---- Local safety net ---------------------------------------------------

    /** Fills the composer from the device-side draft the restore prompt offered. */
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

    /**
     * Restores the site draft the prompt offered.
     *
     * Opening it switches the composer into [ComposerMode.DraftEdit], so subsequent saves update
     * that draft instead of creating another one and 发表/删除 act on it.
     */
    fun restoreServerDraft() {
        val server = _state.value.pendingServerDraft ?: return
        _state.update { it.copy(pendingServerDraft = null) }
        loadDraftForEdit(server.tid, server.fid, server.pid)
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

    /**
     * Saves on leaving the page (back button or composition disposal) when the composer holds
     * something worth keeping.
     *
     * **Never a network write**: leaving must not create or publish a draft silently, so this only
     * touches the device store. It is a no-op over an un-actioned prompt and after a publish/delete.
     */
    fun saveOnLeave() = persistLocalDraft(_state.value)

    /** Hides the restore prompt without deleting anything (accidental dismissal). */
    fun dismissRestorePrompt() =
        _state.update { it.copy(pendingDraft = null, pendingServerDraft = null) }

    /** Deletes the device-side draft and leaves the composer empty; a site draft is untouched. */
    fun discardDraft() {
        val fid = _state.value.fid
        _state.update { it.copy(pendingDraft = null, pendingServerDraft = null) }
        if (fid == 0) return
        viewModelScope.launch { runCatching { drafts.deleteNewThreadDraft(fid) } }
    }

    private fun persistLocalDraft(current: NewThreadState) {
        if (current.fid == 0 || current.isSubmitting || current.draftDeleted) return
        if (current.successTid != null) return
        if (current.pendingDraft != null || current.pendingServerDraft != null) return
        if (current.title.isBlank() && current.message.isBlank() && current.images.isEmpty()) return
        val signature = listOf(
            current.fid,
            current.title,
            current.message,
            current.images.joinToString("|") { it.uri.toString() },
        ).toString()
        if (signature == lastSavedSignature) return
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
                .onFailure { Timber.tag(TAG).e(it, "save local draft fid=%d failed", draft.fid) }
        }
    }

    // ---- Images / submit ----------------------------------------------------

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
        if (current.isDraftEdit) {
            // Editing a site draft: publishing is the confirmed action, not a normal submit.
            publishDraft()
            return
        }
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
                    // The thread exists now: the device copy has served its purpose. The site
                    // draft this composer may have created is published over by this POST, so it
                    // is removed as well.
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
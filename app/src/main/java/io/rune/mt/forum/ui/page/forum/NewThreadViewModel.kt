// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.Forum
import io.rune.mt.forum.domain.forum.model.ForumDraft
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import io.rune.mt.forum.domain.forum.model.ImageHostResult
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One image attached to a new thread.
 *
 * [contentType] and [downscaled] describe the bytes that will actually leave the device, not the
 * picked file: an oversized photo is re-encoded as JPEG before it reaches this point (see
 * [AndroidImagePreparer]). The bytes themselves live in the view model's cache until submit.
 */
data class PickedImage(
    val uri: Uri,
    val fileName: String,
    val byteCount: Long,
    val contentType: String,
    val downscaled: Boolean = false,
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
    /**
     * Where the caret sits in [message], as a UTF-16 index.
     *
     * The page mirrors its `TextFieldValue.selection.end` here and reads it back after a
     * programmatic edit (image-host upload, emoji, BBCode tag), so an inserted `[img]` lands where
     * the user was typing instead of at the bottom of the post.
     */
    val messageCaret: Int = 0,
    val images: List<PickedImage> = emptyList(),
    /**
     * Feedback of the last image pick (accepted files with their size, rejected ones with the
     * reason). Never empty after a pick: a tap that attaches nothing must still say why.
     */
    val imageFeedback: List<ImagePickFeedback> = emptyList(),
    /** True while one picked picture is being uploaded to the MT image host. */
    val isUploadingImage: Boolean = false,
    /**
     * Last image-host failure, with the reason. The message text is never touched by a failure —
     * the user's typing is the only copy of it.
     */
    val hostUploadError: String? = null,
    /**
     * Boards the composer may post into, read from the same index the home screen uses
     * ([ForumRepository.getHome], never a hard-coded list). Empty until the first load finishes.
     */
    val boards: List<Forum> = emptyList(),
    val isLoadingBoards: Boolean = false,
    val boardsError: String? = null,
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
                    // The formhash/posttime of the *selected* board must be in hand: switching
                    // boards drops the old form and re-fetches, and submitting meanwhile would
                    // post to the new fid with the old board's token (the site rejects it).
                    form?.formhash != null && form.needsCaptcha.not() && !isLoadingForm
            }
}

/**
 * Composes and submits a new thread (`forum.php?mod=post&action=newthread`), and edits/ publishes/
 * deletes a **site-side draft** (`action=edit`).
 *
 * Discuz's mobile form is multipart and takes the pictures inline as `Filedata`, so there is no
 * separate upload round-trip for the **attachment** path — images are prepared here
 * ([AndroidImagePreparer]) and ride along with the post. Every pick is reported to the user in
 * [NewThreadState.imageFeedback]; a rejection always carries its reason.
 *
 * ## Two image destinations (docs/REVERSE_SPEC.md §24)
 *
 * The forum's attachment path is not the only one: the MT image host (`https://img.binmt.cc/`,
 * announced in thread 98890) is a **separate service** whose purpose is publishing a picture
 * without the forum's 1 MB re-compression. Choosing it here ([uploadImageToHost]) uploads the
 * original bytes and inserts the host's own `[img]…[/img]` line **at the caret**, so the post
 * shows the full-resolution picture instead of an attachment thumbnail. The attachment path stays
 * exactly as it was; neither is silent — the page asks which one on every pick, and
 * `docs/HANDOVER_CN.md` §21 records that the image host is the default and why.
 *
 * The hidden `formhash`/`posttime` must be fetched from the form page first; they are **per board**,
 * which is why [selectBoard] re-fetches the form and why submitting is blocked while that fetch is
 * in flight. A captcha in that form is surfaced instead of being guessed (we cannot solve it).
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
    /** The MT image host (a separate service from the forum; see the class docs). */
    private val imageHost: ImageHostGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(NewThreadState())
    val state: StateFlow<NewThreadState> = _state.asStateFlow()

    /** Signature of the last auto/manual local save; avoids a back-button + dispose double write. */
    private var lastSavedSignature: String? = null

    /** In-flight board-index load, so re-opening the picker does not re-fetch it. */
    private var boardsJob: Job? = null

    /** In-flight new-thread form fetch; cancelled when the board changes again. */
    private var formJob: Job? = null

    /**
     * Image preparation seam: production uses [AndroidImagePreparer], tests substitute a fake
     * because Robolectric's `BitmapFactory` cannot decode real bytes in this environment.
     */
    internal var imagePreparer: ImagePreparer = AndroidImagePreparer()

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
        loadForm(fid)
        // The board list comes from the same index the home screen reads; it is what the
        // composer's board picker lists. Best-effort: a failure only affects the picker.
        loadBoards()
        viewModelScope.launch {
            offerDraft(fid)
        }
    }

    /**
     * Fetches the hidden `formhash`/`posttime` of [fid]'s form.
     *
     * The tokens are **per board**, so this runs again after a board change; the previous fetch is
     * cancelled so a slow response for the old board cannot overwrite the new one.
     */
    private fun loadForm(fid: Int) {
        formJob?.cancel()
        _state.update { it.copy(form = null, isLoadingForm = true, error = null) }
        formJob = viewModelScope.launch {
            runCatching { repository.getNewThreadForm(fid) }
                .onSuccess { form ->
                    // A response for a board the user already left is dropped.
                    _state.update { current ->
                        if (current.fid != fid) current else current.copy(
                            form = form,
                            isLoadingForm = false,
                            error = if (form.needsCaptcha) CAPTCHA_UNSUPPORTED else null,
                        )
                    }
                }
                .onFailure { e ->
                    Timber.tag(TAG).e(e, "new thread form fid=%d failed", fid)
                    _state.update { current ->
                        if (current.fid != fid) current else current.copy(
                            isLoadingForm = false,
                            error = e.message ?: "无法读取发帖表单",
                        )
                    }
                }
        }
    }

    /**
     * Loads the boards the composer can post into (the home index's board list).
     *
     * Called when the composer opens and by the picker's retry. A successful load is reused — the
     * site's board list does not change between two taps on the same screen.
     */
    fun loadBoards(force: Boolean = false) {
        val current = _state.value
        if (!force && (current.boards.isNotEmpty() || boardsJob?.isActive == true)) return
        boardsJob?.cancel()
        _state.update { it.copy(isLoadingBoards = true, boardsError = null) }
        boardsJob = viewModelScope.launch {
            runCatching { repository.getHome() }
                .onSuccess { home ->
                    Timber.tag(TAG).i("composer boards=%d", home.forums.size)
                    _state.update {
                        it.copy(boards = home.forums, isLoadingBoards = false, boardsError = null)
                    }
                }
                .onFailure { e ->
                    Timber.tag(TAG).w(e, "composer board list failed")
                    _state.update {
                        it.copy(
                            isLoadingBoards = false,
                            boardsError = e.message ?: "无法加载板块列表",
                        )
                    }
                }
        }
    }

    /**
     * Switches the board the new thread will be posted into.
     *
     * The composer stays a *new thread* composer: text and attached images are kept, but the form
     * is re-fetched for [fid] because `formhash`/`posttime` are per-board, and the submit URL uses
     * the new fid. A **draft** is bound to its board (its edit form carries `fid`/`tid`/`pid`), so
     * this is a no-op in [ComposerMode.DraftEdit] — the page also disables the picker there rather
     * than letting the tap do nothing silently.
     */
    fun selectBoard(fid: Int, forumName: String) {
        val current = _state.value
        if (fid <= 0 || fid == current.fid) return
        if (current.isDraftEdit || current.isSubmitting || current.isDraftBusy) return
        _state.update {
            it.copy(
                fid = fid,
                forumName = forumName,
                // The old board's form must never be submitted under the new fid.
                form = null,
                isLoadingForm = true,
                error = null,
            )
        }
        Timber.tag(TAG).i("composer board -> fid=%d (%s)", fid, forumName)
        loadForm(fid)
        // The local safety net is keyed by fid, so the text follows the new board.
        persistLocalDraft(_state.value)
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
                            messageCaret = edit.message.length,
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

    /** Mirrors the whole field; the caret goes to the end, which is where a plain edit leaves it. */
    fun updateMessage(value: String) = updateMessage(value, value.length)

    /**
     * Mirrors the field **and** the caret. The page passes `TextFieldValue.selection.end` (or the
     * selection's end, so a selection insert lands at its right edge) on every keystroke.
     */
    fun updateMessage(value: String, caret: Int) = _state.update {
        it.copy(message = value, messageCaret = caret, error = null, draftError = null, hostUploadError = null)
    }

    /** Inserts [snippet] at the caret (image-host `[img]`, an emoji code, …). */
    fun insertText(snippet: String) = insertEdit(ComposerText.insert(_state.value.message, _state.value.messageCaret, snippet))

    /** Inserts an empty BBCode pair at the caret, leaving the caret between the tags. */
    fun insertTag(name: String) = insertEdit(ComposerText.wrapTag(_state.value.message, _state.value.messageCaret, name))

    private fun insertEdit(edit: ComposerEdit) = _state.update {
        it.copy(message = edit.text, messageCaret = edit.caret, hostUploadError = null)
    }

    /**
     * Prepares picked content URIs for the multipart `Filedata` upload.
     *
     * Every pick produces feedback: an accepted file is listed with its name and the size that
     * will be uploaded, and a file that could not be used is listed with the reason (too large,
     * unreadable, over the cap). The composer previously dropped files over 1 MB with no message,
     * which is what made 添加图片 look broken; [AndroidImagePreparer] now re-encodes them first.
     */
    fun attachImages(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val current = _state.value
            var slots = (ComposerImagePolicy.MAX_IMAGES - current.images.size).coerceAtLeast(0)
            val added = mutableListOf<PickedImage>()
            val feedback = mutableListOf<ImagePickFeedback>()
            val seen = mutableSetOf<Uri>()
            for (uri in uris) {
                if (!seen.add(uri)) continue
                when (val prepared = imagePreparer.prepare(context, uri)) {
                    is ImagePrepResult.Rejected -> feedback += ImagePickFeedback(
                        fileName = prepared.fileName,
                        byteCount = prepared.byteCount,
                        reason = prepared.reason,
                    )

                    is ImagePrepResult.Ready -> {
                        if (slots <= 0) {
                            // Still read the file so the message can name it; the bytes are dropped.
                            feedback += ImagePickFeedback(
                                fileName = prepared.image.fileName,
                                byteCount = prepared.image.bytes.size.toLong(),
                                reason = ImageRejectReason.OverCap,
                            )
                        } else {
                            slots--
                            cache[prepared.image.uri] = prepared.image.bytes
                            added += PickedImage(
                                uri = prepared.image.uri,
                                fileName = prepared.image.fileName,
                                byteCount = prepared.image.bytes.size.toLong(),
                                contentType = prepared.image.contentType,
                                downscaled = prepared.image.downscaled,
                            )
                            feedback += ImagePickFeedback(
                                fileName = prepared.image.fileName,
                                byteCount = prepared.image.bytes.size.toLong(),
                                downscaled = prepared.image.downscaled,
                            )
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    images = (it.images + added).take(ComposerImagePolicy.MAX_IMAGES),
                    imageFeedback = feedback,
                )
            }
        }
    }

    fun removeImage(uri: Uri) = _state.update {
        it.copy(images = it.images.filterNot { image -> image.uri == uri })
    }

    // ---- MT image host (separate service; docs/REVERSE_SPEC.md §24) ----------

    /**
     * Uploads one picked picture to the MT image host and inserts its `[img]…[/img]` line **at the
     * caret**.
     *
     * This is the "clear picture" path: the host keeps the original bytes, while the forum's
     * attachment path re-compresses anything over 1 MB. The upload never touches the forum session
     * (the host has its own cookie jar), and it runs entirely off the main thread
     * ([ForumImageHostClient.upload] switches to `Dispatchers.IO`).
     *
     * On failure the message is left **exactly** as the user typed it ([NewThreadState.hostUploadError]
     * carries the reason) — losing typed text on a failed upload is the bug this must not have.
     */
    fun uploadImageToHost(context: Context, uri: Uri) {
        if (_state.value.isUploadingImage) return
        viewModelScope.launch {
            _state.update { it.copy(isUploadingImage = true, hostUploadError = null) }
            when (val prepared = imagePreparer.prepareForHost(context, uri)) {
                is ImagePrepResult.Rejected -> _state.update {
                    it.copy(
                        isUploadingImage = false,
                        imageFeedback = listOf(
                            ImagePickFeedback(
                                fileName = prepared.fileName,
                                byteCount = prepared.byteCount,
                                reason = prepared.reason,
                            ),
                        ),
                    )
                }

                is ImagePrepResult.Ready -> {
                    val image = prepared.image
                    when (val result = imageHost.upload(image.bytes, image.fileName, image.contentType)) {
                        is ImageHostResult.Success -> {
                            Timber.tag(TAG).i(
                                "image host ok bytes=%d name=%s host=%s",
                                image.bytes.size,
                                image.fileName,
                                result.url.substringBefore('/', result.url),
                            )
                            val edit = ComposerText.insert(
                                _state.value.message,
                                _state.value.messageCaret,
                                result.bbcode,
                            )
                            _state.update {
                                it.copy(
                                    message = edit.text,
                                    messageCaret = edit.caret,
                                    isUploadingImage = false,
                                    hostUploadError = null,
                                    imageFeedback = listOf(
                                        ImagePickFeedback(
                                            fileName = image.fileName,
                                            byteCount = image.bytes.size.toLong(),
                                            hostUrl = result.url,
                                        ),
                                    ),
                                )
                            }
                        }

                        is ImageHostResult.Failure -> {
                            Timber.tag(TAG).w("image host failed: %s", result.reason)
                            _state.update {
                                it.copy(isUploadingImage = false, hostUploadError = result.reason)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Clears the last image-host failure once the user has read it. */
    fun clearHostUploadError() = _state.update { it.copy(hostUploadError = null) }

    /** Clears the last pick's feedback lines once the user has read them. */
    fun clearImageFeedback() = _state.update { it.copy(imageFeedback = emptyList()) }

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
            val images = uploadImages(context, current.images)
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
                messageCaret = draft.message.length,
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

    /**
     * Re-reads the image URIs of a restored device draft.
     *
     * Uses the same preparation pipeline as a fresh pick: a permission that died between sessions
     * shows up as a rejection with a reason instead of the image quietly disappearing, and an
     * oversized file is re-encoded like any other.
     */
    fun resolveRestoredImages(context: Context) {
        val uris = _state.value.restoreImageUris
        if (uris.isEmpty()) return
        _state.update { it.copy(restoreImageUris = emptyList()) }
        viewModelScope.launch {
            var skipped = 0
            val feedback = mutableListOf<ImagePickFeedback>()
            val restored = mutableListOf<PickedImage>()
            var slots = (ComposerImagePolicy.MAX_IMAGES - _state.value.images.size).coerceAtLeast(0)
            for (raw in uris) {
                val uri = runCatching { Uri.parse(raw) }.getOrNull()
                if (uri == null) {
                    skipped++
                    feedback += ImagePickFeedback(fileName = raw, reason = ImageRejectReason.Unreadable)
                    continue
                }
                when (val prepared = imagePreparer.prepare(context, uri)) {
                    is ImagePrepResult.Rejected -> {
                        skipped++
                        feedback += ImagePickFeedback(
                            fileName = prepared.fileName,
                            byteCount = prepared.byteCount,
                            reason = prepared.reason,
                        )
                    }

                    is ImagePrepResult.Ready -> {
                        if (slots <= 0) {
                            skipped++
                            feedback += ImagePickFeedback(
                                fileName = prepared.image.fileName,
                                byteCount = prepared.image.bytes.size.toLong(),
                                reason = ImageRejectReason.OverCap,
                            )
                        } else {
                            slots--
                            cache[prepared.image.uri] = prepared.image.bytes
                            restored += PickedImage(
                                uri = prepared.image.uri,
                                fileName = prepared.image.fileName,
                                byteCount = prepared.image.bytes.size.toLong(),
                                contentType = prepared.image.contentType,
                                downscaled = prepared.image.downscaled,
                            )
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    images = (it.images + restored).take(ComposerImagePolicy.MAX_IMAGES),
                    skippedRestoredImages = it.skippedRestoredImages + skipped,
                    imageFeedback = feedback,
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

    /**
     * Maps the attached strip to the multipart parts of the create request.
     *
     * The bytes are the prepared ones from the pick (the cache exists so a submit does not have to
     * re-read and re-encode every image); if a cache entry is gone the file is prepared again from
     * its URI. A file that can no longer be read is reported in [NewThreadState.imageFeedback]
     * instead of vanishing from the upload.
     */
    private suspend fun uploadImages(context: Context, picked: List<PickedImage>): List<NewThreadImage> {
        if (picked.isEmpty()) return emptyList()
        val dropped = mutableListOf<ImagePickFeedback>()
        val images = withContext(Dispatchers.IO) {
            picked.mapNotNull { image ->
                val bytes = cache[image.uri] ?: when (val again = imagePreparer.prepare(context, image.uri)) {
                    is ImagePrepResult.Ready -> again.image.bytes.also { cache[image.uri] = it }
                    is ImagePrepResult.Rejected -> {
                        dropped += ImagePickFeedback(
                            fileName = again.fileName,
                            byteCount = again.byteCount,
                            reason = again.reason,
                        )
                        null
                    }
                }
                bytes?.let {
                    NewThreadImage(
                        fileName = image.fileName,
                        contentType = image.contentType,
                        bytes = it,
                    )
                }
            }
        }
        if (dropped.isNotEmpty()) {
            _state.update { it.copy(imageFeedback = dropped) }
            Timber.tag(TAG).w("upload dropped %d unreadable image(s)", dropped.size)
        }
        return images
    }

    /** Prepared bytes of the attached images, kept in memory (≤5 × 1 MB) until submit. */
    private val cache = ConcurrentHashMap<Uri, ByteArray>()

    fun submit(context: Context) {
        val current = _state.value
        if (current.isDraftEdit) {
            // Editing a site draft: publishing is the confirmed action, not a normal submit.
            publishDraft()
            return
        }
        if (!current.canSubmit) return
        // `canSubmit` already refuses this; the second check keeps a stale click out.
        if (current.isLoadingForm) return
        val form = current.form ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            val images = uploadImages(context, current.images)
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

    private companion object {
        const val TAG = "NewThreadVM"

        /** Shown when the board's form carries a captcha; the client cannot solve it. */
        const val CAPTCHA_UNSUPPORTED = "该版块发帖需要验证码，客户端暂不支持"
    }
}
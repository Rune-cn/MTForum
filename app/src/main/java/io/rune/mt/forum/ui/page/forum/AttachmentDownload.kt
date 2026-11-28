// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Stable
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.rune.mt.forum.data.forum.network.ForumAttachmentDownloader
import io.rune.mt.forum.data.forum.network.isCancellation
import io.rune.mt.forum.data.forum.network.sanitiseAttachmentFileName
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * One attachment download in flight, as the UI needs to see it.
 *
 * [progress] is `null` while the size is unknown (no `Content-Length`, so the bar is indeterminate)
 * and reaches 1f on success. [error] is set only on a real failure — a cancellation clears the
 * state instead, because a user-initiated cancel is not an error.
 */
internal data class AttachmentDownloadState(
    val attachment: ForumBodyBlock.Attachment,
    val progress: Float? = null,
    val error: String? = null,
) {
    val isRunning: Boolean get() = error == null
}

/**
 * Download state for the thread page: one download at a time, cancellable, never on the main thread.
 *
 * The downloader is resolved from the shared authenticated HTTP stack, so the request carries the
 * session cookies and the acw solver (a plain browser download of the same URL gets 403 or the JS
 * challenge). The destination is the app's cache directory: no storage permission is needed on any
 * supported API level, and the cache sweeper owns cleanup. A copy is offered through the system
 * share sheet so the user can move the file wherever they want it.
 */
@Stable
internal class AttachmentDownloadController(
    private val downloader: ForumAttachmentDownloader,
    private val cacheDir: File,
    private val scope: CoroutineScope,
) {
    /** Current download, or null when idle. */
    var state by mutableStateOf<AttachmentDownloadState?>(null)
        private set

    private var job: Job? = null

    /** True when [download] is already running, so the choice sheet hides the download row. */
    val isDownloading: Boolean get() = state != null && state?.isRunning == true

    /** Starts downloading [attachment], cancelling any download already in flight. */
    fun download(attachment: ForumBodyBlock.Attachment) {
        job?.cancel()
        state = AttachmentDownloadState(attachment)
        job = scope.launch {
            val name = sanitiseAttachmentFileName(attachment.name)
            val target = File(cacheDir, name)
            try {
                downloader.download(attachment.url, target) { fraction ->
                    state = state?.copy(progress = fraction)
                }
                state = state?.copy(progress = 1f)
            } catch (error: Throwable) {
                if (error.isCancellation()) {
                    // Cancelled by the user: drop the row instead of flashing an error.
                    state = null
                    return@launch
                }
                Timber.tag("AttachmentDownload").w("download failed: %s", attachment.url)
                state = state?.copy(error = error.message ?: error::class.java.simpleName)
                target.delete()
            }
        }
    }

    /** Cancels the running download; a no-op when idle. */
    fun cancel() {
        job?.cancel()
        job = null
        state = null
    }

    /** Clears a finished (or failed) row so the affordance returns to idle. */
    fun dismiss() {
        job?.cancel()
        job = null
        state = null
    }

    /** The finished file, when a download has completed. */
    fun completedFile(): File? {
        val current = state ?: return null
        if (!current.isRunning || current.progress != 1f) return null
        return File(cacheDir, sanitiseAttachmentFileName(current.attachment.name))
    }
}

/** Where downloads land. Cache keeps it permission-free; the share sheet is the exit path. */
internal fun attachmentCacheDir(context: Context): File =
    File(context.cacheDir, "attachments").apply { mkdirs() }

/**
 * Hands an `http(s)` URL to whatever activity can view it.
 *
 * Guarded because a device may have no VIEW handler for http(s) at all (and some minimal ROMs
 * ship without a browser); an attachment action must never be able to kill the app.
 */
internal fun openInBrowser(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { error ->
        Timber.tag("AttachmentDownload").w(error, "no activity to open %s", url)
    }
}

/**
 * Hands an already-downloaded attachment to the system share sheet.
 *
 * The cache file is **not** directly shareable: `ACTION_SEND` with a `file://` URI throws
 * `FileUriExposedException` on every API >= 24, and the old implementation wrapped the whole thing
 * in `runCatching`, so the exception vanished and the button looked dead ("下载附件后点保存/分享
 * 没反应"). Sharing now goes through the app's [FileProvider] (`content://`), with
 * `FLAG_GRANT_READ_URI_PERMISSION`, and returns the failure instead of swallowing it.
 *
 * @return null on success, or the throwable the caller should surface to the user.
 */
internal fun shareFile(
    context: Context,
    file: File,
    name: String,
    title: String,
): Throwable? {
    if (!file.exists()) return IllegalStateException("missing file: ${file.name}")
    return runCatching {
        val uri = attachmentContentUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = guessMimeType(name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { error ->
        Timber.tag("AttachmentDownload").w(error, "share failed for %s", name)
    }.exceptionOrNull()
}

/**
 * The `content://` URI a share/view receiver can actually read.
 *
 * [FileProvider] is declared in the manifest with `@xml/file_paths`; the attachments live under
 * `cacheDir/attachments`, which is exactly the `attachments` cache root the provider exposes.
 */
internal fun attachmentContentUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

/**
 * Copies a downloaded attachment to a place the user can find on their own, and reports where.
 *
 * On API 29+ this goes through `MediaStore.Downloads`, so the file appears in the Downloads folder
 * of every file manager without any storage permission. Below 29 writing to the public Downloads
 * folder needs `WRITE_EXTERNAL_STORAGE`, which this app does not request, so the file is written to
 * the app-external directory instead and the returned label names that path.
 */
internal sealed interface AttachmentSaveResult {
    data class Saved(val label: String) : AttachmentSaveResult
    data class Failed(val reason: String) : AttachmentSaveResult
}

internal fun saveAttachmentToDownloads(context: Context, file: File, name: String): AttachmentSaveResult {
    if (!file.exists()) return AttachmentSaveResult.Failed("missing file: ${file.name}")
    return runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, guessMimeType(name))
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/MTForum",
                )
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return AttachmentSaveResult.Failed("Downloads rejected the file")
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: return AttachmentSaveResult.Failed("cannot open the Downloads stream")
            AttachmentSaveResult.Saved("Download/MTForum/$name")
        } else {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return AttachmentSaveResult.Failed("external storage unavailable")
            val dir = File(base, "MTForum").apply { mkdirs() }
            val target = File(dir, name)
            file.copyTo(target, overwrite = true)
            AttachmentSaveResult.Saved(target.absolutePath)
        }
    }.getOrElse { error ->
        Timber.tag("AttachmentDownload").w(error, "save failed for %s", name)
        AttachmentSaveResult.Failed(error.message ?: error::class.java.simpleName)
    }
}

private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    "txt", "md" -> "text/plain"
    else -> "application/octet-stream"
}

/**
 * True when the app can write somewhere the user will find the file without a share step.
 *
 * Only used for the (optional) public Downloads hand-off: on API 29+ scoped storage makes a direct
 * write unsafe without MediaStore, so the cache + share path is always available and this flag is
 * informational only. Kept as a function so the decision is testable and documented in one place.
 */
internal fun hasLegacyDownloadsAccess(): Boolean =
    Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED &&
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
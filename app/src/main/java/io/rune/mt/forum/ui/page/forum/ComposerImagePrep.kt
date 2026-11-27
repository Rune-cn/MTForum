// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Why a picked file did not become an attachment.
 *
 * The composer must never drop a pick silently — "添加图片没有用" was exactly one of these reasons
 * (`TooLarge`) being swallowed — so every rejected file carries one of these values and the screen
 * renders it with the file's name.
 */
enum class ImageRejectReason {
    /** Larger than the site's 1 MB per-image cap and re-encoding could not bring it under it. */
    TooLarge,

    /** The content URI could not be opened (permission lost / file deleted) or is not an image. */
    Unreadable,

    /** The composer already holds [ComposerImagePolicy.MAX_IMAGES] images. */
    OverCap,
}

/**
 * One line of feedback for the last image pick.
 *
 * [accepted] rows report the file name and the byte count that will actually be uploaded;
 * rejected rows report the reason. The screen keeps them until the next pick, so a single tap
 * that only half-worked (two photos added, one rejected) still explains itself.
 */
data class ImagePickFeedback(
    val fileName: String,
    val byteCount: Long = 0,
    val reason: ImageRejectReason? = null,
    /** True when the bytes were re-encoded to fit the site's cap (see [ComposerImagePolicy]). */
    val downscaled: Boolean = false,
) {
    val accepted: Boolean get() = reason == null
}

/**
 * A picked image that is ready for the multipart `Filedata` part.
 *
 * [fileName] is the picked file's own name (kept verbatim, only its extension follows the bytes
 * when it had to be re-encoded), and [contentType] is sniffed from the bytes — never guessed from
 * the name, which was wrong for camera files such as `IMG_20260101_120000` (no extension).
 */
class PreparedImage(
    val uri: Uri,
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
    val downscaled: Boolean,
)

/** Outcome of preparing one picked URI for upload. */
sealed interface ImagePrepResult {
    class Ready(val image: PreparedImage) : ImagePrepResult

    class Rejected(
        val fileName: String,
        val byteCount: Long,
        val reason: ImageRejectReason,
    ) : ImagePrepResult
}

/**
 * Reads a picked content URI and makes it uploadable.
 *
 * An interface (with [AndroidImagePreparer] as the only production implementation) because the
 * decode/re-encode half needs a real `BitmapFactory`, which Robolectric's shadows cannot provide
 * in this environment — the view-model tests substitute a fake here and the pure decisions live in
 * [ComposerImagePolicy], where they are asserted directly.
 */
interface ImagePreparer {
    suspend fun prepare(context: Context, uri: Uri): ImagePrepResult
}

/**
 * The production image pipeline: pick → persistable permission → read → cap → re-encode → upload.
 *
 * ## Why this exists
 *
 * The old composer read the picked file and **dropped it if it was over 1 MB** (`return null`),
 * which is nearly every photo taken by a phone (2–5 MB). Nothing was shown, so the button looked
 * dead. The site's own attachment box says 大小限制 1MB, and the mobile form uploads images inline
 * as multipart `Filedata` parts — there is no separate image-host API (see
 * docs/REVERSE_SPEC.md §16). So the fix is to bring the bytes under the cap rather than drop them.
 *
 * ## Size / scale policy (also documented in docs/HANDOVER_CN.md §16.7)
 *
 * - 1 MB per image (the site's own limit), at most 5 images — unchanged.
 * - Files already ≤ 1 MB are uploaded byte-for-byte (name and MIME preserved).
 * - Larger files are re-decoded at a power-of-two sample that brings the longest side down to
 *   ~1920 px, flattened onto white (JPEG has no alpha; a transparent PNG would otherwise turn
 *   black) and re-encoded as JPEG using a bounded ladder of quality/scale attempts
 *   ([ComposerImagePolicy.downscaleAttempts]). The first attempt that fits 1 MB wins.
 * - The re-encoded copy keeps the original **base name** with a `.jpg` suffix, because its bytes
 *   are JPEG now; the MIME type is always sniffed from the bytes.
 * - Still too large after every attempt → rejected with [ImageRejectReason.TooLarge] and shown.
 * - Source files are read with a [ComposerImagePolicy.MAX_SOURCE_BYTES] ceiling so a pathological
 *   file cannot OOM the composer; a file that exceeds it is rejected as too large.
 */
class AndroidImagePreparer : ImagePreparer {

    override suspend fun prepare(context: Context, uri: Uri): ImagePrepResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri)
        // Open-document URIs carry a persistable read grant; a restored device draft keeps its
        // images only if the grant was taken when the file was picked.
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val source = try {
            runCatching { resolver.openInputStream(uri)?.use { readCapped(it) } }.getOrNull()
        } catch (tooLarge: SourceTooLarge) {
            // The stream is readable, it is just far beyond anything the site would take.
            return@withContext ImagePrepResult.Rejected(
                fileName = name,
                byteCount = ComposerImagePolicy.MAX_SOURCE_BYTES.toLong(),
                reason = ImageRejectReason.TooLarge,
            )
        }
        if (source == null || source.isEmpty()) {
            return@withContext ImagePrepResult.Rejected(
                fileName = name,
                byteCount = 0,
                reason = ImageRejectReason.Unreadable,
            )
        }

        val mime = ComposerImagePolicy.sniffMime(source)
        if (source.size <= ComposerImagePolicy.MAX_UPLOAD_BYTES) {
            if (mime == null) {
                // Readable but not a raster image the site's attachment box accepts.
                return@withContext ImagePrepResult.Rejected(
                    fileName = name,
                    byteCount = source.size.toLong(),
                    reason = ImageRejectReason.Unreadable,
                )
            }
            return@withContext ImagePrepResult.Ready(
                PreparedImage(uri, name, mime, source, downscaled = false),
            )
        }

        val scaled = runCatching { downscaleToJpeg(source) }
            .onFailure { Timber.tag(TAG).w(it, "image downscale failed uri=%s bytes=%d", name, source.size) }
            .getOrNull()
        if (scaled == null) {
            return@withContext ImagePrepResult.Rejected(
                fileName = name,
                byteCount = source.size.toLong(),
                reason = ImageRejectReason.TooLarge,
            )
        }
        ImagePrepResult.Ready(
            PreparedImage(
                uri = uri,
                fileName = ComposerImagePolicy.downscaledFileName(name),
                contentType = MIME_JPEG,
                bytes = scaled,
                downscaled = true,
            ),
        )
    }

    /** The picked file's display name, falling back to the URI's last segment, then a constant. */
    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        val queried = runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
        return queried
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: ComposerImagePolicy.FALLBACK_NAME
    }

    /** Reads a stream, refusing anything above the source ceiling instead of allocating it. */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > ComposerImagePolicy.MAX_SOURCE_BYTES) throw SourceTooLarge()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Re-encodes [source] as JPEG under the 1 MB cap, or returns `null` when no attempt fits.
     *
     * Each attempt draws the decoded bitmap into a fresh white-backed bitmap at the attempt's
     * scale and compresses it at the attempt's quality; the decoded bitmap is released once at the
     * end (attempt bitmaps are released as they are tested). The ladder is bounded and ordered
     * highest-quality-first, so the result is deterministic and the best that fits.
     */
    private fun downscaleToJpeg(source: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ComposerImagePolicy.sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
        try {
            if (decoded.width <= 0 || decoded.height <= 0) return null
            for (attempt in ComposerImagePolicy.downscaleAttempts()) {
                val width = ComposerImagePolicy.scaledDimension(decoded.width, attempt.scale)
                val height = ComposerImagePolicy.scaledDimension(decoded.height, attempt.scale)
                val flattened = flattenOnWhite(decoded, width, height)
                try {
                    val out = ByteArrayOutputStream()
                    flattened.compress(Bitmap.CompressFormat.JPEG, attempt.quality, out)
                    if (out.size() <= ComposerImagePolicy.MAX_UPLOAD_BYTES) return out.toByteArray()
                } finally {
                    flattened.recycle()
                }
            }
            return null
        } finally {
            decoded.recycle()
        }
    }

    /** Copies [source] onto a white ARGB bitmap so a transparent PNG does not turn black as JPEG. */
    private fun flattenOnWhite(source: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Readability ceiling exceeded; thrown internally and mapped to a visible rejection. */
    private class SourceTooLarge : Exception()

    private companion object {
        const val TAG = "ComposerImages"
        const val MIME_JPEG = "image/jpeg"
    }
}

/**
 * The composer's image decisions that do not need a decoder, extracted so they can be asserted.
 *
 * The ladder is deliberately small: a 2–5 MB phone photo lands well under 1 MB on the first or
 * second attempt, and a file that needs all twelve attempts is not something a forum post wants.
 */
internal object ComposerImagePolicy {

    /** The site's attachment box: 大小限制 1MB per image. */
    const val MAX_UPLOAD_BYTES = 1024 * 1024

    /** The composer's own cap; the site's mobile form accepts multiple `Filedata` parts. */
    const val MAX_IMAGES = 5

    /** Hard ceiling on a picked file, so a broken picker cannot allocate hundreds of megabytes. */
    const val MAX_SOURCE_BYTES = 24 * 1024 * 1024

    /** Longest side the re-encoded copy aims for: enough for the site's post width, small enough for 1 MB. */
    const val TARGET_MAX_DIMENSION = 1920

    /** JPEG qualities tried in order, at every scale step. */
    val QUALITY_LADDER = intArrayOf(85, 75, 65, 55)

    /** Each extra scale pass shrinks both sides by this factor. */
    const val SCALE_STEP = 0.8f

    /** How many additional downscale passes follow the first. */
    const val MAX_SCALE_ROUNDS = 2

    const val FALLBACK_NAME = "image.jpg"

    /** One (scale, quality) pair of the re-encode ladder. */
    data class DownscaleAttempt(val scale: Float, val quality: Int)

    /** Highest quality first, then progressively smaller; the first fit wins. */
    fun downscaleAttempts(): List<DownscaleAttempt> = buildList {
        var scale = 1f
        repeat(MAX_SCALE_ROUNDS + 1) {
            QUALITY_LADDER.forEach { quality -> add(DownscaleAttempt(scale, quality)) }
            scale *= SCALE_STEP
        }
    }

    /**
     * Power-of-two decode sample that keeps the longest decoded side near [target].
     *
     * Deliberately the plain Discuz-era formula (`largest / (sample * 2) >= target`): the decoded
     * bitmap's longest side ends up between `target` and `2 * target`, which is what the
     * re-encode ladder then trims. Smaller than the target is never useful — it would only lose
     * detail before the quality ladder had a chance to fit the byte budget.
     */
    fun sampleSizeFor(width: Int, height: Int, target: Int = TARGET_MAX_DIMENSION): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (max(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** One side of the re-encoded copy, never below 1 px. */
    fun scaledDimension(original: Int, scale: Float): Int = max(1, (original * scale).roundToInt())

    /** Sniffs the raster type from magic bytes; `null` when the file is not an accepted image. */
    fun sniffMime(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
        ) {
            return "image/gif"
        }
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        if (bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        ) {
            // HEIF/HEIC camera output: decodable on modern devices, re-encoded to JPEG when over cap.
            return "image/heic"
        }
        return null
    }

    /** The uploaded name for a re-encoded copy: original base name + `.jpg`, because the bytes are JPEG. */
    fun downscaledFileName(original: String): String {
        val base = original.substringBeforeLast('.', original).ifBlank { "image" }
        return "$base.jpg"
    }

    /** Human-readable byte size for the composer's feedback line (`820 KB`, `3.4 MB`). */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "${(bytes + 512) / 1024} KB"
        else -> "$bytes B"
    }
}
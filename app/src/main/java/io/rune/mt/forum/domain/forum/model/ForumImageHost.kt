// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * The MT image host (`https://img.binmt.cc/`) — a **separate service** from the forum.
 *
 * It is announced in bbs.binmt.cc thread 98890 and exists to host pictures at full quality, which
 * the forum's own attachment path does not: that path re-compresses anything over 1 MB. The host
 * answers with a ready-made `[img]…[/img]` line, which is what a composer inserts.
 *
 * The models and the interface live in `domain` so the UI depends on the capability rather than on
 * OkHttp ([io.rune.mt.forum.data.image.ForumImageHostClient] is the only implementation); the
 * payload policy below is shared by the client and the composer's image preparation so both agree
 * on which files the host will take and under what name.
 */
sealed interface ImageHostResult {
    /**
     * One accepted upload: the CDN [url], the [bbcode] the host itself returned (`[img]…[/img]`)
     * and the host's [thumbnailUrl] copy when it provided one.
     */
    data class Success(
        val url: String,
        val bbcode: String,
        val thumbnailUrl: String? = null,
    ) : ImageHostResult

    /** Rejected before/at upload; [reason] is user-facing (the server's own message when it has one). */
    data class Failure(val reason: String) : ImageHostResult
}

/** Upload seam: production is the data-layer client, tests substitute a fake. */
interface ImageHostGateway {
    suspend fun upload(bytes: ByteArray, fileName: String, mimeType: String): ImageHostResult
}

/** What the image host accepts, and how a picked file is named for it. */
object ForumImageHostPolicy {

    /**
     * Accepted formats (REVERSE_SPEC §24). HEIC is *not* in the list even though Android cameras
     * produce it — the composer refuses it with a visible reason instead of uploading a file the
     * host would reject.
     */
    val SUPPORTED_MIME_TO_EXTENSION: Map<String, String> = linkedMapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
    )

    /** True when the host accepts this sniffed MIME type. */
    fun isSupported(mimeType: String): Boolean =
        SUPPORTED_MIME_TO_EXTENSION.containsKey(mimeType.lowercase())

    /**
     * The name the host should store the file under, or `null` when the format is not accepted.
     *
     * The extension follows the **sniffed MIME type**, never the picked name: camera files often
     * carry no extension at all, and a name ending in `.heic` must not be uploaded as a JPEG.
     * A name that already ends in an accepted extension is kept verbatim.
     */
    fun uploadFileName(fileName: String, mimeType: String): String? {
        val extension = SUPPORTED_MIME_TO_EXTENSION[mimeType.lowercase()] ?: return null
        val base = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
        val current = base.substringAfterLast('.', "").lowercase()
        if (current in SUPPORTED_MIME_TO_EXTENSION.values) return base
        val stem = base.substringBeforeLast('.', base).ifBlank { "image" }
        return "$stem.$extension"
    }
}
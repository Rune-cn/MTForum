// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * Messaging, favourites, follow and "new thread" models.
 *
 * These were reverse-engineered from the live site (see docs/REVERSE_SPEC.md §12–§16) and are kept
 * in one file because they are all "the write/extra read surfaces" that the first milestone of the
 * client did not implement.
 */

/** One row in the private-message conversation list. */
data class ForumPrivateConversation(
    val uid: Int,
    val name: String,
    val avatarUrl: String? = null,
    val lastTimeText: String? = null,
    val lastPreview: String? = null,
)

/** One message bubble inside a private conversation. */
data class ForumPrivateMessage(
    val pmid: Long? = null,
    val fromUid: Int = 0,
    /** Display name of the sender, when known (the bubble itself only carries the avatar). */
    val fromName: String = "",
    val avatarUrl: String? = null,
    val timeText: String? = null,
    /** Raw HTML of the bubble body. */
    val contentHtml: String,
    /** True for messages the signed-in user sent (right-aligned bubble). */
    val isMine: Boolean = false,
)

/** A private-message conversation with one peer. */
data class ForumConversationDetail(
    val withUid: Int,
    val withName: String = "",
    val messages: List<ForumPrivateMessage> = emptyList(),
    /** pmid for the reply form; null when starting a brand-new conversation. */
    val pmid: String? = null,
    val formhash: String? = null,
    val page: Int = 1,
    val totalPages: Int = 1,
)

/** A favourited thread row. */
data class ForumFavorite(
    val favid: Long,
    val tid: Int? = null,
    val title: String = "",
    val forumName: String? = null,
    val timeText: String? = null,
)

/** Payload for one image to attach when creating a thread. */
class NewThreadImage(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NewThreadImage) return false
        return fileName == other.fileName &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** Outcome of a "new thread" submission. */
sealed interface NewThreadResult {
    data class Success(val message: String? = null, val tid: Int? = null) : NewThreadResult
    data class Failure(val reason: String) : NewThreadResult
}

/** Hidden credentials of the new-thread form, fetched before composing a post. */
data class ForumNewThreadForm(
    val formhash: String? = null,
    val posttime: String? = null,
    /** True when the server requires a captcha; the client must not submit blindly. */
    val needsCaptcha: Boolean = false,
)

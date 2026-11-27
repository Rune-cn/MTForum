// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.content.Context
import android.net.Uri
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumFavorite
import io.rune.mt.forum.domain.forum.model.ForumFavoritesPage
import io.rune.mt.forum.domain.forum.model.ForumGuideView
import io.rune.mt.forum.domain.forum.model.ForumHomeData
import io.rune.mt.forum.domain.forum.model.ForumNewThreadDraft
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.ForumNoticePage
import io.rune.mt.forum.domain.forum.model.ForumNoticeTab
import io.rune.mt.forum.domain.forum.model.ForumPrivateConversation
import io.rune.mt.forum.domain.forum.model.ForumReplyDraft
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import io.rune.mt.forum.domain.forum.model.ForumSignInState
import io.rune.mt.forum.domain.forum.model.ForumThreadDetail
import io.rune.mt.forum.domain.forum.model.ForumThreadPage
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.forum.model.ForumUserProfile
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import io.rune.mt.forum.domain.forum.model.ImageHostResult
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.model.NewThreadResult
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.ui.page.forum.ImagePrepResult
import io.rune.mt.forum.ui.page.forum.ImagePreparer
import io.rune.mt.forum.ui.page.forum.PreparedImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fakes shared by the two composer suites.
 *
 * They are `internal` (not private-per-file) so `ForumNewThreadImageHostTest` and
 * `ForumPmComposerTest` can both use them without duplicating a 30-method repository stub; nothing
 * here touches the network or the filesystem.
 */

/** The host's own answer for every upload in these suites. */
internal class FakeImageHost : ImageHostGateway {
    var result: ImageHostResult = ImageHostResult.Success(
        url = URL,
        bbcode = "[img]$URL[/img]",
        thumbnailUrl = "https://img.binmt.cc/thumbnails/6b094ea47a7a2.png",
    )

    /** Byte length of the last upload — proves the composer sent the bytes it prepared. */
    var uploadedBytes = 0
    var uploadedName: String? = null

    override suspend fun upload(bytes: ByteArray, fileName: String, mimeType: String): ImageHostResult {
        uploadedBytes = bytes.size
        uploadedName = fileName
        return result
    }

    companion object {
        const val URL = "https://icdn.binmt.cc/2611/6b094ea47a7a2.png"
    }
}

/** Returns the original bytes for every pick; the decode half is covered by [ForumImageDecoderTest]. */
internal class FakeImagePreparer : ImagePreparer {
    override suspend fun prepare(context: Context, uri: Uri): ImagePrepResult =
        ImagePrepResult.Ready(PreparedImage(uri, "probe.png", "image/png", PHOTO, downscaled = false))

    override suspend fun prepareForHost(context: Context, uri: Uri): ImagePrepResult =
        ImagePrepResult.Ready(PreparedImage(uri, "probe.png", "image/png", PHOTO, downscaled = false))

    companion object {
        val PHOTO = byteArrayOf(1, 2, 3, 4)
    }
}

/** One captured `sendPrivateMessage` call: the exact field set the site receives. */
internal data class SentMessage(
    val uid: Int,
    val message: String,
    val formhash: String,
    val pmid: String?,
)

/**
 * Minimal [ForumRepository] fake: only the surfaces the composers read are wired; everything else
 * fails loudly so a test cannot start depending on it by accident.
 */
internal class ComposerTestRepository : ForumRepository {

    var detail: ForumConversationDetail = ForumConversationDetail(withUid = 0)

    /** Every captured send, in order. */
    val sentMessages = mutableListOf<SentMessage>()

    /** When true the site "rejects" the send, i.e. `sendPrivateMessage` answers false. */
    var failSend = false

    override val session: StateFlow<ForumSession> = MutableStateFlow(
        ForumSession(
            cookies = mapOf("cQWy_2132_auth" to "test"),
            user = ForumUser(uid = 153_466, name = "Rune", isLoggedIn = true),
        ),
    )

    override suspend fun getConversation(uid: Int, page: Int): ForumConversationDetail = detail

    override suspend fun sendPrivateMessage(uid: Int, message: String, formhash: String, pmid: String?): Boolean {
        sentMessages += SentMessage(uid = uid, message = message, formhash = formhash, pmid = pmid)
        return !failSend
    }

    override suspend fun getConversations(page: Int): List<ForumPrivateConversation> = notWired()
    override suspend fun getHome(): ForumHomeData = notWired()
    override suspend fun getThreads(fid: Int, page: Int, typeId: Int?): ForumThreadPage = notWired()
    override suspend fun getGuidePage(view: ForumGuideView, page: Int): ForumThreadPage = notWired()
    override suspend fun getThreadDetail(tid: Int, page: Int): ForumThreadDetail = notWired()
    override suspend fun searchThreads(keyword: String, page: Int): ForumThreadPage = notWired()
    override suspend fun reply(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): String = notWired()

    override suspend fun restoreSession(cookies: Map<String, String>): Boolean = notWired()
    override suspend fun loginWithCookie(cookieHeader: String): Boolean = notWired()
    override suspend fun logout(): Unit = notWired()
    override suspend fun getUserProfile(uid: Int): ForumUser? = notWired()
    override suspend fun getUserProfileDetail(uid: Int): ForumUserProfile = notWired()
    override suspend fun getFavorites(page: Int): List<ForumFavorite> = notWired()
    override suspend fun getNotices(tab: ForumNoticeTab, page: Int): ForumNoticePage = notWired()
    override suspend fun getFavoritesPage(page: Int): ForumFavoritesPage = notWired()
    override suspend fun addFavorite(tid: Int, formhash: String): Boolean = notWired()
    override suspend fun removeFavorite(favid: Long, formhash: String): Boolean = notWired()
    override suspend fun followUser(uid: Int, formhash: String): Boolean = notWired()
    override suspend fun unfollowUser(uid: Int, formhash: String): Boolean = notWired()
    override suspend fun recommendThread(tid: Int, formhash: String): Boolean = notWired()
    override suspend fun getSignInState(): ForumSignInState = notWired()
    override suspend fun signIn(formhash: String): ForumSignInOutcome = notWired()
    override suspend fun getNewThreadForm(fid: Int): ForumNewThreadForm = notWired()
    override suspend fun createThread(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): NewThreadResult = notWired()

    private fun <T> notWired(): T = error("not wired for this test")
}

/** Offline draft store: the composers never touch it on the image-host path. */
internal class StubDraftRepository : ForumDraftRepository {
    override suspend fun newThreadDraft(fid: Int): ForumNewThreadDraft? = null
    override suspend fun saveNewThreadDraft(draft: ForumNewThreadDraft) = Unit
    override suspend fun deleteNewThreadDraft(fid: Int) = Unit
    override suspend fun replyDraft(tid: Int): ForumReplyDraft? = null
    override suspend fun saveReplyDraft(draft: ForumReplyDraft) = Unit
    override suspend fun deleteReplyDraft(tid: Int) = Unit
    override suspend fun draftCount(): Int = 0
}

/** Site draft surface, unused by these cases. */
internal class StubDraftService : ForumDraftService {
    override suspend fun getDrafts(page: Int): ForumDraftPage = error("not wired for this test")

    override suspend fun saveNewThreadDraft(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): DraftActionResult = error("not wired for this test")

    override suspend fun saveReplyDraft(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): DraftActionResult = error("not wired for this test")

    override suspend fun loadDraft(tid: Int, fid: Int, pid: Long?): ForumDraftEdit = error("not wired for this test")

    override suspend fun publishDraft(edit: ForumEditForm): DraftActionResult = error("not wired for this test")

    override suspend fun deleteDraft(edit: ForumEditForm): DraftActionResult = error("not wired for this test")

    override suspend fun updateDraft(edit: ForumEditForm, subject: String, message: String): DraftActionResult =
        error("not wired for this test")
}
// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import io.rune.mt.forum.data.forum.network.ForumUrls
import io.rune.mt.forum.data.forum.network.MultipartPart
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.model.ForumDraftEdit
import io.rune.mt.forum.domain.forum.model.ForumDraftPage
import io.rune.mt.forum.domain.forum.model.ForumEditForm
import io.rune.mt.forum.domain.forum.model.ForumNewThreadForm
import io.rune.mt.forum.domain.forum.model.NewThreadImage
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * [ForumDraftService] over the site's own draft feature (docs/REVERSE_SPEC.md §16.1).
 *
 * Measured flow, all through [ForumHttpClient] so the anti-bot interceptor applies:
 *
 * | Action | Request |
 * |---|---|
 * | 草稿列表 | `GET forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2[&page=N]` |
 * | 保存新帖草稿 | `POST …mod=post&action=newthread&fid={fid}&extra=&topicsubmit=yes&mobile=2` with `save=1` |
 * | 保存回复草稿 | `POST …mod=post&action=reply&fid={fid}&tid={tid}&extra=page%3D1&replysubmit=yes&mobile=2` with `save=1` |
 * | 打开发帖草稿 | `GET …mod=post&action=edit&fid={fid}&tid={tid}&pid={pid}&page=1&mobile=2` |
 * | 发表（发布草稿） | `POST` the edit form's own action with `save=` (empty) |
 * | 删除草稿 | `POST` the same action with `delete=1` |
 *
 * The edit form's **whole** hidden field set is replayed from [ForumEditForm.fields]; a hand-built
 * minimal set is rejected or silently ignored by the server.
 *
 * `save=1` publishing the thread — the failure that made the previous round call the whole feature
 * unusable — happens only when `save` is **empty**. With `save=1` the same POST stores a draft and
 * the server answers with the draft's thread id (its thread page then carries the 草稿 badge).
 */
class ForumDraftServiceImpl(
    private val http: ForumHttpClient,
) : ForumDraftService {

    override suspend fun getDrafts(page: Int): ForumDraftPage = withContext(Dispatchers.IO) {
        val html = http.getHtml(ForumUrls.draftListPath(page))
        if (ForumHtmlParser.isLoginRequired(html)) throw ForumLoginRequiredException()
        ForumHtmlParser.parseDraftPage(html, page).also {
            Timber.tag(TAG).i("drafts page=%d rows=%d pages=%d", page, it.drafts.size, it.totalPages)
        }
    }

    override suspend fun saveNewThreadDraft(
        fid: Int,
        subject: String,
        message: String,
        form: ForumNewThreadForm,
        images: List<NewThreadImage>,
    ): DraftActionResult = withContext(Dispatchers.IO) {
        // Same multipart POST as publishing, with the 保存草稿 button's `save=1` on it. The
        // `topicsubmit=yes` marker is part of the measured working request and is kept; it is
        // `save` that decides between "store a draft" and "publish".
        val posttime = form.posttime ?: (System.currentTimeMillis() / 1000).toString()
        val fields = mapOf(
            "formhash" to form.formhash.orEmpty(),
            "posttime" to posttime,
            "subject" to subject,
            "message" to message,
            "htmlon" to "0",
            "usesig" to "1",
            "allownoticeauthor" to "1",
            "save" to "1",
        )
        val parts = images.map { MultipartPart("Filedata", it.fileName, it.contentType, it.bytes) }
        val html = http.postMultipart(
            "forum.php?mod=post&action=newthread&fid=$fid&extra=&topicsubmit=yes&mobile=2",
            fields,
            parts,
        )
        draftOutcome(html, kind = "save-new-thread")
    }

    override suspend fun saveReplyDraft(
        tid: Int,
        fid: Int,
        message: String,
        formhash: String,
        noticeAuthor: String?,
    ): DraftActionResult = withContext(Dispatchers.IO) {
        // The reply form carries the same hidden `postsave`/`save` pair as the new-thread form, so
        // the 保存草稿 button works identically here: POST the reply with `save=1`.
        val fields = buildMap {
            put("formhash", formhash)
            put("message", message)
            put("replysubmit", "yes")
            put("save", "1")
            if (noticeAuthor != null) put("noticeauthor", noticeAuthor)
        }
        val html = http.postForm(
            "forum.php?mod=post&action=reply&fid=$fid&tid=$tid&extra=page%3D1&replysubmit=yes&mobile=2",
            fields,
        )
        draftOutcome(html, kind = "save-reply")
    }

    override suspend fun loadDraft(tid: Int, fid: Int, pid: Long?): ForumDraftEdit =
        withContext(Dispatchers.IO) {
            val html = http.getHtml(ForumUrls.draftEditPath(fid, tid, pid))
            if (ForumHtmlParser.isLoginRequired(html)) throw ForumLoginRequiredException()
            val edit = ForumHtmlParser.parseDraftEdit(html, tid, fid, pid)
            // A draft the author does not own answers with a Discuz notice page instead of a form.
            // Reporting the site's own wording beats handing back an empty composer whose saves
            // would all fail; a form without a formhash is the marker that no form was rendered.
            val notice = ForumHtmlParser.extractNotice(html)
            if (edit.form.formhash.isBlank() && notice != null && ERROR_WORDS.containsMatchIn(notice)) {
                throw IllegalStateException(notice)
            }
            Timber.tag(TAG).i(
                "loadDraft tid=%d fid=%d pid=%s fields=%d",
                tid,
                fid,
                pid,
                edit.form.fields.size,
            )
            edit
        }

    override suspend fun publishDraft(edit: ForumEditForm): DraftActionResult =
        withContext(Dispatchers.IO) {
            // 发表 = the plain submit: `save` empty and `delete=0`. Sent as multipart (no parts)
            // because the live edit form is `enctype="multipart/form-data"`; that is exactly what a
            // browser posts when the 发表 button is pressed without choosing a file.
            val html = http.postMultipart(
                edit.action,
                edit.fields.toMutableMap().apply {
                    put("save", "")
                    put("delete", "0")
                },
                emptyList(),
            )
            draftOutcome(html, kind = "publish")
        }

    override suspend fun deleteDraft(edit: ForumEditForm): DraftActionResult =
        withContext(Dispatchers.IO) {
            // 删除 = comiis_delthread(): `delete=1` on the same form.
            val html = http.postMultipart(
                edit.action,
                edit.fields.toMutableMap().apply {
                    put("delete", "1")
                    put("save", "")
                },
                emptyList(),
            )
            draftOutcome(html, kind = "delete")
        }

    override suspend fun updateDraft(
        edit: ForumEditForm,
        subject: String,
        message: String,
    ): DraftActionResult = withContext(Dispatchers.IO) {
        // The edit page's 保存草稿: same form, new subject/body, `save=1`. Keeps it a draft.
        val html = http.postMultipart(
            edit.action,
            edit.fields.toMutableMap().apply {
                put("subject", subject)
                put("message", message)
                put("save", "1")
                put("delete", "0")
            },
            emptyList(),
        )
        draftOutcome(html, kind = "update")
    }

    /**
     * Classifies a draft write's response.
     *
     * A successful write either redirects to the thread (`thread-<tid>-1-1.html`), which every
     * Discuz post path does, or answers with a 提示 page that carries no error wording. The server
     * speaks its own error text ("抱歉…", "无权…"), so that is what a [DraftActionResult.Failure]
     * carries — a locally invented message would hide the reason.
     */
    private fun draftOutcome(html: String, kind: String): DraftActionResult {
        if (ForumHtmlParser.isLoginRequired(html)) {
            return DraftActionResult.Failure("登录状态已失效，请重新登录")
        }
        val notice = ForumHtmlParser.extractNotice(html)
        if (notice != null && ERROR_WORDS.containsMatchIn(notice)) {
            Timber.tag(TAG).i("%s rejected: %s", kind, notice)
            return DraftActionResult.Failure(notice)
        }
        val tid = ForumHtmlParser.resultTid(html)
        Timber.tag(TAG).i("%s ok tid=%s notice=%s", kind, tid, notice)
        return DraftActionResult.Success(notice, tid)
    }

    private companion object {
        const val TAG = "ForumDraftService"

        /** Discuz error notices that mean the write did not happen. */
        val ERROR_WORDS = Regex("抱歉|非法|错误|不能|失败|无权限|权限不足|间隔|不存在|没有找到")
    }
}
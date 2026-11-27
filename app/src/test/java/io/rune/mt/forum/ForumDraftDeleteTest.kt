// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.data.forum.network.ForumHttpGateway
import io.rune.mt.forum.data.forum.network.ForumHttpResult
import io.rune.mt.forum.data.forum.network.MultipartPart
import io.rune.mt.forum.data.forum.repository.ForumDraftServiceImpl
import io.rune.mt.forum.domain.forum.model.DraftActionResult
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The 草稿 **delete** round trip over the captured pages — no socket, no live request.
 *
 * The device report was 「删除草稿却显示"帖子不存在"，但是却存在」. The site string is Discuz's
 * `post_nonexistence` (lang_message.php: `'post_nonexistence' => '帖子不存在'`), and
 * `post_editpost.php` renders it whenever `get_post_by_tid_pid($_G['tid'], $pid)` is empty — i.e.
 * whenever the edit request carries no `pid`. The 草稿 list row prints no pid, so the client used
 * to request the edit page without one: the site answered 帖子不存在, the client surfaced that as a
 * delete failure, and the `delete=1` POST was never sent (hence "it still exists").
 *
 * The fix reads the pid from the draft's own thread page first. The checks below pin all of it:
 * the pid hop, the replayed delete field set, the success page (whose chrome also carries an
 * error word) and a genuine 主题不存在 failure.
 */
class ForumDraftDeleteTest {

    private val draftTid = 177367
    private val draftFid = 50
    private val draftPid = 12205913L

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `loadDraft reads the draft thread page to learn the pid first`() = runBlocking {
        val http = DraftGateway()
        val edit = ForumDraftServiceImpl(http).loadDraft(draftTid, draftFid, pid = null)

        assertEquals(
            "the row has no pid, so the thread page is the only source for it",
            listOf(
                "thread-$draftTid-1-1.html",
                "forum.php?mod=post&action=edit&fid=$draftFid&tid=$draftTid&pid=$draftPid&page=1&mobile=2",
            ),
            http.requested,
        )
        assertEquals(draftPid, edit.pid)
        assertEquals(draftPid.toString(), edit.form.fields["pid"])
        assertEquals("16385279", edit.form.formhash)
        assertEquals("FULLFORM-DRAFT-a71", edit.subject)
    }

    @Test
    fun `the edit page is never requested without a pid`() = runBlocking {
        // A thread page with no `div.comiis_postli#pid…` cannot yield a pid. Asking the edit page
        // without one is exactly what made the site answer 帖子不存在, so it must not be asked at
        // all — the client refuses with its own reason instead.
        val http = DraftGateway(threadPage = fixture("board_44.html"))

        try {
            ForumDraftServiceImpl(http).loadDraft(draftTid, draftFid, pid = null)
            fail("a pid-less edit request would reproduce the reported 帖子不存在")
        } catch (expected: IllegalStateException) {
            assertTrue("reason mentions pid: ${expected.message}", expected.message?.contains("pid") == true)
        }
        assertTrue(
            "no edit page was requested: ${http.requested}",
            http.requested.none { it.contains("action=edit") },
        )
    }

    @Test
    fun `the pid-less edit page really is the site's page-not-exist page`() = runBlocking {
        // Evidence for the root cause, straight from the parser: the page the old flow fetched is
        // Discuz's `post_nonexistence` notice, and the client used to throw its text.
        val notice = ForumHtmlParser.extractNotice(fixture("draft_notice_post_nonexistence.html"))

        assertEquals("帖子不存在", notice)
    }

    @Test
    fun `deleting a draft replays the form's own fields and is a success`() = runBlocking {
        val http = DraftGateway()
        val service = ForumDraftServiceImpl(http)

        val edit = service.loadDraft(draftTid, draftFid, pid = null)
        val result = service.deleteDraft(edit.form)

        assertTrue(
            "the site answered 主题删除成功; a delete must not become a false failure",
            result is DraftActionResult.Success,
        )
        assertEquals(draftPid.toString(), http.postedFields?.get("pid"))
        assertEquals("1", http.postedFields?.get("delete"))
        assertEquals("", http.postedFields?.get("save"))
        assertEquals(draftTid.toString(), http.postedFields?.get("tid"))
        assertEquals(draftFid.toString(), http.postedFields?.get("fid"))
        assertEquals("16385279", http.postedFields?.get("formhash"))
        assertEquals("yes", http.postedFields?.get("editsubmit"))
        assertEquals(
            "the POST goes to the form's own action, editsubmit included",
            "forum.php?mod=post&action=edit&extra=&editsubmit=yes&mobile=2",
            http.postedPath,
        )
    }

    @Test
    fun `the success page's chrome cannot turn the delete into a failure`() = runBlocking {
        // The captured-shape success page carries an `div.alert_info` whose text is the very
        // 主题不存在 wording. extractNotice must prefer the real message block, and a success
        // notice must win regardless.
        val success = fixture("draft_delete_success.html")
        assertEquals("主题删除成功", ForumHtmlParser.extractNotice(success))

        val http = DraftGateway(deletePage = success)
        val service = ForumDraftServiceImpl(http)
        val edit = service.loadDraft(draftTid, draftFid, null)
        val result = service.deleteDraft(edit.form)

        assertTrue("chrome text must not fail the delete: $result", result is DraftActionResult.Success)
    }

    @Test
    fun `a genuine thread-not-exist answer is a failure with the site's wording`() = runBlocking {
        val http = DraftGateway(deletePage = fixture("draft_notice_thread_nonexistence.html"))
        val service = ForumDraftServiceImpl(http)
        val edit = service.loadDraft(draftTid, draftFid, null)
        val result = service.deleteDraft(edit.form)

        val failure = result as? DraftActionResult.Failure
        assertNotNull("a real 主题不存在 must stay a failure", failure)
        assertTrue(
            "the reason is the server's own wording: ${failure?.reason}",
            failure?.reason?.contains("主题不存在") == true,
        )
    }

    @Test
    fun `a signed-out thread page is login required, not a pid failure`() = runBlocking {
        val http = DraftGateway(threadPage = fixture("login_page.html"))

        try {
            ForumDraftServiceImpl(http).loadDraft(draftTid, draftFid, null)
            fail("a stale session must raise the login card")
        } catch (expected: ForumLoginRequiredException) {
            assertFalse(http.requested.any { it.contains("action=edit") })
        }
    }
}

/**
 * [ForumHttpGateway] over the captured draft pages.
 *
 * Routes by request shape: the thread page, the pid-bearing edit page, the pid-less edit page
 * (the site's `post_nonexistence` answer) and the delete POST. Records every GET path, the POST
 * path and the POSTed field map so a test can pin the wire contract.
 */
private class DraftGateway(
    private val threadPage: String? = null,
    private val deletePage: String? = null,
) : ForumHttpGateway {

    val requested = mutableListOf<String>()
    var postedPath: String? = null
    var postedFields: Map<String, String>? = null

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    override suspend fun getHtmlResult(path: String): ForumHttpResult {
        requested += path
        return ForumHttpResult(
            code = 200,
            url = "https://bbs.binmt.cc/$path",
            body = when {
                path.startsWith("thread-") -> threadPage ?: fixture("draft_thread_view.html")
                // The site reads `pid` from the merged GET/POST parameters; a pid-less edit
                // request is the request the report is about.
                path.contains("action=edit") && !path.contains("pid=") ->
                    fixture("draft_notice_post_nonexistence.html")
                else -> fixture("draft_edit_page.html")
            },
        )
    }

    override suspend fun getHtml(path: String): String = getHtmlResult(path).body

    override suspend fun postForm(path: String, fields: Map<String, String>): String =
        error("draft writes are multipart (got $path)")

    override suspend fun postMultipart(
        path: String,
        fields: Map<String, String>,
        parts: List<MultipartPart>,
    ): String {
        postedPath = path
        postedFields = fields
        requested += "POST $path"
        return deletePage ?: fixture("draft_delete_success.html")
    }
}
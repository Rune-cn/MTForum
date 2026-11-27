// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.data.forum.network.ForumHttpGateway
import io.rune.mt.forum.data.forum.network.ForumHttpResult
import io.rune.mt.forum.data.forum.network.ForumUrls
import io.rune.mt.forum.data.forum.network.MultipartPart
import io.rune.mt.forum.data.forum.repository.ForumDraftServiceImpl
import io.rune.mt.forum.domain.forum.repository.ForumLoginRequiredException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The draft **service** over the captured 草稿 list — no socket, no live request.
 *
 * The parser alone is not enough evidence: this is the layer that decides "rows / login required /
 * failure", and it is the layer that logs the request URL, the HTTP status, the raw row count and
 * the parsed count (docs/HANDOVER_CN.md §16.6). The fixture is the page captured while the account
 * had two drafts.
 */
class ForumDraftServiceTest {

    private fun fixture(name: String): String {
        val path = "src/test/resources/forum/$name"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }

    @Test
    fun `the captured list page yields both drafts`() = runBlocking {
        val http = RecordingGateway {
            ForumHttpResult(
                code = 200,
                url = "https://bbs.binmt.cc/forum.php?mod=guide&view=my",
                body = fixture("drafts_live_list.html"),
            )
        }

        val page = ForumDraftServiceImpl(http).getDrafts()

        assertEquals(2, page.drafts.size)
        assertEquals(listOf(177381, 177363), page.drafts.map { it.tid })
        assertEquals(
            "the service must ask the verified 草稿 filter",
            ForumUrls.draftListPath(1),
            http.requested.single(),
        )
    }

    @Test
    fun `a login form means login required, never an empty list`() = runBlocking {
        val http = RecordingGateway {
            ForumHttpResult(
                code = 200,
                url = "https://bbs.binmt.cc/member.php?mod=logging&action=login",
                body = fixture("login_page.html"),
            )
        }

        try {
            ForumDraftServiceImpl(http).getDrafts()
            fail("a login page must not be reported as 'no drafts'")
        } catch (expected: ForumLoginRequiredException) {
            // This is what raises the screen's login card; the list never becomes "empty".
        }
    }

    @Test
    fun `a non-2xx response stays a failure`() = runBlocking {
        val http = RecordingGateway {
            ForumHttpResult(code = 502, url = "https://bbs.binmt.cc/forum.php", body = "")
        }

        try {
            ForumDraftServiceImpl(http).getDrafts()
            fail("a 5xx must not be reported as 'no drafts'")
        } catch (failure: IllegalStateException) {
            assertTrue(
                "the message keeps the status for the device log: ${failure.message}",
                failure.message?.contains("502") == true,
            )
        }
    }

    @Test
    fun `later pages ask for the paged url`() = runBlocking {
        val http = RecordingGateway {
            ForumHttpResult(code = 200, url = "https://bbs.binmt.cc/x", body = fixture("drafts_live_list.html"))
        }

        ForumDraftServiceImpl(http).getDrafts(page = 2)

        assertEquals(ForumUrls.draftListPath(2), http.requested.single())
        assertTrue("paging must keep the draft filter", http.requested.single().contains("filter=save"))
    }
}

/**
 * [ForumHttpGateway] backed by a captured page.
 *
 * Records the requested path so a test can pin the exact URL; POSTs are rejected because the list
 * path must never write.
 */
private class RecordingGateway(
    private val page: (String) -> ForumHttpResult,
) : ForumHttpGateway {

    val requested = mutableListOf<String>()

    override suspend fun getHtmlResult(path: String): ForumHttpResult {
        requested += path
        return page(path)
    }

    override suspend fun getHtml(path: String): String {
        val result = getHtmlResult(path)
        if (!result.isSuccessful) error("HTTP ${result.code} for $path")
        return result.body
    }

    override suspend fun postForm(path: String, fields: Map<String, String>): String =
        error("the draft list must not POST (got $path)")

    override suspend fun postMultipart(
        path: String,
        fields: Map<String, String>,
        parts: List<MultipartPart>,
    ): String = error("the draft list must not POST (got $path)")
}
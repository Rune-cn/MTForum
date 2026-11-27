// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.net.Uri
import io.rune.mt.forum.domain.forum.model.ForumConversationDetail
import io.rune.mt.forum.domain.forum.model.ImageHostResult
import io.rune.mt.forum.ui.page.forum.ForumMessagesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The private-message composer: BBCode toolbar, image-host insert, preview-safe send.
 *
 * The contract this pins (docs/REVERSE_SPEC.md §12.3 / §24):
 * - a toolbar tag wraps at the caret and leaves the caret between the tags;
 * - an image-host upload inserts `[img]…[/img]` at the caret, and a failure keeps the draft;
 * - **sending is unchanged**: the same `touid` / `formhash` / `pmid` / `message` fields, with the
 *   BBCode travelling as plain text for the site to render.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class ForumPmComposerTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val context get() = RuntimeEnvironment.getApplication()

    private fun uri(): Uri = Uri.parse("content://media/external/images/media/42")

    /** Opens a conversation with a fake page carrying the form's own `formhash` and `pmid`. */
    private fun openConversation(
        repository: ComposerTestRepository,
        host: FakeImageHost,
        pmid: String = "148901",
    ): ForumMessagesViewModel {
        repository.detail = ForumConversationDetail(
            withUid = 153_466,
            withName = "Rune",
            pmid = pmid,
            formhash = "16385279",
        )
        return ForumMessagesViewModel(repository, host)
            .apply { imagePreparer = FakeImagePreparer() }
            .also { viewModel ->
                viewModel.openConversation(153_466, "Rune")
                eventually("the conversation form must load") { viewModel.conversation.value.formhash != null }
            }
    }

    @Test
    fun `pm image inserts bbcode at the caret and send posts the same fields`() {
        val repository = ComposerTestRepository()
        val viewModel = openConversation(repository, FakeImageHost())

        // Caret 6 is right before 图, i.e. after the closing `]` of `[b]`.
        viewModel.updateDraft("你好，[b]图[/b]", caret = 6)
        viewModel.uploadImageToHost(context, uri())
        eventually("the image insert should finish") { !viewModel.conversation.value.isUploadingImage }

        val bbcode = "[img]${FakeImageHost.URL}[/img]"
        assertEquals("the tag lands at the caret", "你好，[b]${bbcode}图[/b]", viewModel.conversation.value.draft)

        viewModel.sendDraft()
        eventually("the message must be sent") { repository.sentMessages.size == 1 }

        val sent = repository.sentMessages.single()
        assertEquals("the recipient is unchanged", 153_466, sent.uid)
        assertEquals("the formhash comes from the parsed page", "16385279", sent.formhash)
        assertEquals("the pmid comes from the parsed page", "148901", sent.pmid)
        assertEquals(
            "the BBCode travels as text — the site renders it",
            "你好，[b]${bbcode}图[/b]",
            sent.message,
        )
        assertEquals("the draft is cleared only after the site accepted", "", viewModel.conversation.value.draft)
    }

    @Test
    fun `a failed pm image upload keeps the draft and reports the reason`() {
        val repository = ComposerTestRepository()
        val host = FakeImageHost().apply { result = ImageHostResult.Failure("图床返回 HTTP 500") }
        val viewModel = openConversation(repository, host)

        viewModel.updateDraft("不要丢的字", caret = 3)
        viewModel.uploadImageToHost(context, uri())
        eventually("the failure should be reported") { viewModel.conversation.value.imageHostError != null }

        assertEquals("图床返回 HTTP 500", viewModel.conversation.value.imageHostError)
        assertEquals("the draft survives a failed upload", "不要丢的字", viewModel.conversation.value.draft)
        assertEquals("nothing was sent", 0, repository.sentMessages.size)
    }

    @Test
    fun `a toolbar tag wraps at the caret and leaves the caret between the tags`() {
        val viewModel = openConversation(ComposerTestRepository(), FakeImageHost())

        viewModel.updateDraft("ab", caret = 1)
        viewModel.insertBbcTag("b")

        assertEquals("a[b][/b]b", viewModel.conversation.value.draft)
        assertEquals("the caret sits between the tags", 4, viewModel.conversation.value.draftCaret)
    }

    @Test
    fun `a failed send keeps the draft and the text that was typed`() {
        val repository = ComposerTestRepository()
        val viewModel = openConversation(repository, FakeImageHost())
        viewModel.updateDraft("还没发出去", caret = 5)
        // A stale page: the site would answer with an error, which the fake models as a send failure.
        repository.failSend = true

        viewModel.sendDraft()
        eventually("the failure should be reported") { viewModel.conversation.value.sendError != null }

        assertEquals("发送失败", viewModel.conversation.value.sendError)
        assertEquals("a failed send must not clear the draft", "还没发出去", viewModel.conversation.value.draft)
    }

    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError(message)
    }
}
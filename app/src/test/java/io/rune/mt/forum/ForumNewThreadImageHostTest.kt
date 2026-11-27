// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.net.Uri
import io.rune.mt.forum.domain.forum.model.ImageHostResult
import io.rune.mt.forum.ui.page.forum.ComposerText
import io.rune.mt.forum.ui.page.forum.NewThreadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The new-thread composer's **image-host** path, with every network hop faked.
 *
 * What matters to the user (docs/REVERSE_SPEC.md §24, docs/HANDOVER_CN.md §21):
 * - the returned `[img]…[/img]` lands **at the caret**, not appended at the end;
 * - an image-host picture does **not** join the attachment strip (the two destinations differ);
 * - a failure surfaces the reason and leaves the typed text untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class ForumNewThreadImageHostTest {

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

    private fun viewModel(host: FakeImageHost = FakeImageHost()): NewThreadViewModel =
        NewThreadViewModel(
            ComposerTestRepository(),
            StubDraftRepository(),
            StubDraftService(),
            host,
        ).apply { imagePreparer = FakeImagePreparer() }

    @Test
    fun `an uploaded image inserts its bbcode at the caret, not at the end`() {
        val host = FakeImageHost()
        val viewModel = viewModel(host)
        viewModel.updateMessage("第一行\n第二行", caret = 3)

        viewModel.uploadImageToHost(context, uri())
        eventually("the upload should complete") { !viewModel.state.value.isUploadingImage }

        val state = viewModel.state.value
        val bbcode = "[img]${FakeImageHost.URL}[/img]"
        assertEquals(
            "the tag must land at caret 3, inside the first line",
            "第一行$bbcode\n第二行",
            state.message,
        )
        assertEquals("the caret follows the inserted tag", 3 + bbcode.length, state.messageCaret)
        assertEquals("the prepared bytes are what was uploaded", 4, host.uploadedBytes)
        assertEquals("the inserted link is reported", FakeImageHost.URL, state.imageFeedback.single().hostUrl)
        assertTrue("an image-host picture is not a forum attachment", state.images.isEmpty())
        assertNull(state.hostUploadError)
    }

    @Test
    fun `a failed image-host upload keeps every typed character and surfaces the reason`() {
        val host = FakeImageHost().apply { result = ImageHostResult.Failure("CSRF token mismatch.") }
        val viewModel = viewModel(host)
        viewModel.updateMessage("已经写好的正文", caret = 5)

        viewModel.uploadImageToHost(context, uri())
        eventually("the failure should be reported") { viewModel.state.value.hostUploadError != null }

        val state = viewModel.state.value
        assertEquals("CSRF token mismatch.", state.hostUploadError)
        assertEquals("a failure must never lose the text", "已经写好的正文", state.message)
        assertEquals("the caret is untouched", 5, state.messageCaret)
        assertFalse(state.isUploadingImage)
        assertTrue("nothing may be inserted on failure", state.imageFeedback.none { it.hostUrl != null })
    }

    @Test
    fun `an emoji is inserted at the caret too`() {
        val viewModel = viewModel()
        viewModel.updateMessage("ab", caret = 1)

        viewModel.insertText(":D")

        assertEquals("a:Db", viewModel.state.value.message)
        assertEquals("the caret sits after the emoji code", 3, viewModel.state.value.messageCaret)
    }

    @Test
    fun `caret insertion clamps a stale caret instead of throwing`() {
        assertEquals(ComposerText.insert("abc", 999, "X"), ComposerText.insert("abc", 3, "X"))
        assertEquals("abcX", ComposerText.insert("abc", 999, "X").text)
        assertEquals("Xabc", ComposerText.insert("abc", -5, "X").text)
        assertEquals(
            "a stale caret clamps to the empty text, then the caret sits after [b]",
            3,
            ComposerText.wrap("", 42, "[b]", "[/b]").caret,
        )
        assertEquals("[b][/b]", ComposerText.wrap("", 42, "[b]", "[/b]").text)
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
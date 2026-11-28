// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.ui.page.forum.ComposerText
import io.rune.mt.forum.ui.page.forum.NewThreadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared BBCode toolbar's insertion semantics, asserted on the pure helper and through the
 * new-thread composer's view model.
 *
 * Wrappers leave the caret between the tags; `[list]` inserts a `[*]` skeleton with the caret after
 * the marker; `[code]` lands on its own lines with the caret on the blank inner line. These are the
 * behaviours the toolbar buttons rely on, so they are pinned independently of the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class ForumEditorToolbarTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): NewThreadViewModel = NewThreadViewModel(
        ComposerTestRepository(),
        StubDraftRepository(),
        StubDraftService(),
        FakeImageHost(),
    ).apply { imagePreparer = FakeImagePreparer() }

    @Test
    fun `a wrapper leaves the caret between the tags`() {
        val edit = ComposerText.wrapTag("ab", caret = 1, name = "b")
        assertEquals("a[b][/b]b", edit.text)
        assertEquals("the caret sits between the tags", 4, edit.caret)
    }

    @Test
    fun `list inserts a star skeleton with the caret after the marker`() {
        val edit = ComposerText.wrapList("ab", caret = 1)
        assertEquals("a[list]\n[*]\n[/list]b", edit.text)
        assertEquals(
            "the caret sits right after the first [*] so the user types item one",
            "a[list]\n[*]".length,
            edit.caret,
        )
    }

    @Test
    fun `code lands on its own lines with the caret on the blank inner line`() {
        val edit = ComposerText.wrapCode("abc", caret = 1)
        assertEquals("a\n[code]\n\n[/code]\nbc", edit.text)
        assertEquals("the caret sits on the blank line inside the code block", 9, edit.caret)
        assertTrue("no marker may glue to the surrounding text", edit.text.contains("\n[code]\n"))
    }

    @Test
    fun `new thread view model inserts list and code at the caret`() {
        val viewModel = viewModel()

        viewModel.updateMessage("ab", caret = 1)
        viewModel.insertList()
        assertEquals("a[list]\n[*]\n[/list]b", viewModel.state.value.message)
        assertEquals("a[list]\n[*]".length, viewModel.state.value.messageCaret)

        viewModel.updateMessage("ab", caret = 1)
        viewModel.insertCode()
        assertEquals("a\n[code]\n\n[/code]\nb", viewModel.state.value.message)
        assertEquals("the code caret lands on the blank line", 9, viewModel.state.value.messageCaret)
    }

    @Test
    fun `new thread view model wraps a toolbar tag at the caret`() {
        val viewModel = viewModel()
        viewModel.updateMessage("xy", caret = 2)
        viewModel.insertTag("quote")
        assertEquals("xy[quote][/quote]", viewModel.state.value.message)
        assertEquals("the caret sits between the quote tags", "xy[quote]".length, viewModel.state.value.messageCaret)
    }
}

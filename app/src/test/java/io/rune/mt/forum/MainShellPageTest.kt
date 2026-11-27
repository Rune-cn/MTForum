// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.ui.navigation.MAIN_PAGE_FORUM
import io.rune.mt.forum.ui.navigation.resolveInitialMainPageIndex
import io.rune.mt.forum.ui.page.main.settings.SettingsSharedViewModel
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A home deep link must land on the 论坛 tab, not merely reopen the app root.
 *
 * The shell restores the last visited tab (`lastMainPageIndex`) on every composition; a link
 * therefore has to set a one-shot request that wins over it. These checks cover the two halves of
 * that contract without a device:
 *  - [SettingsSharedViewModel.requestMainPage] sets the request, [consumeMainPageRequest] clears it
 *    (so a later recomposition falls back to the restored tab instead of yanking the user back);
 *  - [resolveInitialMainPageIndex] resolves the pager's first page, with the request beating the
 *    restored tab and stale values clamped into the bar.
 *
 * The parser/navigator half is covered by `ForumDeepLinkTest` and `ForumDeepLinkNavigationTest`.
 */
class MainShellPageTest {

    @Test
    fun `a pending home request wins over the last visited tab`() {
        assertEquals(MAIN_PAGE_FORUM, resolveInitialMainPageIndex(lastIndex = 3, requestedIndex = 0, pageCount = 4))
        // The 论坛 tab really is the first page of the bar.
        assertEquals(0, MAIN_PAGE_FORUM)
    }

    @Test
    fun `without a request the previous tab is restored`() {
        assertEquals(3, resolveInitialMainPageIndex(lastIndex = 3, requestedIndex = null, pageCount = 4))
    }

    @Test
    fun `stale page indices are clamped into the bar`() {
        assertEquals(3, resolveInitialMainPageIndex(lastIndex = 99, requestedIndex = null, pageCount = 4))
        assertEquals(0, resolveInitialMainPageIndex(lastIndex = -4, requestedIndex = null, pageCount = 4))
        assertEquals(3, resolveInitialMainPageIndex(lastIndex = 2, requestedIndex = 42, pageCount = 4))
        assertEquals(0, resolveInitialMainPageIndex(lastIndex = 2, requestedIndex = 0, pageCount = 0))
    }

    @Test
    fun `the home request is one shot`() {
        val viewModel = SettingsSharedViewModel()

        viewModel.requestMainPage(MAIN_PAGE_FORUM)
        assertEquals(MAIN_PAGE_FORUM, viewModel.state.value.pendingMainPageIndex)

        viewModel.consumeMainPageRequest()
        assertNull(viewModel.state.value.pendingMainPageIndex)
        // The restored tab is untouched by the request, so the next composition keeps it.
        assertEquals(0, viewModel.state.value.lastMainPageIndex)

        viewModel.updateLastMainPageIndex(2)
        viewModel.requestMainPage(MAIN_PAGE_FORUM)
        viewModel.consumeMainPageRequest()
        assertEquals(2, viewModel.state.value.lastMainPageIndex)
    }

    /**
     * The two wiring points that a unit test cannot compose on its own: the deep-link effect in
     * `AppNavContainer` (which asks for the home page) and the shell in `MiuixNavWrapper` (which
     * honours the request and then clears it). Both are one-liners inside composables that need
     * the whole Koin graph, so this pins them the way `SettingsAccountEntryTest` pins its page.
     */
    @Test
    fun `deep link asks for the home page and the shell honours the request`() {
        val container = File(
            "src/main/java/io/rune/mt/forum/ui/navigation/AppNavContainer.kt",
        )
            .also { assertTrue("AppNavContainer not found at ${it.absolutePath}", it.isFile) }
            .readText()
        val wrapper = File(
            "src/main/java/io/rune/mt/forum/ui/navigation/MiuixNavWrapper.kt",
        )
            .also { assertTrue("MiuixNavWrapper not found at ${it.absolutePath}", it.isFile) }
            .readText()

        assertTrue(
            "a home link must request the 论坛 page from the deep-link effect",
            container.contains("requestMainPage(MAIN_PAGE_FORUM)"),
        )
        assertTrue(
            "the shell must resolve its initial page through the request-aware helper",
            wrapper.contains("resolveInitialMainPageIndex("),
        )
        assertTrue(
            "the shell must animate to the requested page",
            wrapper.contains("mainPagerState.animateToPage(target)"),
        )
        assertTrue(
            "the shell must clear the one-shot request after honouring it",
            wrapper.contains("consumeMainPageRequest()"),
        )
    }
}
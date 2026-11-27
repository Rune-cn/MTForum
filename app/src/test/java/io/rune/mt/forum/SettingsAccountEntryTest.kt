// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.rune.mt.forum.domain.forum.model.ForumSession
import io.rune.mt.forum.domain.forum.model.ForumUser
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.main.settings.AccountSection
import io.rune.mt.forum.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings page's account card is now the only "my profile" entry point.
 *
 * The 我的主页 row was removed from the 论坛 section on user request, so the avatar + name header
 * has to carry that navigation. These checks compose the real [AccountSection] and query its
 * semantics tree; the section scan below pins the rows the settings page still renders.
 *
 * Robolectric resolves `stringResource` against `values/` (English), so the labels asserted here
 * are the English ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class SettingsAccountEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private val user = ForumUser(
        uid = 153466,
        name = "Rune",
        avatarUrl = null, // no avatar fetch: the letter fallback keeps the test off the network
        level = "Lv.5",
        isLoggedIn = true,
    )

    private val signedIn = ForumSession(cookies = mapOf("cQWy_2132_auth" to "test"), user = user)

    @Test
    fun `avatar entry opens the signed-in user's own profile`() {
        var opened: ForumUser? = null
        composeSection(signedIn, onOpenProfile = { opened = it })

        compose.onNodeWithContentDescription("My profile").assertIsDisplayed().performClick()

        assertEquals(user, opened, "the avatar/name header must hand back the signed-in user")
    }

    @Test
    fun `sign out button keeps its own click target`() {
        var opened: ForumUser? = null
        composeSection(signedIn, onOpenProfile = { opened = it })

        compose.onNodeWithText("Sign out").performClick()
        compose.waitForIdle()

        // The sign-out path opens its confirmation dialog; if the header's clickable had covered
        // the button this tap would have navigated instead and no dialog would appear.
        compose.onNodeWithText("Sign out?").assertIsDisplayed()
        assertEquals(null, opened, "signing out must not also navigate to the profile")
    }

    @Test
    fun `signed out card exposes no profile entry`() {
        composeSection(
            ForumSession(),
            onOpenProfile = { error("a guest has no profile entry") },
        )

        compose.onNodeWithText("Not signed in").assertIsDisplayed()
        compose.onNodeWithContentDescription("My profile").assertDoesNotExist()
    }

    /**
     * The 论坛 section of the settings page: favourites survives, the notifications and profile
     * rows are gone. The section is built inline in `MiuixPreferredPage`'s `LazyListScope`, which
     * cannot be composed on its own, so this scans the page source — the same kind of static
     * contract check `ForumDeepLinkManifestTest` applies to the manifest.
     */
    @Test
    fun `forum section keeps only the favourites row`() {
        val source = File("src/main/java/io/rune/mt/forum/ui/page/miuix/settings/preferred/MiuixPreferredPage.kt")
            .also { assertTrue(it.isFile, "settings page source not found at ${it.absolutePath}") }
            .readText()

        assertTrue(
            source.contains("R.string.favorites_title"),
            "the favourites row must stay in the settings forum section",
        )
        assertTrue(
            !source.contains("R.string.notice_entry_title"),
            "the notifications row must be gone from the settings forum section",
        )
        assertTrue(
            !source.contains("R.string.profile_entry_title"),
            "the my-profile row must be gone from the settings forum section",
        )
        assertTrue(
            !source.contains("Route.Notices"),
            "the settings page must no longer navigate to the notices page",
        )
        // The avatar now feeds the profile route instead of a settings row.
        assertTrue(
            source.contains("Route.UserProfile("),
            "the settings page must open the profile from the account card",
        )
    }

    @Test
    fun `robolectric resolves the english profile label`() {
        // Guards the assertions above: they only mean something if "My profile" is the real
        // default-locale string (the same key is now the avatar's content description).
        val context = RuntimeEnvironment.getApplication()
        assertEquals("My profile", context.getString(R.string.profile_entry_title))
    }

    /**
     * 签到 moved from the settings card to the home top bar (user request), so the settings page
     * must not host it any more while the component itself stays in the codebase for its new
     * home. Both sides are pinned: absence in the settings source, presence in the home source.
     *
     * The settings section is built inline in a `LazyListScope` and the home page needs the whole
     * Koin graph, so this is the same static contract check the forum-section test above uses;
     * `ForumHomeSignInEntryTest` drives the real widgets for the behaviour.
     */
    @Test
    fun `sign-in is reached from the home top bar not the settings page`() {
        val settings = File("src/main/java/io/rune/mt/forum/ui/page/miuix/settings/preferred/MiuixPreferredPage.kt")
            .also { assertTrue(it.isFile, "settings page source not found at ${it.absolutePath}") }
            .readText()
        val home = File("src/main/java/io/rune/mt/forum/ui/page/forum/ForumHomePage.kt")
            .also { assertTrue(it.isFile, "home page source not found at ${it.absolutePath}") }
            .readText()

        assertTrue(
            !settings.contains("ForumSignInCard"),
            "the 签到 panel must be gone from the settings page",
        )
        assertTrue(
            !settings.contains("R.string.sign_in_section_title"),
            "the settings page must not keep the 每日签到 section title",
        )
        assertTrue(
            home.contains("ForumSignInSheet(") && home.contains("R.string.sign_in_section_title"),
            "the home page must open the sign-in sheet from the top bar",
        )
        assertTrue(
            home.contains("MiuixTopBarIconButton"),
            "the entry point must stay a Miuix top-bar icon button (rule 7)",
        )
    }

    private fun composeSection(
        session: ForumSession,
        onOpenProfile: (ForumUser) -> Unit,
    ) {
        compose.setContent {
            AppTheme(
                useMiuix = true,
                themeMode = ThemeMode.LIGHT,
                paletteStyle = PaletteStyle.TonalSpot,
                colorSpec = ThemeColorSpec.SPEC_2021,
                useDynamicColor = false,
                useMiuixMonet = false,
                seedColor = androidx.compose.ui.graphics.Color(0xFF2E9E5B),
                useMiuixOs4 = false,
            ) {
                AccountSection(
                    session = session,
                    onLogin = {},
                    // The sign-out button only opens a confirmation; the real logout is asserted
                    // through the dialog, so this callback stays a no-op probe.
                    onLogout = {},
                    onOpenProfile = onOpenProfile,
                )
            }
        }
        compose.waitForIdle()
    }
}

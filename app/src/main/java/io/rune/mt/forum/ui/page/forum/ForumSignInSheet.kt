// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBottomSheet
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * The 每日签到 surface behind the home top bar's check-in button.
 *
 * Why a sheet instead of a route: the feature is three numbers and one button, and the user asked
 * for it at a tap's distance from the home screen. A route would add a navigation entry (plus a
 * back stack hop and a page to register) for the same three numbers; the sheet keeps the board list
 * behind it and closes with one downward swipe.
 *
 * The sheet itself is [`MiuixBottomSheet`], the shared OS4-glass / OS3-window container that the
 * reply composer also uses, so the 签到 panel and the reply panel cannot drift into two different
 * shapes. The OS4 glass sheet is an **inline** overlay, so [ForumHomePage] composes it as a
 * sibling of the page scaffold in the page root `Box` — never inside the top bar's action cell
 * (the same placement rule as the thread overflow menu, rule 12). [backdrop] is the page-chrome
 * **content** layer (`MiuixPageChrome.backdrop`): the sheet sits outside the subtree that layer
 * records, so sampling it cannot form the RenderNode cycle of rules 23/28, and `null` (no render
 * effect / SafeMode) simply falls back to the opaque fill.
 *
 * The host for the server's verbatim result lives **inside** the sheet. The shell's scaffold
 * snackbar sits behind the sheet's scrim (OS4) or behind its own dialog window (OS3), so a message
 * shown there would be unreadable exactly when the user needs it.
 *
 * @param onLogin Guest action (login route), supplied by the page. It is a parameter rather than a
 *   `LocalNavigator` read inside the panel because the OS4 sheet renders into the root scaffold's
 *   dialog layer, which is **above** the shell's navigator provider — a composition-local read
 *   there would throw.
 * @param backdrop Page-chrome content layer (`MiuixPageChrome.backdrop`) or `null` for the opaque
 *   fallback; never the host-level `pageBackdrop` (rules 23 / 28).
 */
@Composable
internal fun ForumSignInSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    val title = stringResource(R.string.sign_in_section_title)
    val snackbarHostState = remember { SnackbarHostState() }

    MiuixBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = title,
        modifier = modifier,
        backdrop = backdrop,
    ) {
        SignInPanel(
            snackbarHostState = snackbarHostState,
            onLogin = onLogin,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

/**
 * The sheet's body: the shared [`ForumSignInCard`] panel plus its snackbar.
 *
 * The snackbar is bottom-anchored **over** the panel rather than appended under it: a snackbar
 * appearing must not resize the sheet (the sheet's height is its content), and the panel's action
 * row is the row that changes state at the same moment, so covering it for the message's lifetime
 * costs nothing.
 */
@Composable
private fun SignInPanel(
    snackbarHostState: SnackbarHostState,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        ForumSignInCard(snackbarHostState = snackbarHostState, onLogin = onLogin)
        SnackbarHost(
            state = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}
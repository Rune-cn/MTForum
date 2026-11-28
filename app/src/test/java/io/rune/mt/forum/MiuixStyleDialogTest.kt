// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixComponentHost
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixStyleDialog
import io.rune.mt.forum.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * The style split of the modal dialog.
 *
 * `MiuixStyleDialog` is the one place that decides between the OS4 `GlassDialog` (an inline
 * overlay in the root host) and the OS3 `WindowDialog` (a real window). Both must present the
 * title, summary and content; a dialog that silently renders nothing on one side is exactly the
 * kind of leak a screenshot on the other style would miss.
 *
 * Robolectric cannot compile the AGSL glass shader, but the dialog does not need a backdrop here:
 * with `backdrop = null` it uses the material's opaque fallback, so the semantics are still
 * checkable. The visual glass finish remains a device-only check (see HANDOVER §27.6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixStyleDialogTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `os4 dialog shows title summary and content`() = show(useOs4 = true)

    @Test
    fun `os3 dialog shows title summary and content`() = show(useOs4 = false)

    private fun show(useOs4: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalSquircleEnabled provides false) {
                AppTheme(
                    useMiuix = true,
                    themeMode = ThemeMode.LIGHT,
                    paletteStyle = PaletteStyle.TonalSpot,
                    colorSpec = ThemeColorSpec.SPEC_2021,
                    useDynamicColor = false,
                    useMiuixMonet = false,
                    seedColor = Color(0xFF2E9E5B),
                    useMiuixOs4 = useOs4,
                ) {
                    MiuixComponentHost(useOs4 = useOs4, useBlur = false) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            MiuixStyleDialog(
                                show = true,
                                onDismissRequest = {},
                                title = "Delete draft",
                                summary = "This cannot be undone",
                                modifier = Modifier,
                            ) {
                                Text(text = "Confirm")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithText("Delete draft").assertIsDisplayed()
        compose.onNodeWithText("This cannot be undone").assertIsDisplayed()
        compose.onNodeWithText("Confirm").assertIsDisplayed()
    }
}
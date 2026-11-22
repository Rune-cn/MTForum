// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.ui.page.forum.ForumThreadCard
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real thread card on the JVM through Robolectric.
 *
 * This is the closest thing to "run the APK" available here — the environment cannot boot an
 * Android emulator (nested virtualisation is blocked), so UI correctness is asserted by actually
 * composing the widget and querying the semantics tree rather than by eyeballing a screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class ForumCardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun thread(
        title: String = "论学习通刷课被回退三次的痛苦",
        author: String = "wuti",
        avatar: String? = "https://cdn.binmt.cc/uc_server/data/avatar/000/15/02/24_avatar_middle.jpg",
        pinned: Boolean = false,
        replies: Int = 4,
        views: Int = 95,
        images: List<String> = emptyList(),
    ) = ForumThread(
        tid = 177093,
        fid = 44,
        title = title,
        summary = "让 DeepSeek 根据一个基础项目生成刷课 app",
        authorName = author,
        authorUid = 150224,
        authorAvatarUrl = avatar,
        authorLevel = "Lv.3",
        timeText = "2 小时前",
        replyCount = replies,
        viewCount = views,
        pinned = pinned,
        imageUrls = images,
    )

    @Test
    fun `card renders the poster name and title`() {
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
                ForumThreadCard(thread = thread(), onClick = {})
            }
        }
        compose.onNodeWithText("论学习通刷课被回退三次的痛苦").assertIsDisplayed()
        compose.onNodeWithText("wuti").assertIsDisplayed()
    }

    @Test
    fun `pinned thread shows the pinned badge`() {
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
                ForumThreadCard(thread = thread(pinned = true), onClick = {})
            }
        }
        compose.onNodeWithText("Pinned").assertIsDisplayed()
    }

    @Test
    fun `card with attachments shows the image chip`() {
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
                ForumThreadCard(
                    thread = thread(images = listOf("https://icdn.binmt.cc/2509/x.jpg")),
                    onClick = {},
                )
            }
        }
        compose.onNodeWithText("Image").assertIsDisplayed()
    }

    @Test
    fun `hot thread shows the hot chip`() {
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
                ForumThreadCard(thread = thread(replies = 42, views = 900), onClick = {})
            }
        }
        compose.onNodeWithText("Hot").assertIsDisplayed()
    }

    @Test
    fun `author name still renders when the avatar url is blank`() {
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
                ForumThreadCard(thread = thread(avatar = null), onClick = {})
            }
        }
        compose.onNodeWithText("wuti").assertIsDisplayed()
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.domain.forum.model.ForumPost
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.forum.ForumPostCard
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * Pixel check for the redesigned floor card.
 *
 * There is no emulator on this machine (nested virtualisation is blocked), so the real composable
 * is rendered through Robolectric and captured as a PNG. The two runs cover the OS4 glass-era
 * layout and the classic OS3 layout, since the card itself must stay style-agnostic. Captures are
 * evidence only; a capture failure must not fail the suite (same convention as the menu test).
 *
 * Avatars are null on purpose: the letter fallback keeps the capture deterministic and free of
 * network/CDN dependencies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumPostCardScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private val op = ForumPost(
        pid = 12177297L,
        tid = 177093,
        authorName = "wuti",
        authorUid = 150224,
        authorAvatarUrl = null,
        authorLevel = "Lv.3 初中生",
        timeText = "2 小时前",
        location = "来自 美国",
        contentHtml = """
            <div>让 DeepSeek 根据一个基础项目生成一个刷课的 app，用了三次回退三次。</div>
            <div>有佬友知道目前靠谱的刷课软件吗？</div>
        """.trimIndent(),
        floor = 0,
        isOp = true,
    )

    private val replyFloor = ForumPost(
        pid = 12177386L,
        tid = 177093,
        authorName = "이지은",
        authorUid = 157466,
        authorAvatarUrl = null,
        authorLevel = "Lv.5 大学生",
        timeText = "4 天前",
        location = "来自 山东",
        contentHtml = "<div>github 上有一个开源项目，忘记名字了。</div>",
        floor = 1,
        isOp = false,
    )

    @Test
    fun `capture os4 thread floor cards`() = capture(useOs4 = true, name = "os4-thread-post-cards.png")

    @Test
    fun `capture os3 thread floor cards`() = capture(useOs4 = false, name = "os3-thread-post-cards.png")

    private fun capture(useOs4: Boolean, name: String) {
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF2F3F5))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        ForumPostCard(post = op, forumName = "综合交流", recommendCount = 47, onAuthorClick = {})
                        ForumPostCard(post = replyFloor)
                    }
                }
            }
        }
        compose.waitForIdle()

        // The board label lives on the OP card; asserting it here also proves the forumName
        // plumbing reaches the card (Robolectric resolves the English default strings).
        compose.onNodeWithText("Board: 综合交流").assertIsDisplayed()
        compose.onNodeWithText("wuti").assertIsDisplayed()
        // The thread's 推荐 count is rendered on the OP card only. Robolectric resolves the
        // English default resources, so the format is `<n> likes`.
        compose.onNodeWithText("47 likes").assertIsDisplayed()

        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outputDir, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SCREENSHOT: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure {
            println("SCREENSHOT skipped: ${it::class.java.name}: ${it.message}")
        }
    }
}
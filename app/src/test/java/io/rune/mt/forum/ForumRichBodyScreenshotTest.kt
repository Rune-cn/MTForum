// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.data.forum.network.ForumHtmlParser
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.forum.ForumHtmlText
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
 * Renders a post that exercises every richer body construct and writes a PNG.
 *
 * There is no emulator here, so the real `ForumHtmlText` is composed under Robolectric and
 * captured; the result is the visual evidence that code panels, headings, tables, attachment
 * cards, quotes, lists and rules lay out as blocks instead of loose paragraphs. A capture failure
 * must not fail the suite (same convention as the other screenshot tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class, qualifiers = "w411dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumRichBodyScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private val sampleHtml = """
        <font size="6"><strong>正文渲染示例</strong></font><br />
        这是一段普通正文，包含 <strong>加粗</strong> 和 <font color="#ff0000">红色文字</font>。<br />
        <font size="5"><strong>代码块</strong></font><br />
        <div class="comiis_blockcode comiis_bodybg b_ok f_b"><div class="bg_f b_l"><ol><li>// 示例代码<br /><li>fun main() {<br /><li>&nbsp;&nbsp;println("hi")<br /><li>}</ol></div></div><br />
        <font size="4"><strong>表格</strong></font><br />
        <table><tr><td>名称</td><td>数值</td></tr><tr><td>Alpha</td><td>42</td></tr></table><br />
        <font size="4"><strong>附件</strong></font><br />
        <div class="comiis_attach bg_e b_ok cl"><a href="https://bbs.binmt.cc/forum.php?mod=attachment&amp;aid=ABC"><p class="attach_tit"><span class="f_ok">example.zip</span></p><p class="attach_size f_c">1.20 MB , 下载次数: 4, 下载积分: 金币 -1</p></a></div>
        <div class="comiis_modact bg_b f_c">本主题由 Bin 于 2026-6-6 15:13 加入精华</div>
        <div class="comiis_quote bg_h f_c"><blockquote><font size="2">回复</font> <font color="#999999">alice 发表于 2026-5-8 17:31</font><br /><font color="#999999">引用内容示例</font></blockquote></div>
        <ul><li>条目一<li>条目二</ul>
        <hr class="l" />
        <i class="pstatus"> 本帖最后由 tester 于 2026-5-19 16:20 编辑 </i><br />
    """.trimIndent()

    @Test
    fun `rich body constructs render as blocks`() = render(sampleHtml, "06_rich_body_constructs.png")

    @Test
    fun `real bbcode reply fixture renders`() {
        val html = realReplyHtml()
        render(html, "07_rich_bbcode_reply.png")
    }

    private fun realReplyHtml(): String {
        val path = "src/test/resources/forum/rich_bbcode_reply.html"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        val posts = ForumHtmlParser.parseThreadDetail(file.readText(), tid = 177363, page = 1).posts
        return posts.first { it.contentHtml.contains("<table") }.contentHtml
    }

    private fun render(html: String, name: String) {
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
                    useMiuixOs4 = true,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF2F3F5))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ForumHtmlText(html = html, onLinkClick = {})
                    }
                }
            }
        }
        compose.waitForIdle()

        // The "Code" label is the English default string; asserting it proves the code panel
        // rendered with its affordance rather than being flattened into a paragraph.
        compose.onNodeWithText("Code").assertIsDisplayed()

        runCatching {
            val view = compose.activity.window.decorView
            val width = view.width
            val height = view.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File(outputDir, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SCREENSHOT: ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure {
            println("SCREENSHOT skipped: ${it::class.java.name}: ${it.message}")
        }
    }
}

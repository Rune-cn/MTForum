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
import io.rune.mt.forum.ui.page.forum.AttachmentChoiceSheet
import io.rune.mt.forum.ui.page.forum.ForumBodyBlock
import io.rune.mt.forum.ui.page.forum.ForumBodyParser
import io.rune.mt.forum.ui.page.forum.ForumHtmlText
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixComponentHost
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import org.junit.Assert.assertTrue
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
        <font size="5"><strong>块级对齐</strong></font><br />
        <div align="center"><strong>居中标题</strong></div>
        <div align="center"><ul><li>居中列表一<li>居中列表二</ul></div>
        <font size="5"><strong>表头与合并列</strong></font><br />
        <table>
          <tr><td colspan="2"><div align="center"><strong>合并的表头</strong></div></td></tr>
          <tr><td>普通单元格</td><td><div align="center">居中单元格</div></td></tr>
        </table>
        <font size="5"><strong>嵌套列表</strong></font><br />
        <ul><li>一级<ul><li>二级<ol><li>三级</ol></li></ol></ul></li></ul>
        <font size="5"><strong>引用内的结构</strong></font><br />
        <div class="comiis_quote bg_h f_c"><blockquote>
        <font size="2">回复</font> <font color="#999999">alice 发表于 2026-5-8 17:31</font><br />
        引用里还有列表和图片：<br />
        <ul><li>引用内条目一<li>引用内条目二</ul>
        <img src="https://icdn.binmt.cc/2509/quote-thumb.jpg" border="0" alt="" />
        </blockquote></div>
        <font size="5"><strong>可点击链接与附件</strong></font><br />
        见 <a href="https://bbs.binmt.cc/thread-161119-1-1.html">论坛教程</a> 一节。<br />
        <div class="comiis_attach bg_e b_ok cl"><a href="https://bbs.binmt.cc/forum.php?mod=attachment&amp;aid=IMG"><p class="attach_tit"><img src="https://cdn.binmt.cc/static/image/filetype/image.gif" border="0" class="vm" alt="" /><span class="f_ok">screenshot.png</span><em class="f_d">&nbsp;2026-5-8 17:31上传</em></p><p class="attach_size f_c">88.1 KB , 下载次数: 2</p></a></div>
    """.trimIndent()

    @Test
    fun `rich body constructs render as blocks`() = render(sampleHtml, "06_rich_body_constructs.png")

    @Test
    fun `real bbcode reply fixture renders`() {
        val html = realReplyHtml()
        render(html, "07_rich_bbcode_reply.png")
    }

    /** The real `div.comiis_attach` block from the attachment thread, with its download affordance. */
    @Test
    fun `real attachment block renders with download affordance`() {
        val html = realAttachmentHtml()
        render(html, "08_attachment_download.png")
    }

    /**
     * The real album gallery of thread 177269: its four OP screenshots live in
     * `ul.comiis_img_list`, not in `span.comiis_postimg`. Before the gallery fix this body had no
     * pictures at all; the PNG is the visual proof that the album renders as four picture blocks.
     *
     * The images themselves are remote (`oos.binmt.cc`) and there is no network in tests, so each
     * block shows the renderer's `min-height` placeholder — which is exactly what proves a picture
     * block exists instead of the images being dropped.
     */
    @Test
    fun `real album gallery renders as picture blocks`() {
        val path = "src/test/resources/forum/album_images_177269.html"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        val posts = ForumHtmlParser.parseThreadDetail(file.readText(), tid = 177269, page = 1).posts
        val html = posts.first().contentHtml
        val pictures = ForumBodyParser.parse(html).filterIsInstance<ForumBodyBlock.Picture>()
        assertTrue("the gallery must render as four pictures, got ${pictures.size}", pictures.size == 4)
        assertTrue(
            "every gallery picture must be a real oos file, got ${pictures.map { it.url }}",
            pictures.all { it.url.contains("oos.binmt.cc/") },
        )
        render(html, "09_album_gallery.png")
    }

    private fun realAttachmentHtml(): String {
        val path = "src/test/resources/forum/sample_attach_thread.html"
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        val posts = ForumHtmlParser.parseThreadDetail(file.readText(), tid = 166223, page = 1).posts
        return posts.first { it.contentHtml.contains("comiis_attach") }.contentHtml
    }

    /**
     * The attachment chooser itself: 「下载到本机」 / 「在浏览器打开」 / 「复制链接」.
     *
     * Task B is about the *choice*, so the sheet is the thing worth inspecting. It goes through
     * the shared `MiuixBottomSheet` helper, whose OS4 branch renders into the root host's dialog
     * layer, so the test mounts `MiuixComponentHost` exactly like the app shell does.
     */
    @Test
    fun `attachment chooser sheet renders both actions`() {
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
                    MiuixComponentHost(useOs4 = true, useBlur = false) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFF2F3F5))
                                .padding(16.dp),
                        ) {
                            AttachmentChoiceSheet(
                                target = ForumBodyBlock.Attachment(
                                    name = "Converter-0.2-beta.26042415.mtp",
                                    caption = "67.99 KB , 下载次数: 4, 下载积分: 金币 -1",
                                    url = "https://bbs.binmt.cc/forum.php?mod=attachment&aid=MzYxMTM2",
                                ),
                                download = null,
                                onDismissRequest = {},
                                onDownload = {},
                                onCancelDownload = {},
                                onOpenInBrowser = {},
                                onShareFile = {},
                                onSaveFile = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // Both actions must be present and visible on the sheet. This is an assertion-only test:
        // a `WindowBottomSheet` is a separate dialog *window*, and neither `decorView.draw()` nor
        // `captureToImage()` yields its pixels under Robolectric (both come back blank), so there
        // is no honest screenshot to publish for the sheet itself. The rendered *card* that opens
        // it is captured by `real attachment block renders with download affordance`.
        compose.onNodeWithText("Download to device").assertIsDisplayed()
        compose.onNodeWithText("Open in browser").assertIsDisplayed()
        compose.onNodeWithText("Copy link").assertIsDisplayed()
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

        // Bodies that carry a code panel must render its "Code" label (English default under
        // Robolectric), which proves the panel kept its affordance instead of becoming prose.
        // The attachment-only fixture has no panel, so that assertion is applied conditionally.
        if (html.contains("comiis_blockcode")) {
            compose.onNodeWithText("Code").assertIsDisplayed()
        }

        capture(name)
    }

    /** Draws the current window into `app/build/ui-screenshots/<name>`. */
    private fun capture(name: String) {
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

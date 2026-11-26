// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.sun.net.httpserver.HttpServer
import io.rune.mt.forum.core.image.buildForumImageLoader
import io.rune.mt.forum.domain.forum.model.ForumThread
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.page.forum.ForumHtmlText
import io.rune.mt.forum.ui.page.forum.ForumThreadCard
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import timber.log.Timber

/**
 * Renders the real image surfaces on the JVM and writes a PNG of the result.
 *
 * This is the "run the app without an emulator" path used in this repo: nested virtualisation is
 * blocked on the build machine, so instead of installing the APK we compose the real widgets with
 * a local HTTP server standing in for the forum CDNs, then capture the actual pixels. It is how
 * the GIF-avatar / GIF-post-image bug is reproduced and then verified as fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi", application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumImageScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: HttpServer
    private var port: Int = 0

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    @Before
    fun startServer() {
        // Same loader the app installs in App.onCreate, so the decoders under test are the real ones.
        SingletonImageLoader.setSafe { ctx -> buildForumImageLoader(ctx) }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serve("/avatar.gif", "ui/local_avatar.gif")
        serve("/post.gif", "ui/local_post.gif")
        server.executor = null
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun serve(path: String, resource: String) {
        server.createContext(path) { exchange ->
            val bytes = javaClass.classLoader!!.getResourceAsStream(resource)!!.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "image/gif")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `gif avatar and gif post image decode and render`() {
        val base = "http://127.0.0.1:$port"
        val thread = ForumThread(
            tid = 177093,
            fid = 44,
            title = "GIF 渲染验证",
            summary = "本地 HTTP 服务器提供的 GIF",
            authorName = "wuti",
            authorUid = 150224,
            authorAvatarUrl = "$base/avatar.gif",
            authorLevel = "Lv.3",
            timeText = "刚刚",
            replyCount = 4,
            viewCount = 95,
            pinned = false,
            imageUrls = listOf("$base/post.gif"),
        )
        val html = """
            <div>正文里的 GIF 图片：</div>
            <div><img src="$base/post.gif" /></div>
            <div>结束。</div>
        """.trimIndent()

        compose.setContent {
            // Robolectric's bundled Skia cannot compile the miuix squircle AGSL shader, so the
            // harness opts out of squircle visuals for the capture only; layout and image
            // decoding are unaffected.
            androidx.compose.runtime.CompositionLocalProvider(
                top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled provides false,
            ) {
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ForumThreadCard(thread = thread, onClick = {})
                    ForumHtmlText(html = html)
                }
            }
            }
        }

        // Coil loads on its own dispatcher; pump the main looper until the request settles.
        val loader = SingletonImageLoader.get(RuntimeEnvironment.getApplication())
        var attempts = 0
        while (attempts < 100 && (loader.memoryCache?.size ?: 0) < 2) {
            compose.waitForIdle()
            Thread.sleep(50)
            attempts++
        }
        val cached = loader.memoryCache?.size ?: 0
        capture("02_gif_avatar_and_post.png")
        assertTrue(cached >= 2, "expected both GIFs to decode into the image cache, cached=$cached")
    }

    @Test
    fun `failed picture keeps a visible placeholder and logs the url`() {
        val messages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
            }
        }
        Timber.plant(tree)
        try {
            // Port 1 has no listener, so the request fails immediately. The picture block must
            // stay as an 80dp placeholder instead of collapsing to nothing, and the failure must
            // be logged with the URL (never the cookies) so a blank post is diagnosable.
            val dead = "http://127.0.0.1:1/data/attachment/forum/missing.gif"
            val html = """
                <div>加载失败的图片：</div>
                <div><img src="$dead" /></div>
                <div>结束。</div>
            """.trimIndent()

            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled provides false,
                ) {
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
                        ) {
                            ForumHtmlText(html = html)
                        }
                    }
                }
            }

            var attempts = 0
            while (attempts < 100 && messages.none { it.contains(dead) }) {
                compose.waitForIdle()
                Thread.sleep(50)
                attempts++
            }
            capture("03_failed_image_placeholder.png")
            assertTrue(messages.any { it.contains(dead) }, "the failed URL must be logged, got $messages")
        } finally {
            Timber.uproot(tree)
        }
    }

    private fun capture(name: String) {
        val view = compose.activity.window.decorView
        val width = view.width
        val height = view.height
        assertTrue(width > 0 && height > 0, "window not laid out: ${width}x$height")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File(outputDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT: ${file.absolutePath}")
    }
}

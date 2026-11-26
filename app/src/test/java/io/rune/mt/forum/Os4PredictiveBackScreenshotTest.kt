// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.animation.predictiveback.OS4_DIM_AMOUNT
import io.rune.mt.forum.ui.animation.predictiveback.OS4_FALLBACK_CORNER_RADIUS
import io.rune.mt.forum.ui.animation.predictiveback.miuixOs4NavTransition
import io.rune.mt.forum.ui.animation.predictiveback.os4ExitCardGeometry
import io.rune.mt.forum.ui.animation.predictiveback.os4ScrimFraction
import io.rune.mt.forum.ui.theme.AppTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.squircle.absoluteSquircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OS4 预测式返回在「拖到 45%」时的截图 + 像素级几何校验。
 *
 * 真机不可用（本机跑不了模拟器），NavDisplay 的驱动浮点（`animatedTop`）又是库内部的，
 * 没法在测试里把它拨到 0.45。这里退一步：**用真实的 [miuixOs4NavTransition] 与真实的
 * [NavTransitionScope]**（一个深度固定的假 scope，gesture 非空 → 库的
 * `navDirectionalTransition` 会走 `predictivePop` 分支），按 `NavDisplayLayout` 的层次
 * （backdrop → 下层页 → 黑色 scrim → 上层卡片）叠出同一帧，再截图。
 *
 * 因此截图里的变换不是照着常量重写的一份，而是生产代码算出来的；像素断言也随之校验了
 * 「出页缩放 + 位移」「入页放大」「scrim 上限」三件事。产物：
 * `app/build/ui-screenshots/os4-predictive-back.png`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi", application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Os4PredictiveBackScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** 中期手势进度（用户反馈里「拖到一半」的样子）。 */
    private val progress = 0.45f

    private val outputDir = File("build/ui-screenshots").apply { mkdirs() }

    private class Frame(
        val layoutSize: IntSize,
        val backdrop: Color,
        val previousPage: Color,
        val currentPage: Color,
        val scrimAlpha: Float,
    )

    private val frame = AtomicReference<Frame>()

    @Test
    fun `render the os4 predictive back card at mid gesture`() {
        compose.setContent {
            // Robolectric 自带 Skia 编译不了 miuix squircle 的 AGSL（和仓库里其它截图测试同样的处理），
            // 关掉后 absoluteSquircleClip 会退化成普通 RoundedCornerShape，几何不受影响。
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
                    Os4PredictiveBackFrame(progress = progress, onFrame = { frame.set(it) })
                }
            }
        }
        compose.waitForIdle()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val png = File(outputDir, "os4-predictive-back.png")
        png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT: ${png.absolutePath} (${bitmap.width}x${bitmap.height})")

        val captured = requireNotNull(frame.get()) { "frame 没有被记录" }
        assertTrue(bitmap.width > 0 && bitmap.height > 0, "窗口没有布局：${bitmap.width}x${bitmap.height}")
        assertTrue(
            newSizeMatches(captured.layoutSize, bitmap.width, bitmap.height),
            "记录的尺寸 ${captured.layoutSize} 与截图 ${bitmap.width}x${bitmap.height} 不一致",
        )

        val expectedScrim = OS4_DIM_AMOUNT * os4ScrimFraction(1f - progress)
        val tolerance = 12

        // 0) 真实 transition 给出的 scrim 必须等于几何函数的取值（截图的 scrim 不是另算的一份）。
        assertTrue(
            abs(captured.scrimAlpha - expectedScrim) <= 0.001f,
            "transition 的 scrimFraction 与几何函数不一致：${captured.scrimAlpha} vs $expectedScrim",
        )

        // 1) 卡片四角外侧（左上角 1px 处）露出的是「backdrop + 轻微 scrim」。
        //    这里直接验证了「没有黑闪」：旧行为 dimAmount = 0.5 会把这个像素压掉一半。
        val corner = bitmap.getPixel(1, 1)
        val expectedCorner = blend(captured.backdrop, Color.Black, expectedScrim)
        assertColorClose(corner, expectedCorner, tolerance, "左上角的 backdrop + scrim")
        assertTrue(
            red(corner) > red(captured.backdrop.toArgb()) * 0.6f,
            "scrim 过重（看起来就是黑闪）：corner=#${Integer.toHexString(corner)}",
        )

        // 2) 屏幕左侧（卡片已经滑走的区域）是「上一页 + 轻微 scrim」——证明卡片确实右移了。
        val leftArea = bitmap.getPixel((bitmap.width * 0.2f).roundToInt(), (bitmap.height * 0.85f).roundToInt())
        val expectedLeft = blend(captured.previousPage, Color.Black, expectedScrim)
        assertColorClose(leftArea, expectedLeft, tolerance, "左侧露出的上一页")

        // 3) 卡片内部（右侧下部的空白页背景）是当前页的原色：卡片在 scrim 之上，没有被压暗。
        val cardArea = bitmap.getPixel((bitmap.width * 0.85f).roundToInt(), (bitmap.height * 0.85f).roundToInt())
        assertColorClose(cardArea, captured.currentPage, 4, "卡片内部的页背景")

        // 4) 真正校验变换：卡片左边缘必须落在几何函数算出来的位置（±3px）。
        val geometry = os4ExitCardGeometry(
            progress = progress,
            widthPx = captured.layoutSize.width.toFloat(),
            heightPx = captured.layoutSize.height.toFloat(),
        )
        val expectedEdge = captured.layoutSize.width / 2f -
            geometry.scale * captured.layoutSize.width / 2f +
            geometry.translationX
        val measuredEdge = firstPixelMatching(
            bitmap = bitmap,
            y = (bitmap.height * 0.85f).roundToInt(),
            target = captured.currentPage,
            tolerance = 4,
        )
        println("[geometry] expected card left edge=$expectedEdge measured=$measuredEdge")
        assertTrue(measuredEdge >= 0, "没在扫描行上找到卡片左边缘")
        assertTrue(
            abs(measuredEdge - expectedEdge) <= 3f,
            "卡片左边缘与几何函数不一致：expected=$expectedEdge measured=$measuredEdge",
        )
        assertTrue(
            expectedEdge > bitmap.width * 0.3f && expectedEdge < bitmap.width * 0.7f,
            "45% 手势下卡片左边缘应该还在屏幕中部，实际 $expectedEdge",
        )
    }

    /**
     * 按 `NavDisplayLayout` 的绘制顺序叠一帧：backdrop（主题 surface）→ 入页（上一页）→
     * 黑色 scrim（alpha = dimAmount * transition.scrimFraction(下层)）→ 出页卡片。
     *
     * 两个页面的 Modifier 链与 `NavEntryHost` 一致：`transformEntry` 在外层、
     * `absoluteSquircleClip` 在内层，所以圆角会跟着卡片一起缩放。
     */
    @Composable
    private fun Os4PredictiveBackFrame(progress: Float, onFrame: (Frame) -> Unit) {
        val transition = remember { miuixOs4NavTransition(OS4_FALLBACK_CORNER_RADIUS) }
        val radius = OS4_FALLBACK_CORNER_RADIUS
        val backdrop = MiuixTheme.colorScheme.surface
        val currentPage = MiuixTheme.colorScheme.surface
        val previousPage = MiuixTheme.colorScheme.surfaceContainer
        val density = LocalDensity.current

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layoutSize = IntSize(constraints.maxWidth, constraints.maxHeight)
            // 库在预测式返回期间用 gesture != null 判定「跟手驱动」，从而选中 predictivePop 分支。
            val gesture = remember(progress) {
                NavGesture(
                    progress = progress,
                    swipeEdge = NavSwipeEdge.Left,
                    touchY = layoutSize.height / 2f,
                    initialTouchY = layoutSize.height / 2f,
                )
            }
            val upperScope = remember(layoutSize, density, gesture) {
                StageScope(
                    relativeDepth = -progress,
                    layoutSize = layoutSize,
                    gesture = gesture,
                    density = density,
                )
            }
            val lowerScope = remember(layoutSize, density, gesture) {
                StageScope(
                    relativeDepth = 1f - progress,
                    layoutSize = layoutSize,
                    gesture = gesture,
                    density = density,
                )
            }
            val upperTransform = remember(transition, upperScope) {
                with(transition) { Modifier.transformEntry(upperScope) }
            }
            val lowerTransform = remember(transition, lowerScope) {
                with(transition) { Modifier.transformEntry(lowerScope) }
            }
            val scrimAlpha = OS4_DIM_AMOUNT * transition.scrimFraction(lowerScope).coerceIn(0f, 1f)

            SideEffect {
                onFrame(
                    Frame(
                        layoutSize = layoutSize,
                        backdrop = backdrop,
                        previousPage = previousPage,
                        currentPage = currentPage,
                        scrimAlpha = scrimAlpha,
                    ),
                )
            }

            Box(modifier = Modifier.fillMaxSize().background(backdrop)) {
                // 入页：上一页在卡片后面从 0.9 放大回 1.0。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(lowerTransform)
                        .absoluteSquircleClip(radius, radius, radius, radius)
                        .background(previousPage),
                ) {
                    MockForumPage(header = "版块 · 上一页", accent = MiuixTheme.colorScheme.primary)
                }
                // 库在「最上层 entry 之下的整屏」画一个纯黑 scrim，alpha 由 transition 的 scrimFraction 决定。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = scrimAlpha }
                        .background(Color.Black),
                )
                // 出页卡片：右移 + 缩小到 0.92，四角圆角（effects 层负责 clip，这里复用同一半径）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(upperTransform)
                        .absoluteSquircleClip(radius, radius, radius, radius)
                        .background(currentPage),
                ) {
                    MockForumPage(header = "帖子 · 当前页", accent = MiuixTheme.colorScheme.primary)
                }
            }
        }
    }

    /** 一页「像论坛页面」的静态内容：顶栏 + 少量列表卡片，下部留白以便像素采样。 */
    @Composable
    private fun MockForumPage(header: String, accent: Color) {
        val onSurface = MiuixTheme.colorScheme.onSurface
        val subtle = MiuixTheme.colorScheme.onSurfaceVariantSummary
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(20.dp).background(accent, RoundedCornerShape(10.dp)))
                Spacer(modifier = Modifier.width(12.dp))
                BasicText(
                    text = header,
                    style = TextStyle(color = onSurface, fontSize = 18.sp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            repeat(4) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(72.dp)
                        .background(
                            color = MiuixTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        BasicText(
                            text = "帖子标题 ${index + 1}：OS4 卡片式返回",
                            style = TextStyle(color = onSurface, fontSize = 14.sp),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicText(
                            text = "作者 · 刚刚",
                            style = TextStyle(color = subtle, fontSize = 11.sp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    /** 固定深度的 [NavTransitionScope]：模拟「预测式返回进行到 progress」。 */
    private class StageScope(
        override val relativeDepth: Float,
        override val layoutSize: IntSize,
        override val gesture: NavGesture?,
        override val density: Density,
        override val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) : NavTransitionScope {
        override val role: NavRole = if (relativeDepth <= 0f) NavRole.Top else NavRole.Covered
        override val change: NavChange = NavChange.Pop
    }

    private fun newSizeMatches(size: IntSize, width: Int, height: Int): Boolean =
        size.width == width && size.height == height

    private fun blend(base: Color, overlay: Color, alpha: Float): Color = Color(
        red = base.red * (1f - alpha) + overlay.red * alpha,
        green = base.green * (1f - alpha) + overlay.green * alpha,
        blue = base.blue * (1f - alpha) + overlay.blue * alpha,
        alpha = 1f,
    )

    private fun red(argb: Int): Int = (argb shr 16) and 0xFF

    private fun assertColorClose(actual: Int, expected: Color, tolerance: Int, label: String) {
        val expectedArgb = expected.toArgb()
        val dr = abs(red(actual) - red(expectedArgb))
        val dg = abs(((actual shr 8) and 0xFF) - ((expectedArgb shr 8) and 0xFF))
        val db = abs((actual and 0xFF) - (expectedArgb and 0xFF))
        assertTrue(
            dr <= tolerance && dg <= tolerance && db <= tolerance,
            "$label 颜色不符：actual=#${Integer.toHexString(actual)} expected=#${Integer.toHexString(expectedArgb)} " +
                "d=($dr,$dg,$db) tolerance=$tolerance",
        )
    }

    /** 沿一行从左往右找第一个「接近 target 色」的像素，返回 x；找不到返回 -1。 */
    private fun firstPixelMatching(bitmap: Bitmap, y: Int, target: Color, tolerance: Int): Int {
        val expected = target.toArgb()
        for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val dr = abs(red(pixel) - red(expected))
            val dg = abs(((pixel shr 8) and 0xFF) - ((expected shr 8) and 0xFF))
            val db = abs((pixel and 0xFF) - (expected and 0xFF))
            if (dr <= tolerance && dg <= tolerance && db <= tolerance) return x
        }
        return -1
    }
}
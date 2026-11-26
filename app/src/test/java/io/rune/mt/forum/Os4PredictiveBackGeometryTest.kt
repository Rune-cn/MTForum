// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.compose.ui.unit.dp
import io.rune.mt.forum.ui.animation.predictiveback.OS4_CARD_SHADOW_ELEVATION
import io.rune.mt.forum.ui.animation.predictiveback.OS4_DIM_AMOUNT
import io.rune.mt.forum.ui.animation.predictiveback.OS4_ENTER_MIN_SCALE
import io.rune.mt.forum.ui.animation.predictiveback.OS4_ENTER_PARALLAX_FRACTION
import io.rune.mt.forum.ui.animation.predictiveback.OS4_EXIT_MIN_SCALE
import io.rune.mt.forum.ui.animation.predictiveback.OS4_FALLBACK_CORNER_RADIUS
import io.rune.mt.forum.ui.animation.predictiveback.Os4ExitCardGeometry
import io.rune.mt.forum.ui.animation.predictiveback.os4CardCornerRadius
import io.rune.mt.forum.ui.animation.predictiveback.os4CardShadowElevation
import io.rune.mt.forum.ui.animation.predictiveback.os4EnterPageGeometry
import io.rune.mt.forum.ui.animation.predictiveback.os4ExitCardGeometry
import io.rune.mt.forum.ui.animation.predictiveback.os4ScrimFraction
import io.rune.mt.forum.ui.animation.predictiveback.os4SmoothStep
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * OS4 卡片式返回的几何单测。
 *
 * 这些断言检查的是**几何不变量**（端点、单调性、像素对齐、无跳变、scrim 起手为 0），
 * 不是「把当前实现的数字抄一遍」：
 *
 * * 端点必须落在设计值上（出页 1.0 → [OS4_EXIT_MIN_SCALE]，入页 [OS4_ENTER_MIN_SCALE] → 1.0）；
 * * 整段扫一遍：缩放单调不增/不减、位移单调、逐帧增量不超过线性步长的 2 倍（= 没有跳变）；
 * * 位移必须像素对齐（缩放后左边缘落在整数像素上，否则圆角卡片边缘会抖）；
 * * 出页在 progress = 1 时必须完全离开屏幕（左边缘 >= 屏宽），否则提交时会「卡半截」；
 * * scrim 在起手（下层 depth = 1）必须为 0——这正是旧行为「黑闪」的来源
 *   （库默认 `scrimFraction = depth` 在起手处等于 1，即立刻 0.5 的黑幕）。
 */
class Os4PredictiveBackGeometryTest {

    private val widthPx = 1080f
    private val heightPx = 2340f

    /** 缩放按整像素取整，误差上限是 1px 对应的缩放量。 */
    private val scaleTolerance = 1f / widthPx

    /** 6dp 的阴影高度折算成像素（测试只关心「0 / 满值」两种取值）。 */
    private val shadowPx = 12f

    @Test
    fun `exit card is settled and flat at progress zero`() {
        val g = os4ExitCardGeometry(progress = 0f, widthPx = widthPx, heightPx = heightPx)
        assertEquals(1f, g.scale, scaleTolerance, "静止时出页必须整屏，不能带缩放")
        assertEquals(0f, g.translationX, 0.001f, "静止时不能有位移")
        assertEquals(0f, g.translationY, 0.001f, "静止时不能有纵向位移")
        assertEquals(0f, os4CardShadowElevation(0f, shadowPx), 0.001f, "静止时不能有阴影（否则屏幕边缘会出现暗边）")
    }

    @Test
    fun `exit card ends fully clear of the screen at the design scale`() {
        val g = os4ExitCardGeometry(progress = 1f, widthPx = widthPx, heightPx = heightPx)
        assertEquals(OS4_EXIT_MIN_SCALE, g.scale, scaleTolerance, "拖满时缩放应落在设计值")
        assertTrue(abs(g.scale - 0.92f) <= 0.02f, "设计区间是 0.9~0.94，实际 ${g.scale}")
        assertEquals(widthPx, g.translationX, 0.001f, "拖满时位移必须正好一屏宽")
        assertTrue(leftEdge(g) >= widthPx, "拖满时卡片左边缘必须离开屏幕，实际 ${leftEdge(g)}")
        assertEquals(shadowPx, os4CardShadowElevation(1f, shadowPx), 0.001f, "拖满时阴影应完全升起")
    }

    @Test
    fun `mid gesture keeps the card partially on screen and pixel aligned`() {
        val g = os4ExitCardGeometry(progress = 0.45f, widthPx = widthPx, heightPx = heightPx)
        assertTrue(g.scale > OS4_EXIT_MIN_SCALE && g.scale < 1f, "中段缩放应在两端之间，实际 ${g.scale}")
        assertTrue(g.translationX > 0f && g.translationX < widthPx, "中段位移应在屏幕内，实际 ${g.translationX}")
        assertTrue(leftEdge(g) > 0f && leftEdge(g) < widthPx, "中段卡片应还压在屏幕上，实际 ${leftEdge(g)}")
        assertPixelAligned(g)
    }

    @Test
    fun `exit progress is monotonic and never jumps`() {
        val steps = 100
        var previous = os4ExitCardGeometry(0f, widthPx, heightPx)
        for (i in 1..steps) {
            val p = i / steps.toFloat()
            val g = os4ExitCardGeometry(p, widthPx, heightPx)
            assertTrue(g.scale.isFinite() && g.translationX.isFinite(), "p=$p 出现非有限值")
            assertTrue(g.scale <= previous.scale + 0.0001f, "缩放在 p=$p 处回弹了")
            assertTrue(g.translationX >= previous.translationX - 0.001f, "位移在 p=$p 处回退")
            assertTrue(
                g.scale in OS4_EXIT_MIN_SCALE - scaleTolerance..1f + scaleTolerance,
                "p=$p 缩放越界：${g.scale}",
            )
            assertPixelAligned(g)
            // 线性步长 + 像素对齐带来的最多 1px 取整误差。
            val expectedShiftStep = widthPx / steps
            assertTrue(
                g.translationX - previous.translationX <= expectedShiftStep + 1.5f,
                "p=$p 位移单帧跳变过大：${g.translationX - previous.translationX}",
            )
            val expectedScaleStep = (1f - OS4_EXIT_MIN_SCALE) / steps
            assertTrue(
                previous.scale - g.scale <= expectedScaleStep + 2f * scaleTolerance,
                "p=$p 缩放单帧跳变过大：${previous.scale - g.scale}",
            )
            previous = g
        }
    }

    @Test
    fun `exit card clamps progress outside the gesture range`() {
        val atZero = os4ExitCardGeometry(0f, widthPx, heightPx)
        val before = os4ExitCardGeometry(-0.4f, widthPx, heightPx)
        assertEquals(atZero.scale, before.scale, 0.0001f)
        assertEquals(atZero.translationX, before.translationX, 0.001f)

        val atOne = os4ExitCardGeometry(1f, widthPx, heightPx)
        val after = os4ExitCardGeometry(1.7f, widthPx, heightPx)
        assertEquals(atOne.scale, after.scale, 0.0001f)
        assertEquals(atOne.translationX, after.translationX, 0.001f)
    }

    @Test
    fun `incoming page grows from the design scale to full size`() {
        val start = os4EnterPageGeometry(revealed = 0f, widthPx = widthPx, heightPx = heightPx)
        assertEquals(OS4_ENTER_MIN_SCALE, start.scale, scaleTolerance, "起手时入页应缩到设计值")
        assertEquals(
            -widthPx * OS4_ENTER_PARALLAX_FRACTION,
            start.translationX,
            1f,
            "起手时入页应带固定视差",
        )

        val end = os4EnterPageGeometry(revealed = 1f, widthPx = widthPx, heightPx = heightPx)
        assertEquals(1f, end.scale, scaleTolerance, "露出完成时必须正好整屏")
        assertEquals(0f, end.translationX, 0.001f, "露出完成时视差必须归零（否则会跳一下）")

        val mid = os4EnterPageGeometry(revealed = 0.45f, widthPx = widthPx, heightPx = heightPx)
        assertTrue(mid.scale > OS4_ENTER_MIN_SCALE && mid.scale < 1f, "中段入页缩放应在两端之间")
        assertTrue(
            mid.translationX > -widthPx * OS4_ENTER_PARALLAX_FRACTION && mid.translationX < 0f,
            "中段视差应介于 0 与起手值之间",
        )
        assertPixelAligned(mid.scale, mid.translationX)
    }

    @Test
    fun `incoming page progress is monotonic and bounded`() {
        val steps = 100
        var previous = os4EnterPageGeometry(0f, widthPx, heightPx)
        for (i in 1..steps) {
            val revealed = i / steps.toFloat()
            val g = os4EnterPageGeometry(revealed, widthPx, heightPx)
            assertTrue(g.scale >= previous.scale - 0.0001f, "入页缩放在 $revealed 处回退")
            assertTrue(g.scale <= 1f + scaleTolerance, "入页缩放不能超过 1.0，实际 ${g.scale}")
            assertTrue(g.translationX >= previous.translationX - 0.001f, "入页视差在 $revealed 处倒退")
            assertTrue(
                g.translationX >= -widthPx * OS4_ENTER_PARALLAX_FRACTION - 1f,
                "入页视差不能超过设计幅度",
            )
            assertPixelAligned(g.scale, g.translationX)
            previous = g
        }
    }

    @Test
    fun `scrim is off at gesture start so there is no black flash`() {
        // 起手时「下层」= 完全被盖住（depth = 1）：旧行为在这里就是 0.5 的黑幕。
        assertEquals(0f, os4ScrimFraction(1f), 0.0001f, "手势一按下不能立刻压黑")

        // 提交末尾：下层完全露出（depth = 0）时也不能还压着黑，否则最后一帧会「暗一下再亮」。
        assertEquals(0f, os4ScrimFraction(0f), 0.0001f, "完全露出时 scrim 必须收干净")

        // 中段达到上限，配合 OS4_DIM_AMOUNT 才是「轻微压暗」。
        assertEquals(1f, os4ScrimFraction(0.5f), 0.0001f, "手势中段 scrim 应到位")
    }

    @Test
    fun `scrim stays inside a subtle range`() {
        assertTrue(OS4_DIM_AMOUNT in 0.1f..0.25f, "OS4 的 dim 上限应是轻微压暗，实际 $OS4_DIM_AMOUNT")
        val steps = 200
        var peak = 0f
        var previous = os4ScrimFraction(1f)
        for (i in 0..steps) {
            val depthBelow = i / steps.toFloat()
            val f = os4ScrimFraction(depthBelow)
            assertTrue(f in 0f..1f, "scrim 比例越界：depthBelow=$depthBelow -> $f")
            val dim = OS4_DIM_AMOUNT * f
            assertTrue(dim <= 0.25f + 1e-6f, "实际压暗 $dim 已经不能算「轻微」")
            if (i <= steps / 2) {
                // 露出越来越多（depthBelow 变小）时先淡入。
                assertTrue(f >= previous - 0.0001f, "scrim 淡入段在 depthBelow=$depthBelow 处回退")
            } else {
                // 露出过半之后淡出，保证新页在提交时恢复原亮度。
                assertTrue(f <= previous + 0.0001f, "scrim 淡出段在 depthBelow=$depthBelow 处反升")
            }
            previous = f
            if (f > peak) peak = f
        }
        assertEquals(1f, peak, 0.0001f, "scrim 应在中段达到上限")
    }

    @Test
    fun `card corner radius follows the device and falls back to the design value`() {
        assertEquals(OS4_FALLBACK_CORNER_RADIUS, os4CardCornerRadius(0.dp), "设备不报圆角时必须兜底")
        assertEquals(24.dp, os4CardCornerRadius(24.dp), "设备有圆角时用设备值")
        assertEquals(32.dp, os4CardCornerRadius(32.dp), "设备值正好等于兜底值时保持设备值")
        assertTrue(
            OS4_FALLBACK_CORNER_RADIUS.value in 28f..32f,
            "兜底圆角应在 28~32dp 的设计区间，实际 ${OS4_FALLBACK_CORNER_RADIUS.value}",
        )
    }

    @Test
    fun `rtl mirrors the card direction`() {
        val ltrExit = os4ExitCardGeometry(1f, widthPx, heightPx, directionSign = 1f)
        val rtlExit = os4ExitCardGeometry(1f, widthPx, heightPx, directionSign = -1f)
        assertEquals(widthPx, ltrExit.translationX, 0.001f)
        assertEquals(-widthPx, rtlExit.translationX, 0.001f)

        val ltrEnter = os4EnterPageGeometry(0f, widthPx, heightPx, directionSign = 1f)
        val rtlEnter = os4EnterPageGeometry(0f, widthPx, heightPx, directionSign = -1f)
        assertEquals(-ltrEnter.translationX, rtlEnter.translationX, 0.001f)
    }

    @Test
    fun `shadow elevation is a subtle design constant`() {
        assertTrue(
            OS4_CARD_SHADOW_ELEVATION.value in 1f..10f,
            "卡片阴影应「轻微」，实际 ${OS4_CARD_SHADOW_ELEVATION.value}dp",
        )
    }

    @Test
    fun `shadow elevation only toggles once per gesture`() {
        // Android 的阴影贴图按 (outline, elevation) 缓存：每帧改高度 = 每帧重算整屏阴影贴图。
        // 所以整段手势里高度只允许在 0 / 满值之间切换一次。
        val values = (0..200).map { i -> os4CardShadowElevation(i / 200f, shadowPx) }.toSet()
        assertEquals(setOf(0f, shadowPx), values, "阴影高度不应随进度连续变化")
        assertEquals(0f, os4CardShadowElevation(0f, shadowPx), 0.001f, "静止时必须为 0")
        assertEquals(shadowPx, os4CardShadowElevation(0.001f, shadowPx), 0.001f, "一旦离位就应满阴影")
        // 越界进度不能出现第三种取值（负进度视为静止）。
        assertEquals(0f, os4CardShadowElevation(-0.5f, shadowPx), 0.001f)
        assertEquals(shadowPx, os4CardShadowElevation(1.5f, shadowPx), 0.001f)
    }

    @Test
    fun `smooth step is a proper interpolator`() {
        assertEquals(0f, os4SmoothStep(0f, 1f, -1f), 0.0001f)
        assertEquals(0f, os4SmoothStep(0f, 1f, 0f), 0.0001f)
        assertEquals(1f, os4SmoothStep(0f, 1f, 1f), 0.0001f)
        assertEquals(1f, os4SmoothStep(0f, 1f, 2f), 0.0001f)
        assertEquals(0.5f, os4SmoothStep(0f, 1f, 0.5f), 0.0001f)
        // 退化区间不能除零。
        assertEquals(1f, os4SmoothStep(2f, 2f, 3f), 0.0001f)
        assertEquals(0f, os4SmoothStep(2f, 2f, 1f), 0.0001f)
    }

    /** 缩放后的左边缘必须落在整数像素上（否则圆角卡片的抗锯齿边缘会抖）。 */
    private fun assertPixelAligned(g: Os4ExitCardGeometry) = assertPixelAligned(g.scale, g.translationX)

    private fun assertPixelAligned(scale: Float, translationX: Float) {
        val left = widthPx / 2f - scale * widthPx / 2f + translationX
        assertTrue(
            abs(left - left.roundToInt()) <= 0.01f,
            "左边缘未像素对齐：left=$left scale=$scale tx=$translationX",
        )
    }

    private fun leftEdge(g: Os4ExitCardGeometry): Float =
        widthPx / 2f - g.scale * widthPx / 2f + g.translationX
}
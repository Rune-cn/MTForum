// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.animation.predictiveback

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.navDirectionalTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/** 出页卡片的最小缩放（拖满时）。 */
internal const val OS4_EXIT_MIN_SCALE = 0.92f

/** 入页的最小缩放（手势刚起手时）。 */
internal const val OS4_ENTER_MIN_SCALE = 0.9f

/** 入页的视差幅度，相对屏宽。 */
internal const val OS4_ENTER_PARALLAX_FRACTION = 0.06f

/** OS4 返回时 scrim 的 alpha 上限（全屏纯黑层），0.2 是「轻微压暗」而不是黑幕。 */
internal const val OS4_DIM_AMOUNT = 0.2f

/** 设备不上报圆角（或为 0）时的四角圆角兜底值。 */
internal val OS4_FALLBACK_CORNER_RADIUS: Dp = 32.dp

/** 卡片阴影的抬升高度；静止时不画（否则屏幕边缘会出现一圈暗边）。 */
internal val OS4_CARD_SHADOW_ELEVATION: Dp = 6.dp

/**
 * 出页卡片的阴影高度（px）。
 *
 * Android 的阴影贴图按 (outline, elevation) 缓存：如果让高度跟着进度每帧变化，822x1782
 * 的卡片阴影会**每帧重算一次贴图**，手势期间很容易掉帧。所以这里只做「离位即满阴影」的
 * 二值切换——切换发生在 p=0 那一帧，那时卡片还没有从屏幕边缘露出任何一条缝，看不出来；
 * 之后整个手势期间阴影高度恒定，贴图只需算一次。
 */
internal fun os4CardShadowElevation(progress: Float, maxElevationPx: Float): Float =
    if (progress <= 0f) 0f else maxElevationPx

private val Os4CardMotion = NavMotion(
    // 临界阻尼（库默认 dampingRatio = 1）：卡片滑走不允许过冲/回弹；
    // stiffness 320 对应 0.5s 满步约 0.33s，比库默认（146）更跟手。
    commit = NavSettleSpec.Spring(stiffness = 320f),
    cancel = NavSettleSpec.Spring(stiffness = 1500f),
    programmatic = NavSettleSpec.Tween(durationMillis = 300, easing = FastOutExtraSlowIn),
)

/**
 * 出页卡片在进度 [progress]（0 = 停在顶部，1 = 完全剥离）时的几何量，单位为像素。
 *
 * 平移量 [translationX] 线性映射整屏宽度：库的驱动浮点本身就是按手势进度 snap 的，
 * 线性才能跟手 1:1（与 [NavTransitions.MiuixDefault] 的约定一致）；缩放同理线性。
 * 平移做像素对齐，避免卡片圆角边缘在合成时抖动。
 */
internal data class Os4ExitCardGeometry(
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
)

/** 入页（上一页）在露出比例 [revealed]（0 = 完全被卡片盖住，1 = 完全露出）时的几何量。 */
internal data class Os4EnterPageGeometry(
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
)

internal fun os4ExitCardGeometry(
    progress: Float,
    widthPx: Float,
    heightPx: Float,
    directionSign: Float = 1f,
    pivotX: Float = 0.5f,
    pivotY: Float = 0.5f,
): Os4ExitCardGeometry {
    val p = progress.coerceIn(0f, 1f)
    val scale = snapScaleToPixelExtent(1f + (OS4_EXIT_MIN_SCALE - 1f) * p, widthPx)
    return Os4ExitCardGeometry(
        scale = scale,
        translationX = snapTranslationToPixelEdge(
            translation = directionSign * p * widthPx,
            scale = scale,
            extent = widthPx,
            pivotFraction = pivotX,
        ),
        translationY = snapTranslationToPixelEdge(
            translation = 0f,
            scale = scale,
            extent = heightPx,
            pivotFraction = pivotY,
        ),
    )
}

internal fun os4EnterPageGeometry(
    revealed: Float,
    widthPx: Float,
    heightPx: Float,
    directionSign: Float = 1f,
    pivotX: Float = 0.5f,
    pivotY: Float = 0.5f,
): Os4EnterPageGeometry {
    val r = revealed.coerceIn(0f, 1f)
    val scale = snapScaleToPixelExtent(OS4_ENTER_MIN_SCALE + (1f - OS4_ENTER_MIN_SCALE) * r, widthPx)
    return Os4EnterPageGeometry(
        scale = scale,
        translationX = snapTranslationToPixelEdge(
            translation = -directionSign * (1f - r) * widthPx * OS4_ENTER_PARALLAX_FRACTION,
            scale = scale,
            extent = widthPx,
            pivotFraction = pivotX,
        ),
        translationY = snapTranslationToPixelEdge(
            translation = 0f,
            scale = scale,
            extent = heightPx,
            pivotFraction = pivotY,
        ),
    )
}

/**
 * scrim 的曲线，参数是**下层**（被露出的那页）的相对深度：0 = 完全露出，1 = 完全被盖住。
 *
 * 起手处必须为 0（手势一开始就压黑 = 黑闪），露出到 42% 时淡入到位，之后到提交前再淡出：
 * 卡片完全滑走时新页应当是原本的亮度，否则最后一帧会「暗一下再亮」。
 */
internal fun os4ScrimFraction(depthBelow: Float): Float {
    val revealed = (1f - depthBelow).coerceIn(0f, 1f)
    val rise = os4SmoothStep(0f, 0.42f, revealed)
    val fallOff = 1f - os4SmoothStep(0.62f, 1f, revealed)
    return (rise * fallOff).coerceIn(0f, 1f)
}

/** 设备圆角优先，缺失时退化到 [OS4_FALLBACK_CORNER_RADIUS]，避免出现直角卡片。 */
internal fun os4CardCornerRadius(deviceCornerRadius: Dp): Dp =
    if (deviceCornerRadius > 0.dp) deviceCornerRadius else OS4_FALLBACK_CORNER_RADIUS

internal fun os4SmoothStep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * OS4（HyperOS 4 / Miuix）风格的预测式返回：卡片式剥离。
 *
 * 设计目标（用户反馈「OS4 下的预测式返回不太符合 UI 设计」）：
 *
 * * **出页**像一张居中的卡片：右移的同时缩小到 [OS4_EXIT_MIN_SCALE]，四角圆角（由
 *   `NavDisplayEffects` 负责 clip，半径取设备圆角或 [OS4_FALLBACK_CORNER_RADIUS]），
 *   离位后带一点阴影，浮在被轻微压暗的背景之上；
 * * **入页**（上一页）在卡片后面从 [OS4_ENTER_MIN_SCALE] 放大回 1.0，并带
 *   [OS4_ENTER_PARALLAX_FRACTION] 的轻微视差，不是「硬推」；
 * * **背景暗化**交给 `NavDisplayEffects.dimAmount`（一个纯黑全屏 scrim），OS4 把上限
 *   压到 [OS4_DIM_AMOUNT]——原来的 0.5 在浅色 backdrop 上会读成「黑闪」；
 * * **全程跟手**：所有视觉都只是单一驱动浮点 (`NavTransitionScope.relativeDepth`) 的纯函数，
 *   拖拽、提交、取消共用同一套映射，所以在 gesture start / commit / cancel 处没有跳变。
 *
 * 与库自带 [NavTransitions.MiuixDefault] 的差别：后者是纯平移（出页整屏右移、入页 25%
 * 视差 + 0.9 alpha），没有缩放、没有四角圆角、也没有阴影——这正是 OS4 下不好看的原因。
 * 同一个 transition 同时用于 `pop` 与 `predictivePop`，所以「手势返回」和「返回键 / 系统返回」
 * 的观感一致；前进（push）仍然沿用库默认动画，缩小了改动面。
 *
 * @param cornerRadius 只用于阴影轮廓，必须与 `NavDisplayEffects.cornerClipRadius` 取值一致，
 *   否则阴影会从圆角处露出来。
 */
internal fun miuixOs4NavTransition(cornerRadius: Dp): NavTransition {
    val shadowShape = RoundedCornerShape(cornerRadius)
    val card: NavTransition = navGraphicsTransition(
        opaqueDepth = 1f,
        motion = Os4CardMotion,
        scrim = { scope -> os4ScrimFraction(scope.relativeDepth) },
    ) { scope ->
        val depth = scope.relativeDepth
        val widthPx = scope.layoutSize.width.toFloat()
        val heightPx = scope.layoutSize.height.toFloat()
        val directionSign = if (scope.layoutDirection == LayoutDirection.Rtl) -1f else 1f
        transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0.5f)
        if (depth <= 0f) {
            val geometry = os4ExitCardGeometry(
                // d 在 [-1, 0]：出页进度是 -d（1 = 完全剥离）。注意不是 topProgress(d)，后者是
                // 「进入」进度，静止时等于 1。
                progress = -depth,
                widthPx = widthPx,
                heightPx = heightPx,
                directionSign = directionSign,
            )
            scaleX = geometry.scale
            scaleY = geometry.scale
            translationX = geometry.translationX
            translationY = geometry.translationY
            alpha = 1f
            shape = shadowShape
            shadowElevation = os4CardShadowElevation(
                progress = -depth,
                maxElevationPx = with(this) { OS4_CARD_SHADOW_ELEVATION.toPx() },
            )
        } else {
            val geometry = os4EnterPageGeometry(
                revealed = 1f - depth,
                widthPx = widthPx,
                heightPx = heightPx,
                directionSign = directionSign,
            )
            scaleX = geometry.scale
            scaleY = geometry.scale
            translationX = geometry.translationX
            translationY = geometry.translationY
            // 不透明度保持 1：卡片式返回靠缩放 + 阴影分层，不需要半透明（透明会透出 backdrop 发黑）。
            alpha = 1f
            // 清掉「本层之前作为出页卡片」时留下的阴影：graphicsLayer 属性会跨帧保留，
            // 而库默认动画的 covered 分支只写 translation/alpha，不会帮我们复位。
            shadowElevation = 0f
        }
    }
    return navDirectionalTransition(
        push = NavTransitions.MiuixDefault,
        pop = card,
        predictivePop = card,
    )
}
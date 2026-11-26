// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.animation.predictiveback

import androidx.compose.ui.unit.Dp
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackAnimation
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackExitDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

/**
 * 把用户选的「预测式返回动画」映射成 miuix-nav 的 [NavTransition]。
 *
 * ⚠️ `None` 的语义（A4）：
 *
 * 之前 `AppNavContainer` 在 `None` 模式下注册了 entry 级
 * `NavigationBackHandler(isBackEnabled = true)`，它会在手指按下时就抢走手势，
 * 于是 [NoPredictiveBackTransition] 精心写的「grab anchor」逻辑成了**死代码**。
 * 拦截器拆掉之后，`None` 由库的 `predictivePop` 分支（`isGestureDriven(scope)`）
 * 真正驱动，恢复到它本来的语义：
 *
 * * 拖动期间：`noPredictiveVisualProgress()` 让视觉停在「抓取锚点」上——**不跟手**
 *   （这就是「无预测式预览动画」的字面语义）；
 * * 抬起（Commit）：从锚点起播 `NoPredictivePop`，不再是一次莫名其妙的 programmatic pop；
 * * 取消（Cancel）：从锚点回弹，不会把「进行中的 push」打断成半截。
 *
 * 如果产品上要求「`None` 也要能拉一半看到预览」，只需把下面这行改成
 * `PredictiveBackAnimation.None -> NavTransitions.MiuixDefault`（跟手位移 + 边缘阴影），
 * 代价是 `NoPredictiveBackTransition` 变成不可达代码。
 *
 * ⚠️ `MIUIX` 的语义（OS4 卡片式返回）：
 *
 * OS3（经典）下 `MIUIX` 仍是库自带的纯平移 `NavTransitions.MiuixDefault`（OS3 观感不变）；
 * OS4 下换成 [miuixOs4NavTransition]：出页缩成圆角卡片滑走、入页放大回 1.0、背景轻微压暗。
 * 开关由调用方传入 [useMiuixOs4]（与 `AppTheme.useMiuixOs4` 同一平台守卫），
 * **不是**新增的第六个设置项——设置页仍然是原来的五个选项。
 */
fun appNavTransition(
    animation: PredictiveBackAnimation,
    exitDirection: PredictiveBackExitDirection,
    useMiuixOs4: Boolean = false,
    cornerRadius: Dp = OS4_FALLBACK_CORNER_RADIUS,
): NavTransition = when (animation) {
    // 保留 NoPredictiveBackTransition：尊重“无预测式预览动画”的选项语义（不跟手，仅松手后播放）。
    // 「None 也要跟手预览」的开关：把本行替换为 `NavTransitions.MiuixDefault`。
    PredictiveBackAnimation.None -> NoPredictiveBackTransition
    PredictiveBackAnimation.MIUIX -> if (useMiuixOs4) {
        miuixOs4NavTransition(cornerRadius)
    } else {
        // OS3 保持库默认：整屏平移 + 25% 视差，OS3 用户观感不变。
        NavTransitions.MiuixDefault
    }
    PredictiveBackAnimation.AOSP -> AospNavTransition
    PredictiveBackAnimation.Scale -> scaleNavTransition(exitDirection)
    PredictiveBackAnimation.Classic -> ClassicNavTransition
}

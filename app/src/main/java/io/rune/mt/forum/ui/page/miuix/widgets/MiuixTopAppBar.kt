// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.ui.theme.AppTheme
import io.rune.mt.forum.ui.theme.getMiuixAppBarColor
import io.rune.mt.forum.ui.theme.miuixBlurEffect
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Resolves the layer a top app bar may sample.
 *
 * The Miuix example (`example/.../utils/PageUtils.kt`) samples a *content* layer: `MainPage`
 * records `backdrop` on the `Box` **inside** the `Scaffold` content and hands it to
 * `BlurredBar(backdrop, …)`, so the bar samples the page body and never the bar itself. This app
 * follows the same rule through `MiuixPageChrome.contentBackdropModifier`.
 *
 * The rule needs a guard because the app also has a **host-level** layer
 * (`MiuixComponentScope.pageBackdrop`, recorded by `SettingsActivity` around the whole navigation
 * tree so popups can sample it). That layer contains every bar, so sampling it from a bar makes
 * the bar source a RenderNode that contains the bar: the self-sampling cycle that overflowed the
 * render thread (`docs/HANDOVER_CN.md` §11 / §15.9). A caller that wires it into the bar slot by
 * mistake now gets the opaque fallback instead of a crash.
 *
 * @param contentBackdrop Layer recorded around the page body (the only legal source).
 * @param hostBackdrop The app-wide layer recorded around the navigation tree; never legal here.
 * @return [contentBackdrop], or `null` when it is absent or is the host layer.
 */
internal fun barBackdrop(
    contentBackdrop: LayerBackdrop?,
    hostBackdrop: LayerBackdrop?,
): LayerBackdrop? = when {
    contentBackdrop == null -> null
    // Defence in depth: refuse the layer that contains the bar.
    contentBackdrop === hostBackdrop -> null
    else -> contentBackdrop
}

/**
 * Top app bar that renders the OS4 glass bar or the classic OS3 bar.
 *
 * Resolves its style from [AppTheme.useMiuixOs4] so callers do not pass a flag; passing
 * [useOs4] explicitly only overrides the ambient value for special cases.
 *
 * [backdrop] is the page-chrome *content* layer. Whatever arrives — the ambient default or an
 * explicit `chrome.backdrop` — goes through [barBackdrop], so the bar can never sample the
 * host-level layer that contains it.
 */
@Composable
internal fun MiuixTopAppBar(
    title: String,
    isContentScrolled: Boolean? = null,
    modifier: Modifier = Modifier,
    useOs4: Boolean = LocalMiuixComponentScope.current.useOs4 || AppTheme.useMiuixOs4,
    backdrop: LayerBackdrop? = LocalMiuixComponentScope.current.contentBackdrop,
    scrollBehavior: ScrollBehavior? = null,
    persistentProgressiveBlur: Boolean = true,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val hasContentScroll by remember(scrollBehavior) {
        derivedStateOf { (scrollBehavior?.state?.contentOffset ?: 0f) < 0f }
    }
    val materialVisible = isContentScrolled ?: hasContentScroll
    // One decision point, applied to the default and to every explicit caller.
    val barSamplingLayer = barBackdrop(backdrop, LocalMiuixComponentScope.current.pageBackdrop)
    if (useOs4) {
        if (persistentProgressiveBlur && barSamplingLayer != null) {
            MiuixProgressiveAppBarSurface(backdrop = barSamplingLayer, modifier = modifier) {
                GlassTopAppBar(
                    title = title,
                    isContentScrolled = materialVisible,
                    backdrop = barSamplingLayer,
                    style = GlassDefaults.style(AppTheme.isDark),
                    scrollBehavior = scrollBehavior,
                    backgroundAlpha = { 0f },
                    navigationIcon = navigationIcon,
                    actions = actions,
                )
            }
        } else {
            GlassTopAppBar(
                title = title,
                isContentScrolled = materialVisible,
                modifier = modifier,
                progressiveBlur = true,
                backdrop = barSamplingLayer,
                style = GlassDefaults.style(AppTheme.isDark),
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions,
            )
        }
    } else {
        TopAppBar(
            title = title,
            // Same filtered layer as the OS4 branches: the classic blur path must not sample a
            // scene that contains the bar either.
            modifier = modifier.miuixBlurEffect(barSamplingLayer),
            color = barSamplingLayer.getMiuixAppBarColor(),
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

/**
 * A spatial gradient over the sampled scene for the OS4 bar.
 *
 * The gradient stays put instead of fading out when the list cannot scroll, which is what
 * keeps the bar legible over a short page.
 */
@Composable
internal fun MiuixProgressiveAppBarSurface(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val renderedBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density)
    val blurColors = BlurColors(
        blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.3f))),
    )
    Box(modifier = modifier) {
        if (backdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .layout { measurable, constraints ->
                        val bandHeight = (topInset + TopAppBarDefaults.CollapsedHeight.toPx() + 20.dp.toPx()) * 1.2f
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = bandHeight.toInt(), maxHeight = bandHeight.toInt()),
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
                    }
                    .layerBackdrop(renderedBackdrop)
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
                        blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
                        colors = blurColors,
                    ),
            )
        }
        CompositionLocalProvider(
            LocalMiuixTopBarBackdrop provides renderedBackdrop.takeIf { backdrop != null },
        ) {
            content()
        }
    }
}

/**
 * Glass icon button that samples the top app bar band when one is present.
 *
 * Sampling the rendered bar (rather than reapplying the raw scene) keeps the button aligned
 * with the progressive gradient painted behind it.
 */
@Composable
internal fun MiuixTopBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceAlpha: Float = 1f,
    content: @Composable () -> Unit,
) {
    // Branch on the ambient style. The previous version called GlassIconButton unconditionally,
    // which painted an OS4 glass pill on the classic OS3 bar — the mismatch that made the thread
    // overflow button look out of place.
    if (AppTheme.useMiuixOs4) {
        val backdrop = LocalMiuixTopBarBackdrop.current
        GlassIconButton(
            onClick = onClick,
            modifier = modifier,
            inheritTopBarMaterial = backdrop == null,
            backdrop = backdrop,
            surfaceAlpha = surfaceAlpha,
            content = content,
        )
    } else {
        IconButton(onClick = onClick, modifier = modifier, content = content)
    }
}

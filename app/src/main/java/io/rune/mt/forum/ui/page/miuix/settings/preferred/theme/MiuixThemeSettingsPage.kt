// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings.preferred.theme

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackAnimation
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackExitDirection
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.main.settings.preferred.theme.ThemeSettingsAction
import io.rune.mt.forum.ui.page.main.settings.preferred.theme.ThemeSettingsViewModel
import io.rune.mt.forum.ui.page.main.widget.card.ColorSwatchPreview
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixSwitchWidget
import io.rune.mt.forum.ui.theme.material.PresetColors
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixSpinnerPreference
import io.rune.mt.forum.ui.theme.isOs4PlatformSupported
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixThemeSettingsPage(
    viewModel: ThemeSettingsViewModel = koinViewModel(),
) {
    val navigator = LocalNavigator.current
    val uiState by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        Timber.i("Theme settings page shown")
    }


    // OS4 is a glass-only presentation: without render effects it degrades to OS3, so the
    // selector is hidden rather than offering a style the device cannot draw.
    val supportsOs4 = isOs4PlatformSupported
    val useOs4 = supportsOs4 && uiState.useMiuixOs4

    // Drive the backdrop from the same condition that decides the style. Using useBlur alone
    // left it null when the stored blur toggle was off, so the OS4 bar had nothing to sample
    // The scaffold derives the backdrop from the resolved style, so the glass back button
    // always has a bar to sample.
    MiuixPageScaffold(
        title = stringResource(R.string.theme_settings),
        chrome = rememberMiuixPageChrome(uiState.useBlur),
        navigationIcon = {
            MiuixBackButton(onClick = { navigator.pop() })
        },
    ) {
        item { Spacer(modifier = Modifier.size(12.dp)) }
        // Shell mode: no Google/MIUIX UI switcher (miuix-only repo).
        item { SmallTitle(stringResource(R.string.theme_settings_miuix_ui)) }
        // Light/dark are two preview tiles (the example app's presentation); 跟随系统 stays a
        // switch row inside the card below, so the three modes are all still reachable.
        item {
            ThemeModePreviewRow(
                currentMode = uiState.themeMode,
                onModeChange = { newMode ->
                    viewModel.dispatch(ThemeSettingsAction.SetThemeMode(newMode))
                },
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                ThemeModeFollowSystemRow(
                    currentMode = uiState.themeMode,
                    onModeChange = { newMode ->
                        viewModel.dispatch(ThemeSettingsAction.SetThemeMode(newMode))
                    },
                )
                if (supportsOs4) {
                    MiuixOsVersionWidget(
                        useOs4 = uiState.useMiuixOs4,
                        onUseOs4Change = { viewModel.dispatch(ThemeSettingsAction.SetUseMiuixOs4(it)) },
                    )
                }
                // OS4 forces the floating bottom bar on (see MiuixNavWrapper:
                // useFloatingBottomBar = useOs4 || useAppleFloatingBar), so this preference
                // has no effect there. Hide it instead of showing a switch that does nothing.
                if (!useOs4) {
                    MiuixSwitchWidget(
                        title = stringResource(R.string.theme_settings_use_apple_floating_bar),
                        description = stringResource(R.string.theme_settings_use_apple_floating_bar_desc),
                        checked = uiState.useAppleFloatingBar,
                        onCheckedChange = {
                            viewModel.dispatch(ThemeSettingsAction.SetUseAppleFloatingBar(it))
                        },
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !useOs4) {
                    MiuixSwitchWidget(
                        title = stringResource(R.string.theme_settings_use_blur),
                        description = stringResource(R.string.theme_settings_use_blur_desc),
                        checked = uiState.useBlur,
                        onCheckedChange = { viewModel.dispatch(ThemeSettingsAction.SetUseBlur(it)) },
                    )
                }
                MiuixSwitchWidget(
                    title = stringResource(R.string.theme_settings_miuix_custom_colors),
                    description = stringResource(R.string.theme_settings_miuix_custom_colors_desc),
                    checked = uiState.useMiuixMonet,
                    onCheckedChange = {
                        viewModel.dispatch(ThemeSettingsAction.SetUseMiuixMonet(it))
                    },
                )
                AnimatedVisibility(
                    visible = uiState.useMiuixMonet,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    MiuixSwitchWidget(
                        title = stringResource(R.string.theme_settings_dynamic_color),
                        description = stringResource(R.string.theme_settings_dynamic_color_desc),
                        checked = uiState.useDynamicColor,
                        onCheckedChange = {
                            viewModel.dispatch(ThemeSettingsAction.SetUseDynamicColor(it))
                        },
                    )
                }
                AnimatedVisibility(
                    visible = uiState.useMiuixMonet,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    MiuixPaletteStyleWidget(
                        currentPaletteStyle = uiState.paletteStyle,
                        onPaletteStyleChange = { newStyle ->
                            viewModel.dispatch(ThemeSettingsAction.SetPaletteStyle(newStyle))
                        },
                    )
                }
                AnimatedVisibility(
                    visible = uiState.useMiuixMonet,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    MiuixColorSpecWidget(
                        currentColorSpec = uiState.colorSpec,
                        currentPaletteStyle = uiState.paletteStyle,
                        onColorSpecChange = { newSpec ->
                            viewModel.dispatch(ThemeSettingsAction.SetColorSpec(newSpec))
                        },
                    )
                }
                // Shell: colorful-dialog (dynamic color follow icon) removed.
                // Shell: colorful live-activity follow icon removed.
            }
        }

        item {
            AnimatedVisibility(
                visible = uiState.useMiuixMonet && (!uiState.useDynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S),
                enter = fadeIn(animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)) +
                    expandVertically(animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)) +
                    shrinkVertically(animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)),
            ) {
                Column {
                    SmallTitle(stringResource(R.string.theme_settings_theme_color))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 16.dp),
                        ) {
                            val itemMinWidth = 88.dp
                            val columns = (this.maxWidth / itemMinWidth).toInt().coerceAtLeast(1)
                            val chunkedColors = uiState.availableColors.chunked(columns)

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                chunkedColors.forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        rowItems.forEach { rawColor ->
                                            Box(
                                                modifier = Modifier.weight(1f),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                ColorSwatchPreview(
                                                    rawColor = rawColor,
                                                    currentStyle = uiState.paletteStyle,
                                                    colorSpec = uiState.colorSpec,
                                                    textStyle = MiuixTheme.textStyles.footnote1,
                                                    textColor = MiuixTheme.colorScheme.onSurface,
                                                    isSelected =
                                                        uiState.seedColor == rawColor.color,
                                                ) {
                                                    viewModel.dispatch(
                                                        ThemeSettingsAction.SetSeedColor(
                                                            rawColor.color,
                                                            uiState.useDynamicColor &&
                                                                Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                                                                // If useDynamicColor is true but availableColors is PresetColors, the wallpaperColors have no valid values
                                                                uiState.availableColors != PresetColors,
                                                        ),
                                                    )
                                                }
                                            }
                                        }

                                        val remaining = columns - rowItems.size
                                        if (remaining > 0) {
                                            repeat(remaining) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Predictive Back Section
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            item { SmallTitle(stringResource(R.string.theme_settings_predictive_back)) }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    MiuixPredictiveBackAnimationWidget(
                        currentAnimation = uiState.predictiveBackAnimation,
                        onAnimationChange = { newAnim ->
                            viewModel.dispatch(ThemeSettingsAction.SetPredictiveBackAnimation(newAnim))
                        },
                    )

                    AnimatedVisibility(
                        visible = uiState.predictiveBackAnimation == PredictiveBackAnimation.Scale,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        MiuixPredictiveBackExitDirectionWidget(
                            currentDirection = uiState.predictiveBackExitDirection,
                            onDirectionChange = {
                                viewModel.dispatch(ThemeSettingsAction.SetPredictiveBackExitDirection(it))
                            },
                        )
                    }
                }
            }
        }

        // Shell mode: package-icons setting (prefer system icon) removed.
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

/**
 * MiuixSpinnerPreference widget for selecting Predictive Back Animation
 */
@Composable
private fun MiuixPredictiveBackAnimationWidget(
    modifier: Modifier = Modifier,
    currentAnimation: PredictiveBackAnimation,
    onAnimationChange: (PredictiveBackAnimation) -> Unit,
) {
    val options = remember { PredictiveBackAnimation.entries }

    // Map entries to their string resources within the Composable context
    val spinnerEntries = options.map { anim ->
        val title = when (anim) {
            PredictiveBackAnimation.None -> stringResource(R.string.theme_settings_predictive_back_animation_none)
            PredictiveBackAnimation.AOSP -> stringResource(R.string.theme_settings_predictive_back_animation_aosp)
            PredictiveBackAnimation.MIUIX -> stringResource(R.string.theme_settings_predictive_back_animation_miuix)
            PredictiveBackAnimation.Scale -> stringResource(R.string.theme_settings_predictive_back_animation_scale)
            PredictiveBackAnimation.Classic -> stringResource(R.string.theme_settings_predictive_back_animation_ksu_classic)
        }
        DropdownItem(title = title)
    }

    val selectedIndex = remember(currentAnimation, options) {
        options.indexOf(currentAnimation).coerceAtLeast(0)
    }

    MiuixSpinnerPreference(
        modifier = modifier,
        title = stringResource(id = R.string.theme_settings_predictive_back_animation),
        items = spinnerEntries,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { newIndex ->
            val newAnim = options[newIndex]
            if (currentAnimation != newAnim) {
                onAnimationChange(newAnim)
            }
        },
    )
}

/**
 * MiuixSpinnerPreference widget for selecting Predictive Back Exit Direction
 */
@Composable
private fun MiuixPredictiveBackExitDirectionWidget(
    modifier: Modifier = Modifier,
    currentDirection: PredictiveBackExitDirection,
    onDirectionChange: (PredictiveBackExitDirection) -> Unit,
) {
    val options = remember { PredictiveBackExitDirection.entries }

    // Map entries to their string resources within the Composable context
    val spinnerEntries = options.map { dir ->
        val title = when (dir) {
            PredictiveBackExitDirection.FOLLOW_GESTURE -> stringResource(R.string.theme_settings_predictive_back_exit_direction_follow_gesture)
            PredictiveBackExitDirection.ALWAYS_RIGHT -> stringResource(R.string.theme_settings_predictive_back_exit_direction_always_right)
            PredictiveBackExitDirection.ALWAYS_LEFT -> stringResource(R.string.theme_settings_predictive_back_exit_direction_always_left)
        }
        DropdownItem(title = title)
    }

    val selectedIndex = remember(currentDirection, options) {
        options.indexOf(currentDirection).coerceAtLeast(0)
    }

    MiuixSpinnerPreference(
        modifier = modifier,
        title = stringResource(id = R.string.theme_settings_predictive_back_exit_direction),
        items = spinnerEntries,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { newIndex ->
            val newDir = options[newIndex]
            if (currentDirection != newDir) {
                onDirectionChange(newDir)
            }
        },
    )
}

/**
 * Miuix UI version selector: OS3 (classic) or OS4 (glass).
 *
 * Mirrors the two presentations the rest of the UI family ships, so switching here changes the
 * top app bars, navigation bar and back buttons together instead of per-screen.
 */
@Composable
private fun MiuixOsVersionWidget(
    useOs4: Boolean,
    onUseOs4Change: (Boolean) -> Unit,
) {
    MiuixSpinnerPreference(
        title = stringResource(R.string.theme_settings_miuix_os_version),
        items = listOf(
            DropdownItem(title = stringResource(R.string.theme_settings_miuix_os3)),
            DropdownItem(title = stringResource(R.string.theme_settings_miuix_os4)),
        ),
        selectedIndex = if (useOs4) 1 else 0,
        onSelectedIndexChange = { index ->
            val newUseOs4 = index == 1
            if (useOs4 != newUseOs4) {
                onUseOs4Change(newUseOs4)
            }
        },
    )
}

/**
 * Theme Engine selection widget using MiuixSpinnerPreference, following the provided pattern.
 * Simplified version without data class and icons.
 *
 * @param currentThemeIsMiuix True if MIUIX theme is selected, false if Google theme is selected.
 * @param onThemeChange Callback when the selection changes. Boolean parameter indicates new selection (true = MIUIX).
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun MiuixThemeEngineWidget(
    modifier: Modifier = Modifier,
    currentThemeIsMiuix: Boolean,
    onThemeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    val themeOptions = remember {
        mapOf(
            true to R.string.theme_settings_miuix_ui, // Key = true -> MIUIX UI string resource
            false to R.string.theme_settings_google_ui, // Key = false -> Google UI string resource
        )
    }

    // Convert map entries to List<DropdownItem> for MiuixSpinnerPreference.
    // Ensure the order matches the keys: index 0 = true, index 1 = false.
    val spinnerEntries = remember(themeOptions) {
        themeOptions.entries.sortedByDescending { it.key }.map { entry ->
            DropdownItem(
                title = context.getString(entry.value),
            )
        }
    }

    // Determine selected index based on currentThemeIsMiuix state.
    // Index 0 corresponds to true (MIUIX), Index 1 corresponds to false (Google).
    val selectedIndex = remember(currentThemeIsMiuix) {
        if (currentThemeIsMiuix) 0 else 1
    }

    MiuixSpinnerPreference(
        modifier = modifier,
        title = stringResource(id = R.string.theme_settings_ui_engine),
        // summary = spinnerEntries[selectedIndex].title,
        items = spinnerEntries,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { newIndex ->
            // Convert index back to boolean key (0 -> true, 1 -> false)
            val newModeIsMiuix = themeOptions.keys.sortedDescending().elementAt(newIndex)
            if (currentThemeIsMiuix != newModeIsMiuix) {
                onThemeChange(newModeIsMiuix)
            }
        },
    )
}

/**
 * MiuixSpinnerPreference widget for selecting the Palette Style.
 */
@Composable
fun MiuixPaletteStyleWidget(
    modifier: Modifier = Modifier,
    currentPaletteStyle: PaletteStyle,
    onPaletteStyleChange: (PaletteStyle) -> Unit,
) {
    val options = remember { PaletteStyle.entries }
    val spinnerEntries = remember(options) {
        options.map { DropdownItem(title = it.displayName) }
    }
    val selectedIndex = remember(currentPaletteStyle, options) {
        options.indexOf(currentPaletteStyle).coerceAtLeast(0)
    }

    MiuixSpinnerPreference(
        modifier = modifier,
        title = stringResource(id = R.string.theme_settings_palette_style),
        items = spinnerEntries,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { newIndex ->
            val newStyle = options[newIndex]
            if (currentPaletteStyle != newStyle) {
                onPaletteStyleChange(newStyle)
            }
        },
    )
}

/**
 * MiuixSpinnerPreference widget for selecting the Theme Color Spec.
 * Shows the effective spec and restricts choices to those supported by the palette style.
 */
@Composable
fun MiuixColorSpecWidget(
    modifier: Modifier = Modifier,
    currentColorSpec: ThemeColorSpec,
    currentPaletteStyle: PaletteStyle,
    onColorSpecChange: (ThemeColorSpec) -> Unit,
) {
    val availableSpecs = currentPaletteStyle.supportedSpecs
    val activeSpec = currentPaletteStyle.effectiveSpec(currentColorSpec)
    val descriptionText = if (availableSpecs.size == 1) {
        stringResource(
            id = when (activeSpec) {
                ThemeColorSpec.SPEC_2026 -> R.string.theme_settings_color_spec_only_2026
                else -> R.string.theme_settings_color_spec_only_2021
            },
        )
    } else {
        null
    }

    val spinnerEntries = remember(availableSpecs) {
        availableSpecs.map { DropdownItem(title = it.displayName) }
    }

    val selectedIndex = remember(activeSpec, availableSpecs) {
        availableSpecs.indexOf(activeSpec).coerceAtLeast(0)
    }

    MiuixSpinnerPreference(
        modifier = modifier,
        title = stringResource(id = R.string.theme_settings_color_spec),
        summary = descriptionText,
        enabled = availableSpecs.size > 1,
        items = spinnerEntries,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { newIndex ->
            val selectedSpec = availableSpecs[newIndex]
            if (currentColorSpec != selectedSpec) {
                onColorSpecChange(selectedSpec)
            }
        },
    )
}

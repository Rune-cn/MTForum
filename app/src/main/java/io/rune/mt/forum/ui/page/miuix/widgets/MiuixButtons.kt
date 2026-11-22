// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.rune.mt.forum.R
import io.rune.mt.forum.ui.icons.AppMiuixIcons
import io.rune.mt.forum.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.LocalContentColor
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward

/**
 * A standardized back button for the application, styled for MIUIX.
 *
 * Follows the active Miuix UI version automatically: OS4 renders a glass icon button that
 * samples the top app bar band, OS3 renders the classic icon button. Callers do not pass a
 * style flag, so both presentations stay in sync from one switch.
 *
 * @param onClick The lambda to be executed when the button is clicked.
 * @param modifier The Modifier to be applied to this button.
 * @param icon The vector asset to be displayed inside the button. Defaults to a back arrow.
 * @param iconTint The tint to be applied to the icon. Defaults to the current content color.
 * @param contentDescription The content description for accessibility.
 */
@Composable
fun MiuixBackButton(
    modifier: Modifier = Modifier,
    icon: ImageVector = AppMiuixIcons.Back,
    iconTint: Color = LocalContentColor.current,
    contentDescription: String = stringResource(id = R.string.back),
    onClick: () -> Unit,
) {
    if (LocalMiuixComponentScope.current.useOs4 || AppTheme.useMiuixOs4) {
        MiuixTopBarIconButton(onClick = onClick, modifier = modifier) {
            // OS4 pairs the glass button with the glass icon set; the classic arrow keeps
            // the OS3 glyph set so the two styles stay visually distinct.
            Icon(
                imageVector = if (icon === AppMiuixIcons.Back) MiuixIcons.Glass.ChevronBackward else icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
            )
        }
    }
}

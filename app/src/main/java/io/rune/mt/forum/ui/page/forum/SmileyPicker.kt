// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.rune.mt.forum.data.forum.model.ForumSmilies
import io.rune.mt.forum.data.forum.model.ForumSmiley
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Smiley picker for the reply composer.
 *
 * Tapping a smiley appends its BBCode token (`[#呵呵]`) to the message, which is exactly what the
 * Discuz editor does and what the server expects; images load from the site CDN.
 */
@Composable
internal fun SmileyPicker(
    onPick: (ForumSmiley) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember { ForumSmilies.groups }
    var selected by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEachIndexed { index, group ->
                val active = index == selected
                Text(
                    text = group.label,
                    fontSize = 13.sp,
                    color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selected = index }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(40.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(groups.getOrNull(selected)?.smilies.orEmpty()) { smiley ->
                AsyncImage(
                    model = smiley.url,
                    contentDescription = smiley.code,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onPick(smiley) },
                )
            }
        }
    }
}

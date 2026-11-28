// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun MiuixBlockingLoadingDialog(visible: Boolean, text: String) {
    MiuixStyleDialog(show = visible, onDismissRequest = {}) {
        BackHandler {
            // Block input while the operation is running.
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfiniteProgressIndicator()
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = text)
        }
    }
}

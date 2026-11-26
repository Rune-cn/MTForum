// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose a new thread (`forum.php?mod=post&action=newthread&fid=`).
 *
 * The body is BBCode, exactly like the reply composer, because that is what the site's own editor
 * posts. Images are attached inline (multipart `Filedata`), which is how the mobile form works.
 */
@Composable
fun NewThreadPage(
    fid: Int,
    forumName: String,
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: NewThreadViewModel = koinViewModel(),
) {
    val chrome = rememberMiuixPageChrome(enableBlur)
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris -> viewModel.attachImages(context, uris) }

    LaunchedEffect(fid) { viewModel.open(fid, forumName) }
    LaunchedEffect(state.successTid, state.successMessage) {
        if (state.successTid != null || state.successMessage != null) {
            android.widget.Toast.makeText(context, "发布成功", android.widget.Toast.LENGTH_SHORT).show()
            navigator.pop()
        }
    }

    MiuixPageScaffold(
        title = "发表主题",
        chrome = chrome,
        outerPadding = outerPadding,
        horizontalPadding = 12.dp,
        verticalPadding = 12.dp,
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
    ) {
        if (state.isLoadingForm) {
            item { ForumLoadingCard() }
            return@MiuixPageScaffold
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = forumName.ifBlank { "版块 $fid" },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("标题", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextField(
                        value = state.title,
                        onValueChange = viewModel::updateTitle,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("正文（支持 [b] [i] [quote] 与表情）", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextField(
                        value = state.message,
                        onValueChange = viewModel::updateMessage,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        singleLine = false,
                        minLines = 8,
                    )

                    if (state.images.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items = state.images, key = { it.uri.toString() }) { image ->
                                Box {
                                    AsyncImage(
                                        model = image.uri,
                                        contentDescription = image.fileName,
                                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    TextButton(
                                        text = "×",
                                        onClick = { viewModel.removeImage(image.uri) },
                                        modifier = Modifier.align(Alignment.TopEnd),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            text = if (pickerOpen) "收起表情" else "表情",
                            onClick = { pickerOpen = !pickerOpen },
                        )
                        TextButton(text = "添加图片", onClick = { pickImages.launch("image/*") })
                    }
                    if (pickerOpen) {
                        SmileyPicker(onPick = { smiley -> viewModel.updateMessage(state.message + smiley.code) })
                    }

                    state.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(text = it, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(text = "取消", onClick = { navigator.pop() })
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { viewModel.submit(context) },
                            enabled = state.canSubmit,
                        ) {
                            if (state.isSubmitting) {
                                InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("发布")
                        }
                    }
                }
            }
        }
    }
}

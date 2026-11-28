// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.rune.mt.forum.R
import io.rune.mt.forum.domain.forum.model.CommentFilterRule
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleError
import io.rune.mt.forum.domain.forum.model.CommentFilterRuleType
import io.rune.mt.forum.domain.forum.model.MAX_COMMENT_FILTER_PATTERN_LENGTH
import io.rune.mt.forum.domain.forum.model.MAX_COMMENT_FILTER_RULES
import io.rune.mt.forum.ui.navigation.LocalNavigator
import io.rune.mt.forum.ui.page.forum.ForumCommentFilterViewModel
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixBackButton
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixConfirmDialog
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixHintTextField
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixNavigationItemWidget
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixPageScaffold
import io.rune.mt.forum.ui.page.miuix.widgets.MiuixSwitchWidget
import io.rune.mt.forum.ui.page.miuix.widgets.rememberMiuixPageChrome
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The 评论屏蔽 settings screen: the master switch, the optional "也过滤楼主" opt-in, and the rule
 * list (add / edit / delete). Rules are stored in the app settings DataStore by
 * [ForumCommentFilterViewModel]; the actual hiding happens in the thread page (see
 * `ForumViewModel.threadDisplay`).
 */
@Composable
fun ForumCommentFilterPage(
    enableBlur: Boolean,
    outerPadding: PaddingValues,
    viewModel: ForumCommentFilterViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CommentFilterRule?>(null) }
    var pattern by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(CommentFilterRuleType.KEYWORD) }
    var dialogError by remember { mutableStateOf<CommentFilterRuleError?>(null) }
    var pendingDelete by remember { mutableStateOf<CommentFilterRule?>(null) }

    fun openAdd() {
        editing = null
        pattern = ""
        type = CommentFilterRuleType.KEYWORD
        dialogError = null
        showEditor = true
    }

    fun openEdit(rule: CommentFilterRule) {
        editing = rule
        pattern = rule.pattern
        type = rule.type
        dialogError = null
        showEditor = true
    }

    MiuixPageScaffold(
        title = stringResource(R.string.forum_comment_filter_title),
        chrome = rememberMiuixPageChrome(enableBlur),
        navigationIcon = { MiuixBackButton(onClick = { navigator.pop() }) },
        outerPadding = outerPadding,
    ) {
        item { Spacer(Modifier.size(12.dp)) }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixSwitchWidget(
                    title = stringResource(R.string.forum_comment_filter_master),
                    description = stringResource(R.string.forum_comment_filter_master_desc),
                    checked = state.masterEnabled,
                    onCheckedChange = viewModel::setMasterEnabled,
                )
                MiuixSwitchWidget(
                    title = stringResource(R.string.forum_comment_filter_filter_op),
                    description = stringResource(R.string.forum_comment_filter_filter_op_desc),
                    enabled = state.masterEnabled,
                    checked = state.filterOp,
                    onCheckedChange = viewModel::setFilterOp,
                )
            }
        }
        item { SmallTitle(stringResource(R.string.forum_comment_filter_rules)) }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = stringResource(R.string.forum_comment_filter_add),
                    description = stringResource(R.string.forum_comment_filter_pattern_hint),
                    onClick = { openAdd() },
                )
            }
        }
        if (state.rules.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.forum_comment_filter_empty),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        items(items = state.rules, key = { it.id }) { rule ->
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openEdit(rule) }
                        .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.pattern,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = typeLabel(rule.type),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.forum_comment_filter_delete),
                        onClick = { pendingDelete = rule },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    // Add / edit dialog. Validation runs in the repository at save time; the dialog stays open and
    // shows the localized reason until the pattern is accepted.
    WindowDialog(show = showEditor, onDismissRequest = { showEditor = false }) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    if (editing == null) R.string.forum_comment_filter_add_title
                    else R.string.forum_comment_filter_edit_title,
                ),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            MiuixHintTextField(
                value = pattern,
                onValueChange = { pattern = it },
                labelText = stringResource(R.string.forum_comment_filter_pattern_hint),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterTypeChip(
                    text = stringResource(R.string.forum_comment_filter_type_keyword),
                    selected = type == CommentFilterRuleType.KEYWORD,
                    onClick = { type = CommentFilterRuleType.KEYWORD },
                )
                FilterTypeChip(
                    text = stringResource(R.string.forum_comment_filter_type_regex),
                    selected = type == CommentFilterRuleType.REGEX,
                    onClick = { type = CommentFilterRuleType.REGEX },
                )
            }
            dialogError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = filterErrorText(error),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showEditor = false },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        scope.launch {
                            val id = editing?.id
                            val error = if (id == null) {
                                viewModel.addRule(pattern, type)
                            } else {
                                viewModel.updateRule(id, pattern, type)
                            }
                            if (error == null) {
                                showEditor = false
                            } else {
                                dialogError = error
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.forum_comment_filter_save))
                }
            }
        }
    }

    MiuixConfirmDialog(
        visible = pendingDelete != null,
        title = stringResource(R.string.forum_comment_filter_delete_title),
        message = stringResource(R.string.forum_comment_filter_delete_message),
        confirmText = stringResource(R.string.forum_comment_filter_delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDelete?.let { viewModel.deleteRule(it.id) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    )
}

@Composable
private fun typeLabel(type: CommentFilterRuleType): String = when (type) {
    CommentFilterRuleType.KEYWORD -> stringResource(R.string.forum_comment_filter_type_keyword)
    CommentFilterRuleType.REGEX -> stringResource(R.string.forum_comment_filter_type_regex)
}

@Composable
private fun filterErrorText(error: CommentFilterRuleError): String = when (error) {
    CommentFilterRuleError.BLANK_PATTERN -> stringResource(R.string.forum_comment_filter_error_blank)
    CommentFilterRuleError.PATTERN_TOO_LONG ->
        stringResource(R.string.forum_comment_filter_error_too_long, MAX_COMMENT_FILTER_PATTERN_LENGTH)

    CommentFilterRuleError.INVALID_REGEX -> stringResource(R.string.forum_comment_filter_error_invalid_regex)
    CommentFilterRuleError.TOO_MANY_RULES ->
        stringResource(R.string.forum_comment_filter_error_too_many, MAX_COMMENT_FILTER_RULES)

    CommentFilterRuleError.STORAGE_FAILED -> stringResource(R.string.forum_comment_filter_error_storage)
}

@Composable
private fun FilterTypeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(text = text) }
    } else {
        TextButton(text = text, onClick = onClick)
    }
}

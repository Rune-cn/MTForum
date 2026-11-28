// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.rune.mt.forum.R
import top.yukonga.miuix.kmp.basic.TextButton

/** Test tag of the composer toolbar's `[list]` action. */
internal const val COMPOSER_TOOLBAR_LIST_TAG = "composer_toolbar_list"

/** Test tag of the composer toolbar's `[code]` action. */
internal const val COMPOSER_TOOLBAR_CODE_TAG = "composer_toolbar_code"

/** Test tag of the composer toolbar's preview toggle (shared by both composers). */
internal const val COMPOSER_TOOLBAR_PREVIEW_TAG = "composer_toolbar_preview"

/**
 * The BBCode toolbar shared by the two composers that speak BBCode — the private-message box
 * ([ForumConversationPage]) and the new-thread editor ([NewThreadPage]).
 *
 * Both fields are `TextFieldValue`-backed and both send BBCode as plain text for the site to
 * render, so the buttons and the caret semantics are identical; only the image action differs
 * (a PM picks one host image, the new-thread editor has its own 图床/附件 chooser). Keeping one
 * composable means a tag added here appears in both editors instead of drifting apart.
 *
 * The wrappers (`B`/`I`/`U`/引用/链接) insert an **empty pair with the caret in the middle**;
 * 列表 inserts a `[list]` + `[*]` skeleton and 代码 a `[code]` block on its own lines (see
 * [ComposerText]). [image] is an optional slot so a host can drop in its own image button without
 * duplicating the rest of the row.
 */
@Composable
internal fun ComposerBbcToolbar(
    onWrapTag: (String) -> Unit,
    onInsertList: () -> Unit,
    onInsertCode: () -> Unit,
    previewOpen: Boolean,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
    image: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BbcToolbarButton(stringResource(R.string.pm_toolbar_bold)) { onWrapTag("b") }
        BbcToolbarButton(stringResource(R.string.pm_toolbar_italic)) { onWrapTag("i") }
        BbcToolbarButton(stringResource(R.string.pm_toolbar_underline)) { onWrapTag("u") }
        BbcToolbarButton(stringResource(R.string.pm_toolbar_quote)) { onWrapTag("quote") }
        BbcToolbarButton(stringResource(R.string.pm_toolbar_link)) { onWrapTag("url") }
        TextButton(
            text = stringResource(R.string.composer_toolbar_list),
            onClick = onInsertList,
            modifier = Modifier.testTag(COMPOSER_TOOLBAR_LIST_TAG),
            minHeight = 40.dp,
        )
        TextButton(
            text = stringResource(R.string.forum_body_code_label),
            onClick = onInsertCode,
            modifier = Modifier.testTag(COMPOSER_TOOLBAR_CODE_TAG),
            minHeight = 40.dp,
        )
        if (image != null) image.invoke(this)
        TextButton(
            text = if (previewOpen) {
                stringResource(R.string.pm_preview_hide)
            } else {
                stringResource(R.string.pm_preview_show)
            },
            onClick = onTogglePreview,
            modifier = Modifier.testTag(COMPOSER_TOOLBAR_PREVIEW_TAG),
            minHeight = 40.dp,
        )
    }
}

/** One compact toolbar chip. */
@Composable
private fun BbcToolbarButton(label: String, onClick: () -> Unit) {
    TextButton(text = label, onClick = onClick, minHeight = 40.dp)
}

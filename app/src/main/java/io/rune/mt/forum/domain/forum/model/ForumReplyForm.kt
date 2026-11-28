// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * Hidden fields of the site's reply form (`forum.php?mod=post&action=reply&...`).
 *
 * The form is **per-request**: Discuz issues a one-shot [posttime] (double-post guard) and, when
 * the form was opened with `&repquote=<pid>`, it also prefills [noticeAuthor] / [noticeTrimStr]
 * so the server itself builds the quoted block. Posting without them is rejected with
 * 「含有非法字符」, which is why the client must fetch the form before every reply.
 */
data class ForumReplyForm(
    val formhash: String,
    val posttime: String,
    val noticeAuthor: String? = null,
    val noticeTrimStr: String? = null,
    val fid: Int,
    val tid: Int,
    val page: Int,
)

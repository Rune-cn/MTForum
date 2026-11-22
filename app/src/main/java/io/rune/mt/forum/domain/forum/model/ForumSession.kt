// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

/**
 * Persisted login session.
 *
 * Discuz authenticates purely by the `cQWy_2132_*` cookie set; there is no token API.
 * [cookies] keeps the whole cookie jar as name → raw value so it can be restored verbatim.
 */
data class ForumSession(
    val cookies: Map<String, String> = emptyMap(),
    val user: ForumUser? = null,
) {
    val isLoggedIn: Boolean get() = cookies.any { (k, v) -> k.endsWith("_auth") && v.isNotBlank() }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

/**
 * The site answered with its login form instead of the requested page.
 *
 * Search is the one forum screen the site gates before rendering anything (`search.php` 302s an
 * anonymous visitor to `member.php?mod=logging`), so the caller has to tell "session expired"
 * apart from a plain failure and offer the sign-in card again instead of an empty result list.
 */
class ForumLoginRequiredException : Exception("login required")
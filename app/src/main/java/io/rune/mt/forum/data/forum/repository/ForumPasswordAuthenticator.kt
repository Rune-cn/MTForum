// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.repository

import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import timber.log.Timber

/**
 * Drives the Discuz username/password login form.
 *
 * The repository's cookie entry point is deliberately the only session-facing API, but Discuz
 * also exposes a classic HTML form. This helper performs the two-step handshake (GET formhash,
 * POST credentials) and hands the resulting cookie jar back to
 * [io.rune.mt.forum.domain.forum.repository.ForumRepository.restoreSession].
 * It never logs credentials or cookie values.
 *
 * Verified against the live site: the `loginhash` query parameter the form action carries is
 * not mandatory — both the bare `loginsubmit=yes` endpoint and the hashed one are accepted, and
 * a rejected password answers `200` with a `messagetext` payload rather than an error status.
 * That payload is what [Failure.reason] surfaces, because "200 but no auth cookie" is otherwise
 * indistinguishable from a network hiccup.
 */
class ForumPasswordAuthenticator(
    private val http: ForumHttpClient,
    private val cookieJar: ForumCookieJar,
) {

    /** Outcome of a password login attempt. */
    sealed interface Outcome {
        data class Success(val cookies: Map<String, String>) : Outcome

        /** [reason] is the server's own message when it sent one. */
        data class Failure(val reason: String) : Outcome
    }

    /**
     * Returns the cookie snapshot when the server issued an auth cookie, or null when the form
     * could not be read / the credentials were rejected.
     */
    suspend fun authenticate(username: String, password: String): Outcome {
        cookieJar.clear()
        val loginPage = runCatching { http.getHtml(LOGIN_PAGE) }.getOrNull()
        if (loginPage.isNullOrBlank()) {
            Timber.tag(TAG).i("password login failed: login page unavailable")
            return Outcome.Failure("无法加载登录页，请检查网络")
        }
        val formhash = findFormhash(loginPage)
        if (formhash == null) {
            Timber.tag(TAG).i("password login failed: formhash missing")
            return Outcome.Failure("登录页结构异常，缺少 formhash")
        }
        val fields = mapOf(
            "formhash" to formhash,
            "username" to username,
            "password" to password,
            "cookietime" to COOKIE_TIME,
            "loginfield" to "username",
            "questionid" to "0",
            "answer" to "",
        )
        val response = runCatching { http.postForm(LOGIN_SUBMIT, fields) }.getOrNull()
        val cookies = cookieJar.snapshot()
        val hasAuth = cookies.any { (name, value) -> name.endsWith("_auth") && value.isNotBlank() }
        if (hasAuth) {
            Timber.tag(TAG).i("password login accepted")
            return Outcome.Success(cookies)
        }
        val reason = response?.let { extractMessage(it) } ?: "登录请求失败，请检查网络"
        Timber.tag(TAG).i("password login rejected: %s", reason)
        return Outcome.Failure(reason)
    }

    /** The server answers a rejected login with `<p>登录失败，您还可以尝试 4 次…</p>`. */
    private fun extractMessage(html: String): String =
        MESSAGE.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "登录失败，请检查账号或密码"

    private fun findFormhash(html: String): String? =
        FORM_HASH_NAME_FIRST.find(html)?.groupValues?.getOrNull(1)
            ?: FORM_HASH_VALUE_FIRST.find(html)?.groupValues?.getOrNull(1)
            ?: FORM_HASH_INLINE.find(html)?.groupValues?.getOrNull(1)

    private companion object {
        const val TAG = "ForumPasswordAuth"
        const val LOGIN_PAGE = "member.php?mod=logging&action=login"
        const val LOGIN_SUBMIT =
            "member.php?mod=logging&action=login&loginsubmit=yes&handlekey=login&mobile=2"
        const val COOKIE_TIME = "2592000"

        val MESSAGE = Regex("""<p>([^<]{2,200})<""")

        val FORM_HASH_NAME_FIRST = Regex(
            """<input[^>]*name=["']formhash["'][^>]*value=["']([0-9a-fA-F]+)["']""",
            RegexOption.IGNORE_CASE,
        )
        val FORM_HASH_VALUE_FIRST = Regex(
            """<input[^>]*value=["']([0-9a-fA-F]+)["'][^>]*name=["']formhash["']""",
            RegexOption.IGNORE_CASE,
        )
        val FORM_HASH_INLINE = Regex("""formhash=([0-9a-fA-F]{8})""")
    }
}

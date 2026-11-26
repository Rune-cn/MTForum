// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * In-memory cookie store shared by every forum request.
 *
 * The site authenticates by cookie alone (`cQWy_2132_auth`), so the jar is the session.
 * [snapshot] / [restore] move it to and from persistent storage.
 */
class ForumCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { store[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        // `matches` keeps the session cookie on bbs.binmt.cc: the same jar is also handed to the
        // image loader, and sending the auth cookie to the public CDNs would leak it (and make
        // their shared caches serve session-bound content).
        return store.values.filter { it.expiresAt > now && it.matches(url) }
    }

    fun snapshot(): Map<String, String> = store.mapValues { it.value.value }

    fun restore(cookies: Map<String, String>) {
        cookies.forEach { (name, value) ->
            store[name] = Cookie.Builder()
                .name(name)
                .value(value)
                .domain("bbs.binmt.cc")
                .path("/")
                .build()
        }
    }

    fun setCookie(name: String, value: String) {
        store[name] = Cookie.Builder()
            .name(name)
            .value(value)
            .domain("bbs.binmt.cc")
            .path("/")
            .build()
    }

    fun clear() = store.clear()

    fun has(name: String): Boolean = store.containsKey(name)
}

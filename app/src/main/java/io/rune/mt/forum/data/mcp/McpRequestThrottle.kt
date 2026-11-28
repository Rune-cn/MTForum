// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.mcp

/**
 * Sliding-window request throttle for the loopback MCP endpoint.
 *
 * A local MCP client can legitimately issue a burst (tools/list followed by several tool calls),
 * but an accidental loop should not hammer the forum: [maxRequests] per [windowMillis] is
 * enforced before any repository work happens, and the excess gets HTTP `429`. The clock is
 * injectable so the behaviour is unit-testable without sleeping.
 */
class McpRequestThrottle(
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(windowMillis > 0) { "windowMillis must be positive" }
    }

    private val accepted = ArrayDeque<Long>()

    /** Returns true when the request may proceed; false means the caller answers `429`. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        while (accepted.isNotEmpty() && now - accepted.first() >= windowMillis) {
            accepted.removeFirst()
        }
        if (accepted.size >= maxRequests) return false
        accepted.addLast(now)
        return true
    }

    companion object {
        /** 120 requests per minute is far above any interactive client, far below a runaway loop. */
        const val DEFAULT_MAX_REQUESTS = 120
        const val DEFAULT_WINDOW_MILLIS = 60_000L
    }
}
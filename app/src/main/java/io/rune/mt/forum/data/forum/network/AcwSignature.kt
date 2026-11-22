// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.forum.network

/**
 * Reimplementation of the `acw_sc__v2` anti-bot signature used by bbs.binmt.cc.
 *
 * The server answers unsigned requests with a tiny JS challenger instead of the page.
 * The challenger contains `arg1='<hex>'`; the cookie value is
 * `hexXor(unbox(arg1), KEY)`. See docs/REVERSE_SPEC.md for the full write-up.
 */
object AcwSignature {

    private const val KEY = "3000176000856006061501533003690027800375"

    private val BOX = intArrayOf(
        0xf, 0x23, 0x1d, 0x18, 0x21, 0x10, 0x1, 0x26, 0xa, 0x9, 0x13, 0x1f, 0x28, 0x1b,
        0x16, 0x17, 0x19, 0xd, 0x6, 0xb, 0x27, 0x12, 0x14, 0x8, 0xe, 0x15, 0x20, 0x1a,
        0x2, 0x1e, 0x7, 0x4, 0x11, 0x5, 0x3, 0x1c, 0x22, 0x25, 0xc, 0x24,
    )

    private val ARG1_REGEX = Regex("""arg1='([0-9A-Fa-f]+)'""")

    /** Returns true when [body] is the anti-bot challenger rather than real content. */
    fun isChallenger(body: String): Boolean = body.length < 20_000 && body.contains("arg1=")

    /** Extracts signed cookie value from a challenger page, or null when not a challenger. */
    fun solve(body: String): String? {
        val match = ARG1_REGEX.find(body) ?: return null
        return hexXor(unbox(match.groupValues[1]), KEY)
    }

    private fun unbox(arg: String): String {
        val out = CharArray(arg.length)
        for (i in arg.indices) {
            val c = arg[i]
            for (j in BOX.indices) {
                if (BOX[j] == i + 1) out[j] = c
            }
        }
        return String(out)
    }

    private fun hexXor(a: String, b: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < a.length && i < b.length) {
            val x = Character.digit(a[i], 16) * 16 + Character.digit(a[i + 1], 16)
            val y = Character.digit(b[i], 16) * 16 + Character.digit(b[i + 1], 16)
            sb.append((x xor y).toString(16).padStart(2, '0'))
            i += 2
        }
        return sb.toString()
    }
}

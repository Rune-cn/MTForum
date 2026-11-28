// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.model

import kotlinx.serialization.Serializable

/**
 * The user's board (版块) customisation: the order they arranged and the boards they hid.
 *
 * [orderedFids] is the display order the user chose. It is **not** required to be exhaustive:
 * boards the server adds later are absent, and [arrangeForums] slots them back into their server
 * position (see the merge rule there). [hiddenFids] are dropped from the home grid and the board
 * pickers; they stay listed on the settings page so the user can unhide them.
 */
@Serializable
data class ForumBoardPreferences(
    val orderedFids: List<Int> = emptyList(),
    val hiddenFids: List<Int> = emptyList(),
) {
    val hiddenFidSet: Set<Int> get() = hiddenFids.toSet()
}

/**
 * Applies [prefs] to the site's board list.
 *
 * **Merge rule for boards the server adds later** — *known boards keep the user's order, a board
 * the server adds keeps its server position.* Concretely:
 *  - Start from the server's own order (which is authoritative for "where a board belongs").
 *  - Walk the server list. When a board the user has seen (a fid in [ForumBoardPreferences.orderedFids])
 *    is reached, emit the **next not-yet-emitted known board** in the user's order instead of the
 *    server one — this is what makes the user's reordering take effect.
 *  - A board the user has never seen (no fid in `orderedFids`, i.e. the server added it after the
 *    last reorder) is emitted right where it sits in the server list. Its slot therefore stays the
 *    server's slot, so new boards appear in a sane place instead of being dumped at the top/bottom.
 *  - Finally, hidden boards are removed (unless [includeHidden] is true, as the settings page needs
 *    to keep drawing them to offer the un-hide switch).
 *
 * When `orderedFids` is empty (the user never reordered) the server order is returned untouched.
 * Unknown fids in `orderedFids` (boards the server removed) are ignored. The function is pure and
 * deterministic; `ForumBoardPreferencesTest` pins each branch.
 */
fun arrangeForums(
    serverForums: List<Forum>,
    prefs: ForumBoardPreferences,
    includeHidden: Boolean = false,
): List<Forum> {
    if (serverForums.isEmpty()) return emptyList()
    val hidden = prefs.hiddenFidSet
    val base = if (includeHidden) serverForums else serverForums.filterNot { it.fid in hidden }
    if (prefs.orderedFids.isEmpty()) return base

    // De-duplicate and keep only fids the server still returns.
    val baseFids = base.mapTo(HashSet()) { it.fid }
    val knownOrder = prefs.orderedFids.distinct().filter { it in baseFids }
    if (knownOrder.isEmpty()) return base
    val knownSet = prefs.orderedFids.toSet()
    val byFid = base.associateBy { it.fid }
    val queue = ArrayDeque(knownOrder.mapNotNull { byFid[it] })

    val result = ArrayList<Forum>(base.size)
    val emitted = HashSet<Int>(base.size)
    for (forum in base) {
        if (forum.fid in knownSet) {
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (emitted.add(next.fid)) {
                    result += next
                    break
                }
            }
        } else {
            if (emitted.add(forum.fid)) result += forum
        }
    }
    // Safety net: known boards not reached by the walk (should not happen) keep the user order.
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        if (emitted.add(next.fid)) result += next
    }
    return result
}

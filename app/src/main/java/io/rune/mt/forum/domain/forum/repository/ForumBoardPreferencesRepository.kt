// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.forum.repository

import io.rune.mt.forum.domain.forum.model.ForumBoardPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Device-local store for the board order/hide customisation.
 *
 * Backed by the existing app settings DataStore as one JSON document. The repository is a dumb
 * store: [update] atomically applies the caller's transform, and the merge rule that turns the
 * stored fids into a display list lives in
 * [io.rune.mt.forum.domain.forum.model.arrangeForums] so it can be unit-tested without Android.
 */
interface ForumBoardPreferencesRepository {

    val preferences: Flow<ForumBoardPreferences>

    /** Reads the current value, applies [transform], and writes it back atomically. */
    suspend fun update(transform: (ForumBoardPreferences) -> ForumBoardPreferences)
}

/** A repository that stores nothing, used as the default for callers that do not care. */
object NoopForumBoardPreferencesRepository : ForumBoardPreferencesRepository {
    override val preferences: Flow<ForumBoardPreferences> = flowOf(ForumBoardPreferences())
    override suspend fun update(transform: (ForumBoardPreferences) -> ForumBoardPreferences) = Unit
}

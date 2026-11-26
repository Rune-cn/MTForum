// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import io.rune.mt.forum.data.forum.repository.ForumDraftRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumPasswordAuthenticator
import io.rune.mt.forum.data.forum.repository.ForumRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumSessionStore
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier of the client-local draft store (its own file, separate from app settings). */
private val DRAFT_DATA_STORE = named("ForumDraftDataStore")

val forumModule = module {
    singleOf(::ForumCookieJar)
    singleOf(::ForumHttpClient)
    singleOf(::ForumSessionStore)
    singleOf(::ForumPasswordAuthenticator)
    singleOf(::ForumRepositoryImpl) { bind<ForumRepository>() }

    // Drafts are client-local because the site cannot store them (see ForumDraftRepository).
    single<DataStore<Preferences>>(DRAFT_DATA_STORE) {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("forum_drafts") },
        )
    }
    single<ForumDraftRepository> {
        ForumDraftRepositoryImpl(dataStore = get(DRAFT_DATA_STORE), json = get())
    }
}

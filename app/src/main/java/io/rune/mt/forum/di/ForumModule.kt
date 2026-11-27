// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import io.rune.mt.forum.data.forum.network.ForumHttpGateway
import io.rune.mt.forum.data.forum.repository.ForumDraftRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumDraftServiceImpl
import io.rune.mt.forum.data.forum.repository.ForumPasswordAuthenticator
import io.rune.mt.forum.data.forum.repository.ForumRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumSessionStore
import io.rune.mt.forum.data.image.ForumImageHostClient
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier of the offline draft store, kept as a safety net next to the site drafts. */
private val DRAFT_DATA_STORE = named("ForumDraftDataStore")

val forumModule = module {
    singleOf(::ForumCookieJar)
    singleOf(::ForumHttpClient) { bind<ForumHttpGateway>() }
    singleOf(::ForumSessionStore)
    singleOf(::ForumPasswordAuthenticator)
    singleOf(::ForumRepositoryImpl) { bind<ForumRepository>() }

    // The MT image host (img.binmt.cc) is a **separate service**: its own host, its own token and
    // its own cookie jar, so no forum cookie is ever sent there (rules 14 / 33).
    singleOf(::ForumImageHostClient) { bind<ImageHostGateway>() }

    // Drafts live on the site (the 保存草稿 button's `save=1`); this is the read/write surface.
    singleOf(::ForumDraftServiceImpl) { bind<ForumDraftService>() }

    // The device store is only an offline safety net now (written when a site save fails or the
    // composer is left), so its own DataStore file stays separate from the app settings.
    single<DataStore<Preferences>>(DRAFT_DATA_STORE) {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("forum_drafts") },
        )
    }
    single<ForumDraftRepository> {
        ForumDraftRepositoryImpl(dataStore = get(DRAFT_DATA_STORE), json = get())
    }
}

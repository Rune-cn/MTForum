// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import io.rune.mt.forum.data.forum.network.ForumHttpGateway
import io.rune.mt.forum.data.forum.repository.CommentFilterRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumBoardPreferencesRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumDraftRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumDraftServiceImpl
import io.rune.mt.forum.data.forum.repository.ForumPasswordAuthenticator
import io.rune.mt.forum.data.forum.repository.ForumRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumSearchHistoryRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumSessionStore
import io.rune.mt.forum.data.forum.repository.ForumThreadHistoryRepositoryImpl
import io.rune.mt.forum.data.image.ForumImageHostClient
import io.rune.mt.forum.data.plugin.PluginRepositoryImpl
import io.rune.mt.forum.data.plugin.PluginRunner
import io.rune.mt.forum.data.plugin.PluginScriptStorage
import io.rune.mt.forum.data.plugin.PluginUiBridge
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import io.rune.mt.forum.domain.forum.repository.CommentFilterRepository
import io.rune.mt.forum.domain.forum.repository.ForumBoardPreferencesRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.forum.repository.ForumSearchHistoryRepository
import io.rune.mt.forum.domain.forum.repository.ForumThreadHistoryRepository
import io.rune.mt.forum.domain.plugin.PluginRepository
import io.rune.mt.forum.ui.page.forum.ForumThreadCacheRepository
import io.rune.mt.forum.ui.page.forum.ForumThreadCacheRepositoryImpl
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier of the offline draft store, kept as a safety net next to the site drafts. */
private val DRAFT_DATA_STORE = named("ForumDraftDataStore")

/** Qualifier of the local recent-search list (a convenience, unrelated to the site's search). */
private val SEARCH_HISTORY_DATA_STORE = named("ForumSearchHistoryDataStore")

/** Qualifier of the device-local thread browsing history (never leaves the phone). */
private val THREAD_HISTORY_DATA_STORE = named("ForumThreadHistoryDataStore")

val forumModule = module {
    singleOf(::ForumCookieJar)
    singleOf(::ForumHttpClient) { bind<ForumHttpGateway>() }
    singleOf(::ForumSessionStore)
    singleOf(::ForumPasswordAuthenticator)
    singleOf(::ForumRepositoryImpl) { bind<ForumRepository>() }

    // The MT image host (img.binmt.cc) is a **separate service**: its own host, its own token and
    // its own cookie jar, so no forum cookie is ever sent there (rules 14 / 33).
    //
    // Built explicitly rather than with `singleOf(::ForumImageHostClient)`: its constructor has a
    // `baseUrl: String = …` default, and Koin's reflection-free DSL resolves **every** constructor
    // parameter, so `singleOf` asked the graph for a `java.lang.String` and the app died at the
    // first frame with `No definition found for type 'java.lang.String'` (26.11.10 crash). Keeping
    // the default here makes the host constant come from the class, not from the DI graph.
    single<ImageHostGateway> { ForumImageHostClient() }

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

    // Recent search keywords: device-local shortcuts for the home magnifier, with their own
    // DataStore file so clearing them can never touch drafts or settings.
    single<DataStore<Preferences>>(SEARCH_HISTORY_DATA_STORE) {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("forum_search_history") },
        )
    }
    single<ForumSearchHistoryRepository> {
        ForumSearchHistoryRepositoryImpl(dataStore = get(SEARCH_HISTORY_DATA_STORE), json = get())
    }

    // Thread browsing history: the threads this device opened, with their own DataStore file so
    // clearing them can never touch drafts, recent searches or settings. It is deliberately not
    // tied to the forum session, so guests keep their own list (it is purely local).
    single<DataStore<Preferences>>(THREAD_HISTORY_DATA_STORE) {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("forum_thread_history") },
        )
    }
    single<ForumThreadHistoryRepository> {
        ForumThreadHistoryRepositoryImpl(dataStore = get(THREAD_HISTORY_DATA_STORE), json = get())
    }

    // User customisation of the forum itself, stored as JSON documents in the **app settings**
    // DataStore (not a private one) exactly like the brief asked. `get()` with no qualifier is the
    // settings DataStore; the private stores above are qualified and cannot be picked by
    // accident.
    single<CommentFilterRepository> {
        CommentFilterRepositoryImpl(dataStore = get(), json = get())
    }
    single<ForumBoardPreferencesRepository> {
        ForumBoardPreferencesRepositoryImpl(dataStore = get(), json = get())
    }

    // ---- 插件 (设置 → 插件) -----------------------------------------------------
    //
    // Built explicitly, never with singleOf: every one of these has a constructor parameter the
    // graph cannot guess (a Context, a File, another interface), and Koin's reflection-free DSL
    // resolves **every** parameter, default or not (rule 34).
    //
    // The page id channel is the one bridge between a running script and the navigator: the script
    // publishes history/filter/board/mcp/cache, AppNavContainer resolves the route.
    single { PluginUiBridge() }
    single { PluginScriptStorage(androidContext()) }
    single {
        PluginRunner(
            http = get(),
            forum = get(),
            imageHost = get(),
            storage = get(),
            uiBridge = get(),
            context = androidContext(),
        )
    }
    // Install state, the remote catalogue and the run records live in the **app settings**
    // DataStore (get() with no qualifier), exactly like the comment filter and board prefs: no
    // second store is introduced for a feature that is a preference document.
    single<PluginRepository> {
        PluginRepositoryImpl(dataStore = get(), json = get(), runner = get(), storage = get())
    }

    // 帖子缓存: the device-local copy of opened threads. Its own directory, so 清空 cannot touch
    // drafts, history or settings.
    single<ForumThreadCacheRepository> {
        ForumThreadCacheRepositoryImpl(
            root = File(androidContext().filesDir, "thread_cache"),
            json = get(),
        )
    }
}

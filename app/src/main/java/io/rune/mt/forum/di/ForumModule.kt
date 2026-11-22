// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di

import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.data.forum.network.ForumHttpClient
import io.rune.mt.forum.data.forum.repository.ForumPasswordAuthenticator
import io.rune.mt.forum.data.forum.repository.ForumRepositoryImpl
import io.rune.mt.forum.data.forum.repository.ForumSessionStore
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val forumModule = module {
    singleOf(::ForumCookieJar)
    singleOf(::ForumHttpClient)
    singleOf(::ForumSessionStore)
    singleOf(::ForumPasswordAuthenticator)
    singleOf(::ForumRepositoryImpl) { bind<ForumRepository>() }
}

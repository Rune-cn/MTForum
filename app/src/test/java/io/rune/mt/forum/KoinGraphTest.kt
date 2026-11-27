// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import android.app.Application
import io.rune.mt.forum.di.init.appModules
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import io.rune.mt.forum.domain.forum.repository.ForumDraftRepository
import io.rune.mt.forum.domain.forum.repository.ForumDraftService
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.forum.repository.ForumSearchHistoryRepository
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.InstanceCreationException
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Resolves the **production** Koin graph (`appModules`, exactly what `App.onCreate` loads),
 * not fakes.
 *
 * 26.11.10 shipped with `singleOf(::ForumImageHostClient)`, whose constructor has a
 * `baseUrl: String = "https://img.binmt.cc/"` default. Koin's reflection-free DSL resolves every
 * constructor parameter, so it asked the graph for a `java.lang.String`, found nothing, and the app
 * died on the first frame with
 *
 * ```
 * Could not create instance for '[Singleton: …]'
 * Caused by: No definition found for type 'java.lang.String'
 * ```
 *
 * Every other test in this repo starts its own Koin module with fakes, which is why nothing caught
 * it. This test boots the real module and resolves the definitions a screen can request at
 * startup, so a definition the graph cannot build fails here instead of on a phone.
 *
 * It deliberately does not resolve the whole graph: some singletons (the HTTP stack) build fine but
 * would drag the anti-bot interceptor in, and the point is "is every user-reachable definition
 * constructible", not "is every class instantiable offline".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class KoinGraphTest {

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    private fun bootProductionGraph() {
        runCatching { stopKoin() }
        startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(appModules)
        }
    }

    @Test
    fun `the image host gateway is resolvable from the production graph`() {
        bootProductionGraph()
        val gateway = org.koin.core.context.GlobalContext.get().get<ImageHostGateway>()
        assertNotNull("the composer needs an ImageHostGateway", gateway)
    }

    @Test
    fun `every screen-level definition the app asks for is constructible`() {
        bootProductionGraph()
        val koin = org.koin.core.context.GlobalContext.get()
        // Screens resolve these directly (koinViewModel / injected repositories); a missing
        // constructor argument makes the first frame crash instead of a single screen failing.
        val definitions = listOf(
            "ForumRepository" to runCatching { koin.get<ForumRepository>() },
            "ForumDraftService" to runCatching { koin.get<ForumDraftService>() },
            "ForumDraftRepository" to runCatching { koin.get<ForumDraftRepository>() },
            "ForumSearchHistoryRepository" to runCatching { koin.get<ForumSearchHistoryRepository>() },
            "ImageHostGateway" to runCatching { koin.get<ImageHostGateway>() },
        )
        val failures = definitions.mapNotNull { (name, result) ->
            result.exceptionOrNull()?.let { error ->
                // InstanceCreationException wraps the root "no definition" cause; keep the message.
                "$name: ${(error as? InstanceCreationException)?.cause?.message ?: error.message}"
            }
        }
        org.junit.Assert.assertTrue("production graph cannot build: $failures", failures.isEmpty())
    }
}
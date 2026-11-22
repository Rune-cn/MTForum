// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di

import io.rune.mt.forum.util.cache.AutoCacheSweeper
import io.rune.mt.forum.util.timber.LogController
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val initModule = module {
    singleOf(::LogController) { createdAtStart() }
    singleOf(::AutoCacheSweeper) { createdAtStart() }
}

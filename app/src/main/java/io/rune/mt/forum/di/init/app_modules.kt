// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di.init

import io.rune.mt.forum.di.coreModule
import io.rune.mt.forum.di.forumModule
import io.rune.mt.forum.di.initModule
import io.rune.mt.forum.di.mcpModule
import io.rune.mt.forum.di.networkModule
import io.rune.mt.forum.di.serializationModule
import io.rune.mt.forum.di.settingsModule
import io.rune.mt.forum.di.updateModule
import io.rune.mt.forum.di.viewModelModule

val appModules = listOf(
    viewModelModule,
    serializationModule,
    coreModule,
    forumModule,
    settingsModule,
    networkModule,
    updateModule,
    initModule,
    mcpModule,
)
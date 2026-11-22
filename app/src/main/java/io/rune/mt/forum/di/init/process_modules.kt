// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di.init

import io.rune.mt.forum.di.coreModule
import io.rune.mt.forum.di.serializationModule

val processModules = listOf(
    serializationModule,
    coreModule,
)

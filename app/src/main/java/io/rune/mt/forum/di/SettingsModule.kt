// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.rune.mt.forum.data.settings.local.datastore.AppDataStore
import io.rune.mt.forum.data.settings.provider.ThemeStateProviderImpl
import io.rune.mt.forum.data.settings.repository.AppSettingsRepositoryImpl
import io.rune.mt.forum.domain.settings.provider.ThemeStateProvider
import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.settings.usecase.settings.UpdateSettingUseCase
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val settingsModule = module {
    // DataStore
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            migrations = listOf(
                SharedPreferencesMigration(androidContext(), "app"),
            ),
        ) {
            androidContext().preferencesDataStoreFile("app_settings")
        }
    }

    singleOf(::AppDataStore)

    single<AppSettingsRepository> {
        AppSettingsRepositoryImpl(
            appDataStore = get(),
            appScope = get(named("AppScope")),
        )
    }

    // Providers
    single<ThemeStateProvider> {
        ThemeStateProviderImpl(
            appSettingsRepo = get<AppSettingsRepository>(),
            appScope = get(named("AppScope")),
        )
    }

    // UseCases
    factoryOf(::UpdateSettingUseCase)
}
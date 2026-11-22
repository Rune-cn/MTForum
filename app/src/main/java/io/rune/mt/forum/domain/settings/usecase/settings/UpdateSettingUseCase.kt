// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.settings.usecase.settings

import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.settings.repository.BooleanSetting
import io.rune.mt.forum.domain.settings.repository.IntSetting
import io.rune.mt.forum.domain.settings.repository.StringSetting

class UpdateSettingUseCase(private val appSettingsRepo: AppSettingsRepository) {
    suspend operator fun invoke(setting: BooleanSetting, value: Boolean) {
        appSettingsRepo.putBoolean(setting, value)
    }

    suspend operator fun invoke(setting: StringSetting, value: String) {
        appSettingsRepo.putString(setting, value)
    }

    suspend operator fun invoke(setting: IntSetting, value: Int) {
        appSettingsRepo.putInt(setting, value)
    }
}

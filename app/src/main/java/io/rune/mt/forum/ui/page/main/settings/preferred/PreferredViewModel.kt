// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.settings.repository.BooleanSetting
import io.rune.mt.forum.domain.settings.usecase.settings.UpdateSettingUseCase
import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.checkedVersion
import io.rune.mt.forum.domain.updater.repository.UpdateRepository
import io.rune.mt.forum.ui.page.main.settings.preferred.about.UpdateCheckFeedback
import io.rune.mt.forum.ui.page.main.settings.preferred.about.updateCheckFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class PreferredViewModel(
    appSettingsRepo: AppSettingsRepository,
    private val updateRepo: UpdateRepository,
    private val updateSetting: UpdateSettingUseCase,
) : ViewModel() {

    private val _uiEvents = MutableSharedFlow<PreferredViewEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val uiEvents = _uiEvents.asSharedFlow()

    val state: StateFlow<PreferredViewState> = combine(
        appSettingsRepo.preferencesFlow,
        updateRepo.updateInfoFlow,
        updateRepo.checkState,
    ) { prefs, updateInfo, checkState ->
        PreferredViewState(
            allowInternetAccess = prefs.allowInternetAccess,
            hasUpdate = prefs.allowInternetAccess && (updateInfo?.hasUpdate ?: false),
            remoteVersion = if (prefs.allowInternetAccess) updateInfo?.remoteVersion.orEmpty() else "",
            autoCheckUpdateOnStart = prefs.autoCheckUpdateOnStart,
            useBlur = prefs.useBlur,
            updateInfo = updateInfo,
            checkState = checkState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PreferredViewState(),
    )

    fun dispatch(action: PreferredViewAction) {
        when (action) {
            is PreferredViewAction.ChangeInternetAccess -> viewModelScope.launch {
                updateSetting(BooleanSetting.AllowInternetAccess, action.enabled)
            }

            PreferredViewAction.CheckUpdate -> checkUpdate(force = true)

            is PreferredViewAction.SetAutoCheckUpdateOnStart -> viewModelScope.launch {
                updateSetting(BooleanSetting.AutoCheckUpdateOnStart, action.enabled)
            }

            is PreferredViewAction.SetUseBlur -> viewModelScope.launch {
                updateSetting(BooleanSetting.UiUseBlur, action.enabled)
            }
        }
    }

    /**
     * Runs one check; the loading dialog follows `state.isChecking`, not an event pair, so a
     * cancellation or a thrown repository cannot strand the UI in "检查更新".
     */
    private fun checkUpdate(force: Boolean = false) = viewModelScope.launch {
        Timber.i("Update check requested (force=%s)", force)
        val result = try {
            updateRepo.checkUpdate(force)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.e(error, "Update check failed")
            UpdateCheckResult.Failed(
                reason = error.message ?: error.javaClass.simpleName,
                checkedAt = System.currentTimeMillis(),
            )
        }
        when (val feedback = updateCheckFeedback(result)) {
            UpdateCheckFeedback.ShowUpdateSheet -> {
                Timber.i("Update found: %s", result.checkedVersion)
                _uiEvents.emit(PreferredViewEvent.ShowUpdateSheet)
            }

            is UpdateCheckFeedback.Message -> _uiEvents.emit(
                PreferredViewEvent.ShowMessage(feedback.resId, feedback.args),
            )
        }
    }
}
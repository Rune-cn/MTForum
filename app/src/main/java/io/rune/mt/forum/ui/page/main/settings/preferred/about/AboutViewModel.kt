// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.main.settings.preferred.about

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.settings.repository.BooleanSetting
import io.rune.mt.forum.domain.settings.usecase.settings.UpdateSettingUseCase
import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.checkedVersion
import io.rune.mt.forum.domain.updater.repository.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class AboutViewModel(
    private val context: Context,
    appSettingsRepo: AppSettingsRepository,
    private val updateRepo: UpdateRepository,
    private val updateSetting: UpdateSettingUseCase,
) : ViewModel() {

    private val _uiEvents = MutableSharedFlow<AboutEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val uiEvents = _uiEvents.asSharedFlow()

    private val _appIcon = MutableStateFlow<ImageBitmap?>(null)

    val state: StateFlow<AboutState> = combine(
        appSettingsRepo.preferencesFlow,
        updateRepo.updateInfoFlow,
        updateRepo.checkState,
        _appIcon,
    ) { prefs, updateInfo, checkState, appIcon ->
        AboutState(
            hasUpdate = prefs.allowInternetAccess && (updateInfo?.hasUpdate ?: false),
            remoteVersion = if (prefs.allowInternetAccess) updateInfo?.remoteVersion.orEmpty() else "",
            autoCheckUpdateOnStart = prefs.autoCheckUpdateOnStart,
            appIcon = appIcon,
            updateInfo = updateInfo,
            checkState = checkState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AboutState(),
    )

    init {
        loadAppIcon()
    }

    fun dispatch(action: AboutAction) {
        when (action) {
            is AboutAction.CheckUpdate -> checkUpdate(force = true)
            is AboutAction.SetAutoCheckUpdateOnStart -> viewModelScope.launch {
                updateSetting(BooleanSetting.AutoCheckUpdateOnStart, action.enabled)
            }
        }
    }

    /**
     * Runs one check and announces the **terminal** outcome.
     *
     * There is no loading event any more: `UpdateRepository.checkState` drives the dialog, and
     * the repository settles it on every path. Even a repository that throws (or a cancellation)
     * therefore cannot leave the page spinning — the exception becomes a definite failure.
     */
    private fun checkUpdate(force: Boolean = false) = viewModelScope.launch {
        Timber.i("About update check (force=%s)", force)
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
                _uiEvents.emit(AboutEvent.ShowUpdateSheet)
            }

            is UpdateCheckFeedback.Message -> _uiEvents.emit(
                AboutEvent.ShowMessage(feedback.resId, feedback.args),
            )
        }
    }

    private fun loadAppIcon() = viewModelScope.launch(Dispatchers.IO) {
        _appIcon.value = runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap: Bitmap = drawable.toBitmap(512, 512)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}
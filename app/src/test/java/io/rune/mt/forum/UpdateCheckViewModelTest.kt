// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import io.rune.mt.forum.domain.settings.model.preferences.AppPreferences
import io.rune.mt.forum.domain.settings.model.preferences.GithubUpdateChannel
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackAnimation
import io.rune.mt.forum.domain.settings.model.preferences.PredictiveBackExitDirection
import io.rune.mt.forum.domain.settings.model.preferences.theme.PaletteStyle
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeColorSpec
import io.rune.mt.forum.domain.settings.model.preferences.theme.ThemeMode
import io.rune.mt.forum.domain.settings.repository.AppSettingsRepository
import io.rune.mt.forum.domain.settings.repository.BooleanSetting
import io.rune.mt.forum.domain.settings.repository.IntSetting
import io.rune.mt.forum.domain.settings.repository.StringSetting
import io.rune.mt.forum.domain.settings.usecase.settings.UpdateSettingUseCase
import io.rune.mt.forum.domain.updater.model.UpdateCheckResult
import io.rune.mt.forum.domain.updater.model.UpdateCheckState
import io.rune.mt.forum.domain.updater.model.UpdateInfo
import io.rune.mt.forum.domain.updater.repository.UpdateRepository
import io.rune.mt.forum.ui.page.main.settings.preferred.PreferredViewAction
import io.rune.mt.forum.ui.page.main.settings.preferred.PreferredViewEvent
import io.rune.mt.forum.ui.page.main.settings.preferred.PreferredViewModel
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The settings page's update-check state machine.
 *
 * The device report was 「一直显示检查更新」. The page used to keep its own `showUpdateLoading`
 * boolean, switched by a `ShowUpdateLoading` / `HideUpdateLoading` event pair — a `Hide` that was
 * never reached (early return, exception, cancellation) left the blocking dialog up forever.
 *
 * The loading state now comes from `UpdateRepository.checkState`, so these checks assert that the
 * page's `isChecking` follows the repository and that every terminal outcome (update available /
 * up to date / failed with reason / policy skip / repository exception) produces exactly one
 * user-facing message and a non-checking state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the checking state, not a local flag, drives the dialog`() = runTest(dispatcher.scheduler) {
        val updateRepo = FakeUpdateRepository()
        val viewModel = viewModel(updateRepo)
        advanceUntilIdle()
        assertFalse("nothing is running yet", viewModel.state.value.isChecking)

        // The repository is the single owner of the phase: if it says Checking, the dialog shows.
        updateRepo.emitChecking()
        advanceUntilIdle()
        assertTrue("the dialog follows the repository", viewModel.state.value.isChecking)

        updateRepo.settle(upToDate("26.11.9"))
        advanceUntilIdle()
        assertFalse("a settled check cannot keep the dialog up", viewModel.state.value.isChecking)
    }

    @Test
    fun `an available update opens the update sheet`() = runTest(dispatcher.scheduler) {
        val updateRepo = FakeUpdateRepository()
        val viewModel = viewModel(updateRepo)
        val events = collectEvents(viewModel)
        updateRepo.next = {
            UpdateCheckResult.Checked(updateInfo(hasUpdate = true, version = "26.11.9"), 1_000)
        }

        viewModel.dispatch(PreferredViewAction.CheckUpdate)
        advanceUntilIdle()

        assertEquals(listOf<PreferredViewEvent>(PreferredViewEvent.ShowUpdateSheet), events)
        assertFalse(viewModel.state.value.isChecking)
    }

    @Test
    fun `an up-to-date check reports the version it checked`() = runTest(dispatcher.scheduler) {
        val updateRepo = FakeUpdateRepository()
        val viewModel = viewModel(updateRepo)
        val events = collectEvents(viewModel)
        updateRepo.next = {
            UpdateCheckResult.Checked(updateInfo(hasUpdate = false, version = "26.11.9"), 1_000)
        }

        viewModel.dispatch(PreferredViewAction.CheckUpdate)
        advanceUntilIdle()

        assertEquals(
            listOf<PreferredViewEvent>(
                PreferredViewEvent.ShowMessage(R.string.update_up_to_date_format, listOf("26.11.9")),
            ),
            events,
        )
    }

    @Test
    fun `a failed check reports the reason instead of spinning`() = runTest(dispatcher.scheduler) {
        val updateRepo = FakeUpdateRepository()
        val viewModel = viewModel(updateRepo)
        val events = collectEvents(viewModel)
        updateRepo.next = {
            UpdateCheckResult.Failed("GitHub HTTP 403 for https://api.github.com/…", 1_000)
        }

        viewModel.dispatch(PreferredViewAction.CheckUpdate)
        advanceUntilIdle()

        assertEquals(
            listOf<PreferredViewEvent>(
                PreferredViewEvent.ShowMessage(
                    R.string.update_check_failed_format,
                    listOf("GitHub HTTP 403 for https://api.github.com/…"),
                ),
            ),
            events,
        )
        assertFalse("a failure must not leave the dialog up", viewModel.state.value.isChecking)
    }

    @Test
    fun `a policy skip explains why nothing was checked`() = runTest(dispatcher.scheduler) {
        val updateRepo = FakeUpdateRepository()
        val viewModel = viewModel(updateRepo)
        val events = collectEvents(viewModel)
        updateRepo.next = {
            UpdateCheckResult.Skipped(UpdateCheckResult.SkipReason.INTERNET_DISABLED)
        }

        viewModel.dispatch(PreferredViewAction.CheckUpdate)
        advanceUntilIdle()

        assertEquals(
            listOf<PreferredViewEvent>(PreferredViewEvent.ShowMessage(R.string.update_check_skip_offline)),
            events,
        )
        assertFalse(viewModel.state.value.isChecking)
    }

    @Test
    fun `a repository that throws still ends in a definite failure`() = runTest(dispatcher.scheduler) {
        // Defensive branch: even if an implementation broke its contract and let an exception out,
        // the page must announce a failure rather than keep the loading dialog.
        val updateRepo = FakeUpdateRepository()
        val viewModel = viewModel(updateRepo)
        val events = collectEvents(viewModel)
        updateRepo.next = { throw IllegalStateException("boom") }

        viewModel.dispatch(PreferredViewAction.CheckUpdate)
        advanceUntilIdle()

        val message = events.single() as? PreferredViewEvent.ShowMessage
        assertTrue("a message must be shown: $events", message != null)
        assertEquals(R.string.update_check_failed_format, message?.messageResId)
        assertEquals(listOf("boom"), message?.args)
        assertFalse(viewModel.state.value.isChecking)
    }

    private fun viewModel(updateRepo: UpdateRepository) = PreferredViewModel(
        appSettingsRepo = TestSettingsRepository(),
        updateRepo = updateRepo,
        updateSetting = UpdateSettingUseCase(TestSettingsRepository()),
    )

    private fun TestScope.collectEvents(viewModel: PreferredViewModel): MutableList<PreferredViewEvent> {
        val events = mutableListOf<PreferredViewEvent>()
        // backgroundScope: the collector never completes on its own, and runTest cancels it.
        // Unconfined: it subscribes before `launch` returns, so a replay-less flow cannot drop
        // the first event.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEvents.collect { events += it }
        }
        return events
    }

    private fun upToDate(version: String) = UpdateCheckResult.Checked(
        UpdateInfo(hasUpdate = false, remoteVersion = version, releaseUrl = "", downloadUrl = ""),
        1_000,
    )

    private fun updateInfo(hasUpdate: Boolean, version: String) = UpdateInfo(
        hasUpdate = hasUpdate,
        remoteVersion = version,
        releaseUrl = "https://github.com/Rune-cn/MTForum/releases/tag/$version",
        downloadUrl = "https://github.com/Rune-cn/MTForum/releases/download/$version/mtforum-stable-$version.apk",
    )
}

/** [UpdateRepository] fake that mirrors the real state lifecycle (Checking → Settled / Idle). */
private class FakeUpdateRepository : UpdateRepository {
    private val info = MutableStateFlow<UpdateInfo?>(null)
    override val updateInfoFlow: StateFlow<UpdateInfo?> = info

    private val state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    override val checkState: StateFlow<UpdateCheckState> = state

    var next: suspend () -> UpdateCheckResult = {
        UpdateCheckResult.Skipped(UpdateCheckResult.SkipReason.DEBUG_BUILD)
    }

    fun emitChecking() {
        state.value = UpdateCheckState.Checking
    }

    fun settle(result: UpdateCheckResult) {
        state.value = UpdateCheckState.Settled(result)
    }

    override suspend fun checkUpdate(force: Boolean): UpdateCheckResult {
        state.value = UpdateCheckState.Checking
        return try {
            val result = next()
            (result as? UpdateCheckResult.Checked)?.let { info.value = it.info }
            state.value = UpdateCheckState.Settled(result)
            result
        } catch (cancellation: CancellationException) {
            state.value = UpdateCheckState.Idle
            throw cancellation
        } catch (error: Exception) {
            state.value = UpdateCheckState.Idle
            throw error
        }
    }

    override suspend fun downloadUpdate(url: String): Pair<InputStream, Long>? = null
}

/** [AppSettingsRepository] fake with the defaults the settings page reads. */
private class TestSettingsRepository : AppSettingsRepository {
    private val preferences = MutableStateFlow(
        AppPreferences(
            allowInternetAccess = true,
            githubUpdateChannel = GithubUpdateChannel.OFFICIAL,
            customGithubProxyUrl = "",
            autoCheckUpdateOnStart = false,
            enableFileLogging = true,
            themeMode = ThemeMode.SYSTEM,
            paletteStyle = PaletteStyle.TonalSpot,
            colorSpec = ThemeColorSpec.SPEC_2025,
            useDynamicColor = true,
            useMiuixMonet = false,
            useAppleFloatingBar = false,
            seedColorInt = 0,
            wallpaperSeedColorInt = 0,
            useBlur = false,
            showMiuixUI = true,
            predictiveBackAnimation = PredictiveBackAnimation.MIUIX,
            predictiveBackExitDirection = PredictiveBackExitDirection.ALWAYS_RIGHT,
        ),
    )
    override val preferencesFlow: Flow<AppPreferences> = preferences

    override suspend fun putString(setting: StringSetting, value: String): Unit = error(UNUSED)
    override fun getString(setting: StringSetting, default: String): Flow<String> = error(UNUSED)
    override suspend fun putInt(setting: IntSetting, value: Int): Unit = error(UNUSED)
    override fun getInt(setting: IntSetting, default: Int): Flow<Int> = error(UNUSED)
    override suspend fun putBoolean(setting: BooleanSetting, value: Boolean): Unit = error(UNUSED)
    override fun getBoolean(setting: BooleanSetting, default: Boolean): Flow<Boolean> = error(UNUSED)

    private companion object {
        const val UNUSED = "not used by the settings page"
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.miuix.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rune.mt.forum.data.plugin.PluginUiBridge
import io.rune.mt.forum.domain.plugin.InstalledPlugin
import io.rune.mt.forum.domain.plugin.PluginManifest
import io.rune.mt.forum.domain.plugin.PluginRepository
import io.rune.mt.forum.domain.plugin.PluginRepositoryConfig
import io.rune.mt.forum.domain.plugin.PluginRunRecord
import io.rune.mt.forum.domain.plugin.PluginRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** One card on the 插件 page: an installed plugin, its switch and its last run. */
data class ForumPluginCard(
    val manifest: PluginManifest,
    val enabled: Boolean,
    val record: PluginRunRecord? = null,
)

/**
 * State of the 插件 page.
 *
 * [cards] are the installed plugins (built-ins first on a fresh install), [available] is the remote
 * catalogue minus what is installed — i.e. exactly the rows that still need an 安装 button.
 * [lastLog] is the newest run's `ctx.log` output, kept in memory only: the page's "最近 50 行日志"
 * is a debugging aid, not a second store.
 */
data class ForumPluginState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val cards: List<ForumPluginCard> = emptyList(),
    val available: List<PluginManifest> = emptyList(),
    val config: PluginRepositoryConfig = PluginRepositoryConfig(),
    /** Why the last remote fetch failed; the built-ins stay usable. */
    val catalogError: String? = null,
    /** Id of the plugin currently running (one at a time, the Lua host is not re-entrant per card). */
    val runningId: String? = null,
    val lastLog: List<String> = emptyList(),
    val lastLogName: String? = null,
    /** One-shot line for the page's Toast (install/run/uninstall outcome). */
    val message: String? = null,
)

/**
 * Backs 设置 → 插件.
 *
 * The page is a list of cards over three flows of [PluginRepository] (installed, run records,
 * repository config) plus one catalogue fetch on demand. Two rules shape this class:
 *
 *  - **the catalogue is a shop window, not a dependency**: when the fetch fails, [ForumPluginState]
 *    keeps the built-in five and publishes [ForumPluginState.catalogError] as a single line;
 *  - **nothing here throws to the UI**: every repository call is wrapped, and the outcome is either
 *    a refreshed list or a Toast line, so a broken script / a bad JSON file never leaves the screen
 *    in a half-updated state.
 */
class ForumPluginViewModel(
    private val repository: PluginRepository,
    private val uiBridge: PluginUiBridge,
) : ViewModel() {

    private val _state = MutableStateFlow(ForumPluginState())
    val state: StateFlow<ForumPluginState> = _state.asStateFlow()

    /** Latest snapshots; the publish step is the only place that builds cards out of them. */
    private var installed: List<InstalledPlugin> = emptyList()
    private var catalog: List<PluginManifest> = emptyList()
    private var records: Map<String, PluginRunRecord> = emptyMap()

    init {
        viewModelScope.launch {
            repository.installedPlugins.collect { list ->
                installed = list
                publish()
            }
        }
        viewModelScope.launch {
            repository.runRecords.collect { map ->
                records = map
                publish()
            }
        }
        viewModelScope.launch {
            repository.repositoryConfig.collect { config ->
                _state.update { it.copy(config = config) }
            }
        }
        refresh()
    }

    /** Re-reads the remote catalogue; the local cards stay on screen while it runs. */
    fun refresh() {
        if (_state.value.isRefreshing) return
        viewModelScope.launch {
            _state.update { current ->
                current.copy(
                    isRefreshing = true,
                    isLoading = current.cards.isEmpty(),
                    catalogError = null,
                )
            }
            try {
                repository.ensureSeeded()
                catalog = repository.availableManifests()
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        isLoading = false,
                        catalogError = repository.catalogError.value,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "plugin catalogue refresh failed")
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        isLoading = false,
                        catalogError = error.message ?: "插件清单读取失败",
                    )
                }
            }
            publish()
        }
    }

    fun install(manifest: PluginManifest) {
        viewModelScope.launch {
            try {
                repository.install(manifest)
                _state.update { it.copy(message = "已安装「${manifest.name}」") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "plugin %s install failed", manifest.id)
                _state.update { it.copy(message = "安装失败：${error.message ?: "未知错误"}") }
            }
            publish()
        }
    }

    fun uninstall(manifest: PluginManifest) {
        viewModelScope.launch {
            try {
                repository.uninstall(manifest.id)
                _state.update { it.copy(message = "已卸载「${manifest.name}」") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "plugin %s uninstall failed", manifest.id)
                _state.update { it.copy(message = "卸载失败：${error.message ?: "未知错误"}") }
            }
            publish()
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setEnabled(id, enabled)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "plugin %s switch failed", id)
                _state.update { it.copy(message = "保存开关失败") }
            }
        }
    }

    /**
     * Runs a script plugin and keeps its log for the page's log block.
     *
     * A built-in has no script, so its card calls [openBuiltin] instead — that difference is the
     * whole reason the model has `builtin` and `script` side by side.
     */
    fun run(manifest: PluginManifest) {
        if (_state.value.runningId != null) return
        viewModelScope.launch {
            _state.update { it.copy(runningId = manifest.id, lastLogName = manifest.name) }
            val result = try {
                repository.run(manifest.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "plugin %s run failed", manifest.id)
                PluginRunResult(ok = false, message = error.message ?: "运行失败")
            }
            _state.update {
                it.copy(
                    runningId = null,
                    lastLog = result.log,
                    lastLogName = manifest.name,
                    message = if (result.ok) {
                        result.message.ifBlank { "运行完成" }
                    } else {
                        "运行失败：${result.message.ifBlank { "未知错误" }}"
                    },
                )
            }
        }
    }

    /** Opens a built-in plugin's page through the same bridge a script's `ctx.ui.open` uses. */
    fun openBuiltin(builtinId: String) {
        uiBridge.open(builtinId)
    }

    /** Saves the owner/repo/branch of the catalogue and reloads it. */
    fun saveRepository(owner: String, repo: String, branch: String) {
        val config = PluginRepositoryConfig(owner = owner, repo = repo, branch = branch).normalized()
        if (!config.isComplete) {
            _state.update { it.copy(message = "owner、仓库、分支都不能为空") }
            return
        }
        viewModelScope.launch {
            try {
                repository.setRepositoryConfig(config)
                _state.update { it.copy(message = "插件仓库已保存", catalogError = null) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "plugin repository config save failed")
                _state.update { it.copy(message = "保存失败：${error.message ?: "未知错误"}") }
                return@launch
            }
            refresh()
        }
    }

    /** Installs every plugin found in a `.json` document picked by the user. */
    fun installFromJson(raw: String) {
        viewModelScope.launch {
            val result = try {
                repository.installFromJson(raw)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "plugin import failed")
                _state.update { it.copy(message = "安装失败：${error.message ?: "未知错误"}") }
                return@launch
            }
            _state.update {
                it.copy(
                    message = result.error?.let { reason -> "安装失败：$reason" }
                        ?: "已安装 ${result.installed} 个插件",
                )
            }
            if (result.installed > 0) refresh()
        }
    }

    /** Drops the one-shot Toast line once the page has shown it. */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * Rebuilds the card rows out of the three snapshots.
     *
     * Kept as one function (instead of a `combine` of three flows) because the catalogue is also
     * affected by an action (refresh/install) — a single publish point makes "install moves a row
     * from 可安装 to 已安装" impossible to get half-applied.
     */
    private fun publish() {
        val cards = installed.map { plugin ->
            ForumPluginCard(
                manifest = plugin.manifest,
                enabled = plugin.enabled,
                record = records[plugin.manifest.id],
            )
        }
        val known = installed.map { it.manifest.id }.toHashSet()
        val available = catalog.filterNot { it.id in known }
        _state.update { it.copy(cards = cards, available = available, isLoading = false) }
    }

    private companion object {
        const val TAG = "ForumPluginVM"
    }
}

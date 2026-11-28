// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.plugin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.rune.mt.forum.domain.plugin.InstalledPlugin
import io.rune.mt.forum.domain.plugin.PluginImportResult
import io.rune.mt.forum.domain.plugin.PluginManifest
import io.rune.mt.forum.domain.plugin.PluginPackage
import io.rune.mt.forum.domain.plugin.PluginRepository
import io.rune.mt.forum.domain.plugin.PluginRepositoryConfig
import io.rune.mt.forum.domain.plugin.PluginRunRecord
import io.rune.mt.forum.domain.plugin.PluginRunResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * [PluginRepository] backed by the **app settings** DataStore, exactly like the comment filter and
 * the board preferences (rule 37's "one JSON document per feature, one atomic edit" pattern).
 *
 * Three documents, three keys, all in the settings store — no second DataStore is introduced:
 *
 *  - `plugin_installed_v1`: the installed manifests plus their switch. Storing whole manifests
 *    (not ids) is deliberate: an installed script must keep working when the remote catalogue is
 *    unreachable, so the remote list is only ever a shop window.
 *  - `plugin_repository_v1`: owner / repo / branch of the catalogue URL.
 *  - `plugin_runtime_v1`: the last run of each plugin (time, ok, first line, ≤50 log lines), so the
 *    card still explains itself after a restart.
 *
 * A document that does not decode degrades instead of throwing: the installed list falls back to
 * the five built-ins, the config to the project default, the runtime records to empty. Installing
 * is the only path that **rejects** a manifest, and it does so before writing.
 */
class PluginRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val runner: PluginRunner,
    private val storage: PluginScriptStorage,
    /**
     * HTTP client for the catalogue only.
     *
     * It deliberately does **not** share [io.rune.mt.forum.data.forum.network.ForumHttpClient]:
     * raw.githubusercontent.com is not the forum, needs no anti-bot solver and must never see a
     * forum cookie (rule 14 is about host scoping). The 15 s cap is the `callTimeout` below —
     * a coroutine `withTimeout` cannot interrupt a blocking `execute()` (rule 36).
     */
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : PluginRepository {

    private val _catalogError = MutableStateFlow<String?>(null)
    override val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    // Stored as `Map<id, InstalledPlugin>` (the brief's shape); the UI keeps a list because it
    // renders cards in install order — a decoded LinkedHashMap preserves that order.
    override val installedPlugins: Flow<List<InstalledPlugin>> =
        dataStore.data.map { prefs -> decodeInstalled(prefs[KEY_INSTALLED]).values.toList() }

    override val repositoryConfig: Flow<PluginRepositoryConfig> =
        dataStore.data.map { prefs -> decodeConfig(prefs[KEY_CONFIG]) }

    override val runRecords: Flow<Map<String, PluginRunRecord>> =
        dataStore.data.map { prefs -> decodeRecords(prefs[KEY_RUNTIME]) }

    override suspend fun availableManifests(): List<PluginManifest> {
        val config = repositoryConfig.first().normalized()
        val (remote, error) = fetchRemote(config)
        _catalogError.value = error
        // Built-ins always win the id: a remote document cannot redefine 浏览历史's page or
        // replace an app screen with a script of the same name.
        val merged = linkedMapOf<String, PluginManifest>()
        BuiltinPlugins.all.forEach { merged[it.id] = it }
        remote.forEach { manifest ->
            if (manifest.hasUsableId && merged[manifest.id] == null) merged[manifest.id] = manifest
        }
        return merged.values.toList()
    }

    override suspend fun installed(): List<InstalledPlugin> =
        installedPlugins.first().filter { it.manifest.hasUsableId }

    override suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            if (prefs[KEY_INSTALLED].isNullOrBlank()) {
                prefs[KEY_INSTALLED] = json.encodeToString(INSTALLED_SERIALIZER, builtinInstalls())
            }
        }
        Unit
    }

    override suspend fun install(manifest: PluginManifest) = withContext(Dispatchers.IO) {
        val clean = validateForInstall(manifest)
        mutateInstalled { current ->
            val entry = InstalledPlugin(
                manifest = clean,
                // Reinstalling keeps the user's switch where it was; a fresh install follows the
                // manifest's own default (scripts ship off, built-ins on).
                enabled = current[clean.id]?.enabled ?: clean.enabledByDefault,
            )
            current.filterKeys { it != clean.id } + (clean.id to entry)
        }
        Unit
    }

    override suspend fun uninstall(id: String) = withContext(Dispatchers.IO) {
        mutateInstalled { current -> current - id }
        dataStore.edit { prefs ->
            val records = decodeRecords(prefs[KEY_RUNTIME])
            if (records.containsKey(id)) {
                prefs[KEY_RUNTIME] = json.encodeToString(RECORDS_SERIALIZER, records - id)
            }
        }
        storage.clear(id)
        Unit
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutateInstalled { current ->
            current.mapValues { (key, plugin) ->
                if (key == id) plugin.copy(enabled = enabled) else plugin
            }
        }
        Unit
    }

    override suspend fun run(id: String): PluginRunResult = withContext(Dispatchers.IO) {
        val plugin = installedPlugins.first().firstOrNull { it.manifest.id == id }
            ?: return@withContext PluginRunResult(ok = false, message = "插件未安装")
        val manifest = plugin.manifest
        val result = when {
            // A built-in has no script: "running" it means resolving its page, which the UI does
            // through PluginUiBridge. Reporting success here keeps the card's message honest.
            manifest.isBuiltin -> PluginRunResult(ok = true, message = "已打开内置页面")
            !manifest.hasScript -> PluginRunResult(ok = false, message = "这个插件没有脚本")
            else -> try {
                runner.run(manifest)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "plugin %s failed", manifest.id)
                PluginRunResult(ok = false, message = error.message ?: "运行失败")
            }
        }
        recordRun(id, result)
        result
    }

    override suspend fun setRepositoryConfig(config: PluginRepositoryConfig) =
        withContext(Dispatchers.IO) {
            val clean = config.normalized()
            dataStore.edit { prefs ->
                prefs[KEY_CONFIG] = json.encodeToString(PluginRepositoryConfig.serializer(), clean)
            }
            Unit
        }

    override suspend fun installFromJson(raw: String): PluginImportResult =
        withContext(Dispatchers.IO) {
            if (raw.isBlank()) return@withContext PluginImportResult(error = "文件是空的")
            if (raw.length > MAX_IMPORT_CHARS) {
                return@withContext PluginImportResult(error = "文件太大，超过 1 MB")
            }
            val manifests = parseManifests(raw)
            if (manifests.isEmpty()) {
                return@withContext PluginImportResult(error = "文件里没有可用的插件")
            }
            var installed = 0
            var failure: String? = null
            manifests.forEach { manifest ->
                try {
                    install(manifest)
                    installed++
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    failure = error.message ?: "安装失败"
                    Timber.tag(TAG).w(error, "plugin %s rejected", manifest.id)
                }
            }
            if (installed == 0) {
                PluginImportResult(error = failure ?: "安装失败")
            } else {
                PluginImportResult(installed = installed)
            }
        }

    override suspend fun runEnabledOnAppStart() = withContext(Dispatchers.IO) {
        ensureSeeded()
        val scripts = installedPlugins.first()
            .filter { it.enabled && it.manifest.hasScript && !it.manifest.isBuiltin }
        if (scripts.isEmpty()) return@withContext
        scripts.forEach { plugin ->
            try {
                val result = runner.runOnAppStart(plugin.manifest)
                Timber.tag(TAG).i(
                    "onAppStart %s ok=%s %s",
                    plugin.manifest.id,
                    result.ok,
                    result.message,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // One plugin must never stop the next one (or the app).
                Timber.tag(TAG).w(error, "onAppStart %s crashed", plugin.manifest.id)
            }
        }
    }

    // ---- Catalogue -----------------------------------------------------------

    /**
     * Fetches and parses `plugins.json`.
     *
     * @return the manifests plus the reason the fetch failed (or `null` on success); a thrown
     *   exception is turned into the reason, so an offline phone just shows the built-ins.
     */
    private suspend fun fetchRemote(
        config: PluginRepositoryConfig,
    ): Pair<List<PluginManifest>, String?> = withContext(Dispatchers.IO) {
        if (!config.isComplete) {
            return@withContext emptyList<PluginManifest>() to "插件仓库地址不完整"
        }
        val request = Request.Builder()
            .url(config.manifestUrl)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("catalogue HTTP %d for %s", response.code, config.manifestUrl)
                    return@use emptyList<PluginManifest>() to "HTTP ${response.code}"
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use emptyList<PluginManifest>() to "清单是空的"
                val manifests = parseManifests(body)
                if (manifests.isEmpty()) {
                    emptyList<PluginManifest>() to "清单里没有可用的插件"
                } else {
                    manifests to null
                }
            }
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "catalogue fetch from %s failed", config.manifestUrl)
            emptyList<PluginManifest>() to (error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Accepts the three shapes a document may have: a [PluginPackage], a bare list of manifests, or
     * one manifest. The order matters — see the guards below — and anything that fails is dropped
     * instead of failing the whole document.
     */
    private fun parseManifests(raw: String): List<PluginManifest> {
        val packageDocument = runCatching {
            json.decodeFromString(PluginPackage.serializer(), raw)
        }.getOrNull()
        if (packageDocument != null && packageDocument.plugins.isNotEmpty()) {
            return sanitise(packageDocument.plugins)
        }
        val listDocument = runCatching {
            json.decodeFromString(MANIFEST_LIST_SERIALIZER, raw)
        }.getOrNull()
        if (listDocument != null && listDocument.isNotEmpty()) {
            return sanitise(listDocument)
        }
        val single = runCatching {
            json.decodeFromString(PluginManifest.serializer(), raw)
        }.getOrNull()
        return sanitise(listOfNotNull(single))
    }

    private fun sanitise(manifests: List<PluginManifest>): List<PluginManifest> {
        val seen = HashSet<String>()
        return manifests.asSequence()
            .filter { it.hasUsableId }
            .filter { it.name.isNotBlank() }
            .filter { it.isBuiltin || it.hasScript }
            .filter { (it.script?.length ?: 0) <= PluginManifest.MAX_SCRIPT_LENGTH }
            .filter { seen.add(it.id) }
            .take(MAX_CATALOG_PLUGINS)
            .toList()
    }

    // ---- Store helpers -------------------------------------------------------

    /** Atomic read-modify-write of the installed document. */
    private suspend fun mutateInstalled(
        transform: (Map<String, InstalledPlugin>) -> Map<String, InstalledPlugin>,
    ) {
        dataStore.edit { prefs ->
            val current = decodeInstalled(prefs[KEY_INSTALLED])
            prefs[KEY_INSTALLED] = json.encodeToString(INSTALLED_SERIALIZER, transform(current))
        }
    }

    private suspend fun recordRun(id: String, result: PluginRunResult) {
        dataStore.edit { prefs ->
            val records = decodeRecords(prefs[KEY_RUNTIME]).toMutableMap()
            records[id] = PluginRunRecord(
                at = clock(),
                ok = result.ok,
                message = result.message.take(MAX_MESSAGE_CHARS),
                log = result.log.take(PluginRunResult.MAX_RUN_LOG_LINES),
            )
            prefs[KEY_RUNTIME] = json.encodeToString(RECORDS_SERIALIZER, records.toMap())
        }
    }

    /** Rejects a manifest that could never work, before anything is written. */
    private fun validateForInstall(manifest: PluginManifest): PluginManifest {
        val clean = manifest.copy(
            id = manifest.id.trim(),
            name = manifest.name.trim(),
            version = manifest.version.trim().ifBlank { "1.0.0" },
            builtin = manifest.builtin?.trim()?.takeIf { it.isNotEmpty() },
            script = manifest.script?.takeIf { it.isNotBlank() },
        )
        require(clean.hasUsableId) { "插件 id 不可用（不能为空、不能含空格或斜杠、最多 64 字符）" }
        require(clean.name.isNotEmpty()) { "插件名称不能为空" }
        clean.builtin?.let { page ->
            require(BuiltinPlugins.isPageId(page)) { "未知的内置页面：$page" }
        }
        require(clean.isBuiltin || clean.hasScript) { "插件既没有内置页面也没有脚本" }
        require((clean.script?.length ?: 0) <= PluginManifest.MAX_SCRIPT_LENGTH) {
            "脚本超过 ${PluginManifest.MAX_SCRIPT_LENGTH} 字符"
        }
        return clean
    }

    private fun builtinInstalls(): Map<String, InstalledPlugin> {
        val installs = linkedMapOf<String, InstalledPlugin>()
        BuiltinPlugins.all.forEach { manifest ->
            installs[manifest.id] = InstalledPlugin(manifest = manifest, enabled = manifest.enabledByDefault)
        }
        return installs
    }

    /**
     * A missing document means "never seeded", which is what makes uninstalling a built-in stick:
     * once the document exists its content — including an empty list — is the truth.
     */
    private fun decodeInstalled(raw: String?): Map<String, InstalledPlugin> {
        if (raw.isNullOrBlank()) return builtinInstalls()
        val decoded = runCatching { json.decodeFromString(INSTALLED_SERIALIZER, raw) }
            .onFailure { Timber.tag(TAG).w(it, "installed plugins unreadable; showing built-ins") }
            .getOrNull()
            ?: return builtinInstalls()
        val sanitised = linkedMapOf<String, InstalledPlugin>()
        decoded.forEach { (key, plugin) ->
            // The map key is the authority; a hand-edited document whose key disagrees with the
            // manifest is dropped rather than rendered under the wrong identity.
            if (key != plugin.manifest.id) return@forEach
            if (!plugin.manifest.hasUsableId) return@forEach
            if (!plugin.manifest.isBuiltin && !plugin.manifest.hasScript) return@forEach
            sanitised[key] = plugin
        }
        return sanitised
    }

    private fun decodeConfig(raw: String?): PluginRepositoryConfig {
        if (raw.isNullOrBlank()) return PluginRepositoryConfig()
        return runCatching { json.decodeFromString(PluginRepositoryConfig.serializer(), raw) }
            .onFailure { Timber.tag(TAG).w(it, "plugin repository config unreadable; using default") }
            .getOrNull()
            ?.normalized()
            ?.takeIf { it.isComplete }
            ?: PluginRepositoryConfig()
    }

    private fun decodeRecords(raw: String?): Map<String, PluginRunRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(RECORDS_SERIALIZER, raw) }
            .onFailure { Timber.tag(TAG).w(it, "plugin run records unreadable; treating as empty") }
            .getOrDefault(emptyMap())
    }

    private companion object {
        const val TAG = "PluginRepo"
        const val KEY_INSTALLED_NAME = "plugin_installed_v1"
        const val KEY_CONFIG_NAME = "plugin_repository_v1"
        const val KEY_RUNTIME_NAME = "plugin_runtime_v1"
        const val MAX_CATALOG_PLUGINS = 200
        const val MAX_IMPORT_CHARS = 1_000_000
        const val MAX_MESSAGE_CHARS = 300
        const val HTTP_TIMEOUT_SECONDS = 15L
        const val USER_AGENT = "MTForum-Android"

        val KEY_INSTALLED = stringPreferencesKey(KEY_INSTALLED_NAME)
        val KEY_CONFIG = stringPreferencesKey(KEY_CONFIG_NAME)
        val KEY_RUNTIME = stringPreferencesKey(KEY_RUNTIME_NAME)
        val INSTALLED_SERIALIZER = MapSerializer(String.serializer(), InstalledPlugin.serializer())
        val MANIFEST_LIST_SERIALIZER = ListSerializer(PluginManifest.serializer())
        val RECORDS_SERIALIZER = MapSerializer(String.serializer(), PluginRunRecord.serializer())

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

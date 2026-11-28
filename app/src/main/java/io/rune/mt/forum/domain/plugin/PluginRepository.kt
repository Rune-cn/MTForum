// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.plugin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The plugin surface the 设置 → 插件 page and the app start use.
 *
 * Three things live behind it, and they are deliberately separate:
 *
 *  - **the catalogue** ([availableManifests]): the built-in five plus whatever the remote
 *    `plugins.json` currently lists. A failed fetch is not an error state for the screen — the
 *    built-ins are always there, the reason is reported through [catalogError] and shown as a
 *    single line;
 *  - **the install state** ([installedPlugins]): a DataStore document that stores the whole
 *    manifest, so an installed plugin never depends on the remote list being reachable again;
 *  - **execution** ([run]): built-ins only resolve a page id (the pages already exist), scripts go
 *    through the Lua host in `data/plugin`.
 *
 * Nothing here throws to the UI: [run] reports a failure as a [PluginRunResult], and the store
 * methods are best-effort in the sense that a broken document degrades to "the built-ins", never
 * to a crashing screen (the same tolerance rule as the draft / filter stores).
 */
interface PluginRepository {

    /** Installed plugins, in install order; the five built-ins first on a fresh install. */
    val installedPlugins: Flow<List<InstalledPlugin>>

    /** Where the remote `plugins.json` is read from (owner / repo / branch). */
    val repositoryConfig: Flow<PluginRepositoryConfig>

    /** Why the last catalogue fetch failed, or `null` when it succeeded. */
    val catalogError: StateFlow<String?>

    /** Last run per plugin id, so a card survives a restart. */
    val runRecords: Flow<Map<String, PluginRunRecord>>

    /**
     * The remote catalogue merged with the built-ins, deduplicated by id.
     *
     * A failed fetch (offline, 404, bad JSON, the 15 s cap) returns the built-ins only and publishes
     * the reason on [catalogError]; it never propagates an exception.
     */
    suspend fun availableManifests(): List<PluginManifest>

    /** Installed plugins as a one-shot list (the start-up and run paths). */
    suspend fun installed(): List<InstalledPlugin>

    /**
     * Seeds the built-in five on first use.
     *
     * The install document's absence means "never seeded", which is what makes uninstalling a
     * built-in stick: once the document exists its content is the truth.
     */
    suspend fun ensureSeeded()

    /**
     * Installs [manifest], replacing an installed copy with the same id.
     *
     * @throws IllegalArgumentException when the manifest is unusable (blank/unsafe id, no built-in
     *   page and no script, or a script above [PluginManifest.MAX_SCRIPT_LENGTH]).
     */
    suspend fun install(manifest: PluginManifest)

    /** Removes the plugin, its run record and its per-plugin key/value store. */
    suspend fun uninstall(id: String)

    /** Flips the switch of an installed plugin; a no-op for an id that is not installed. */
    suspend fun setEnabled(id: String, enabled: Boolean)

    /**
     * Runs one plugin.
     *
     * Built-ins resolve to their page id (the page itself opens through
     * [io.rune.mt.forum.data.plugin.PluginUiBridge]); scripts execute in the Lua host. Every
     * outcome — including a script error — is a result, never an exception.
     */
    suspend fun run(id: String): PluginRunResult

    /** Saves the owner/repo/branch of the remote catalogue. */
    suspend fun setRepositoryConfig(config: PluginRepositoryConfig)

    /**
     * Installs every plugin in a picked `.json` document.
     *
     * The document may be a [PluginPackage], a bare list of manifests or a single manifest. The
     * script text is copied into the install state; the picked file is never referenced again.
     */
    suspend fun installFromJson(raw: String): PluginImportResult

    /**
     * Runs `onAppStart(ctx)` of every enabled script plugin, one by one.
     *
     * Called once per process from `App`; each plugin is isolated, so a throwing script cannot
     * stop the next one or the app.
     */
    suspend fun runEnabledOnAppStart()
}

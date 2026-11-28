// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.domain.plugin

import kotlinx.serialization.Serializable

/**
 * One plugin as it arrives from the remote `plugins.json`, from a local `.json` file, or from
 * the built-in catalogue.
 *
 * A manifest is **data only**: either it points at a built-in screen ([builtin]) or it carries the
 * Lua source that the host executes ([script]). Both fields are optional so one document can
 * describe a built-in row and a script plugin with the same schema.
 *
 * [id] is the stable identity used by the install state, the per-plugin key/value store and the
 * run records, so it must survive edits: it is the only key those maps use.
 */
@Serializable
data class PluginManifest(
    /**
     * Stable identifier, e.g. `history` (built-in) or `daily-signin` (script).
     *
     * Defaults to blank so one hand-edited entry cannot make a whole `plugins.json` undecodable;
     * [hasUsableId] is the gate every caller uses, and installing rejects a blank id.
     */
    val id: String = "",
    /** Display name, in the plugin's own language; blank entries are dropped by the parser. */
    val name: String = "",
    /** Manifest version as printed on the card. */
    val version: String = "1.0.0",
    /** One or two lines explaining what the plugin does. */
    val description: String = "",
    /**
     * Lua source executed by [io.rune.mt.forum.data.plugin.PluginRunner].
     *
     * Copied verbatim into the install state when a package is installed — the app never keeps a
     * pointer at the picked file, so deleting the download cannot break an installed plugin.
     */
    val script: String? = null,
    /**
     * Built-in page identifier: `history`, `filter`, `board`, `mcp` or `cache`.
     *
     * A built-in plugin has no script: "running" it only opens the existing screen, which is why
     * the five pre-existing features became cards without any of their logic moving.
     */
    val builtin: String? = null,
    /** Whether a freshly installed copy starts enabled. Built-ins ship enabled. */
    val enabledByDefault: Boolean = false,
) {
    /** True when this row opens an app screen instead of executing a script. */
    val isBuiltin: Boolean get() = !builtin.isNullOrBlank()

    /** True when there is Lua source to execute. */
    val hasScript: Boolean get() = !script.isNullOrBlank()

    /**
     * True when [id] is usable as a storage key: trimmed, bounded and free of path separators
     * (the id is part of the per-plugin preference file name).
     */
    val hasUsableId: Boolean
        get() {
            val trimmed = id.trim()
            return trimmed.isNotEmpty() &&
                trimmed.length <= MAX_PLUGIN_ID_LENGTH &&
                trimmed.none { it == '/' || it == '\\' || it.isWhitespace() || it.isISOControl() }
        }

    companion object {
        const val MAX_PLUGIN_ID_LENGTH = 64

        /** Upper bound on a stored script; a manifest above it is rejected at install time. */
        const val MAX_SCRIPT_LENGTH = 200_000
    }
}

/**
 * The wire shape of a `plugins.json` (and of a local install file).
 *
 * [schemaVersion] exists so a future format can be recognised instead of silently mis-parsed; the
 * reader accepts a bare [PluginManifest] and a bare `[PluginManifest]` as well, because a
 * hand-written single-plugin file is a legitimate thing to pick from the file picker.
 */
@Serializable
data class PluginPackage(
    val schemaVersion: Int = 1,
    val plugins: List<PluginManifest> = emptyList(),
)

/**
 * One installed plugin plus its switch.
 *
 * The whole [manifest] is stored (not just its id) so an installed script keeps working offline:
 * the remote list is a shop window, not a dependency.
 */
@Serializable
data class InstalledPlugin(
    val manifest: PluginManifest,
    val enabled: Boolean = true,
)

/**
 * Where the remote shop window lives: `raw.githubusercontent.com/<owner>/<repo>/<branch>/plugins.json`.
 *
 * The three fields are user-editable (设置 → 插件 → 插件仓库) so a fork can be used without a new
 * build; the defaults point at this project's own plugin repository.
 */
@Serializable
data class PluginRepositoryConfig(
    val owner: String = DEFAULT_OWNER,
    val repo: String = DEFAULT_REPO,
    val branch: String = DEFAULT_BRANCH,
) {
    /** Trimmed copy, safe to store and to put in a URL. */
    fun normalized(): PluginRepositoryConfig = copy(
        owner = owner.trim(),
        repo = repo.trim(),
        branch = branch.trim(),
    )

    /** False when any part is blank: such a config can never build a URL. */
    val isComplete: Boolean
        get() = owner.isNotBlank() && repo.isNotBlank() && branch.isNotBlank()

    /** The catalogue URL this config resolves to. */
    val manifestUrl: String
        get() = "https://raw.githubusercontent.com/${owner.trim()}/${repo.trim()}/${branch.trim()}/plugins.json"

    companion object {
        const val DEFAULT_OWNER = "Rune-cn"
        const val DEFAULT_REPO = "MTForum-Plugins"
        const val DEFAULT_BRANCH = "main"
    }
}

/**
 * What one `run` of a plugin produced.
 *
 * [message] is one user-facing line (the server's own wording where there is one); [log] is the
 * script's own `ctx.log` output, newest run only, capped at [MAX_RUN_LOG_LINES]. The host never
 * throws: a broken script is reported here and everything else keeps working.
 */
data class PluginRunResult(
    val ok: Boolean,
    val message: String = "",
    val log: List<String> = emptyList(),
) {
    companion object {
        /** How many log lines one run keeps (the page's "最近 50 行"). */
        const val MAX_RUN_LOG_LINES = 50
    }
}

/** The last run of one plugin, persisted so the card survives a restart. */
@Serializable
data class PluginRunRecord(
    val at: Long = 0L,
    val ok: Boolean = false,
    val message: String = "",
    val log: List<String> = emptyList(),
)

/** Outcome of installing a picked `.json` file. */
data class PluginImportResult(
    val installed: Int = 0,
    val error: String? = null,
)

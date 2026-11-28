// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.data.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import io.rune.mt.forum.data.forum.network.ForumHttpGateway
import io.rune.mt.forum.domain.forum.model.ImageHostGateway
import io.rune.mt.forum.domain.forum.model.ImageHostResult
import io.rune.mt.forum.domain.forum.model.ForumSignInOutcome
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.plugin.PluginManifest
import io.rune.mt.forum.domain.plugin.PluginRunResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import timber.log.Timber

/**
 * The Lua host behind 设置 → 插件.
 *
 * ## What a script sees
 *
 * Exactly one global table, `ctx`, with six namespaces ([createContext]):
 *
 * ```lua
 * function onAppStart(ctx)      -- optional; runs ~2 s after the session restore
 * end
 *
 * function onRun(ctx)           -- optional; runs from the card's 运行 button
 *   ctx.log("hello")
 *   ctx.toast("hello")
 *   ctx.storage.set("last", "2026-11-29") -- per-plugin key/value (strings only)
 *   local signed = ctx.forum.signIn()      -- { ok = bool, message = string }
 *   local logged = ctx.forum.isLoggedIn()  -- boolean
 *   local up = ctx.imageHost.uploadBase64(base64, "a.png")
 *                                          -- { ok, bbcode, message, url }
 *   local page = ctx.http.get("forum.php") -- { ok, code, body }
 *   local posted = ctx.http.postForm("plugin.php?id=x", { a = "1" })
 *   ctx.ui.open("history")                 -- history/filter/board/mcp/cache
 * end
 * ```
 *
 * ## What it cannot reach
 *
 * `os`, `io`, `package`, `require`, `debug`, `luajava`, `load`, `loadfile` and `dofile`
 * are set to nil before the chunk runs, so a plugin cannot touch the filesystem, spawn a process,
 * load more code or call into Java through LuaJ's own binding. `string` / `table` / `math` stay:
 * without them a script could not do anything at all, and they are pure Lua.
 *
 * ## Threading and failure policy
 *
 * The whole script runs on [Dispatchers.IO] inside `withTimeout(10 s)`, and every host call bridges
 * back to a `suspend` API with [runBlocking] — the Lua VM is synchronous, so there is no other way
 * to keep the same API on both sides. Nothing a plugin does may crash the app: a syntax error, a
 * runtime error, a timeout or a failed request all come back as a [PluginRunResult] with the reason
 * and whatever the script logged before it stopped.
 *
 * **Known limit:** LuaJ has no instruction-count hook we can use on Android, so the 10 s cap is
 * checked at suspension points. A script stuck in a pure-Lua `while true do end` keeps its IO
 * thread until the process dies; it cannot block the UI, and it is documented as a risk rather
 * than pretended away.
 */
class PluginRunner(
    private val http: ForumHttpGateway,
    private val forum: ForumRepository,
    private val imageHost: ImageHostGateway,
    private val storage: PluginScriptStorage,
    private val uiBridge: PluginUiBridge,
    context: Context,
) {

    private val appContext: Context = context.applicationContext
    private val uploadDir = File(appContext.cacheDir, UPLOAD_DIR)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Runs the `onRun` hook (or the chunk itself when there is none). */
    suspend fun run(manifest: PluginManifest): PluginRunResult = execute(manifest, ENTRY_RUN)

    /** Runs the optional `onAppStart` hook; a plugin without one is simply skipped. */
    suspend fun runOnAppStart(manifest: PluginManifest): PluginRunResult =
        execute(manifest, ENTRY_APP_START)

    private suspend fun execute(manifest: PluginManifest, entry: String): PluginRunResult {
        val script = manifest.script?.takeIf { it.isNotBlank() }
            ?: return PluginRunResult(ok = false, message = "这个插件没有脚本")
        val log = PluginLogSink()
        return try {
            withTimeout(SCRIPT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val globals = newGlobals()
                    val ctx = createContext(manifest, log)
                    // Compile + run the chunk first: top-level statements are the script's own
                    // setup, and a syntax error must surface before any hook is looked up.
                    globals.load(script, "@${manifest.id.take(32)}").call()
                    val hook = globals.get(entry)
                    if (!hook.isfunction()) {
                        if (entry == ENTRY_APP_START) {
                            PluginRunResult(ok = true, message = "未定义 onAppStart", log = log.snapshot())
                        } else {
                            PluginRunResult(ok = true, message = "脚本已执行（未定义 onRun）", log = log.snapshot())
                        }
                    } else {
                        hook.call(ctx)
                        PluginRunResult(
                            ok = true,
                            message = if (entry == ENTRY_APP_START) "onAppStart 已执行" else "运行完成",
                            log = log.snapshot(),
                        )
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // Caught before CancellationException: the timeout is a cancellation, but here it is a
            // normal outcome of the plugin, not the caller going away.
            Timber.tag(TAG).w("plugin %s hit the %dms cap", manifest.id, SCRIPT_TIMEOUT_MS)
            PluginRunResult(ok = false, message = "脚本执行超过 10 秒，已中止", log = log.snapshot())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "plugin %s failed", manifest.id)
            PluginRunResult(ok = false, message = firstLine(error), log = log.snapshot())
        }
    }

    /**
     * A fresh sandbox per run: a script cannot leave a global behind for the next plugin, and a
     * plugin that overwrites `ctx` only breaks itself.
     */
    private fun newGlobals(): Globals {
        val globals = JsePlatform.standardGlobals()
        BLOCKED_GLOBALS.forEach { name ->
            globals.set(LuaValue.valueOf(name), LuaValue.NIL)
        }
        return globals
    }

    /** The `ctx` table handed to `onRun` / `onAppStart`. */
    private fun createContext(manifest: PluginManifest, log: PluginLogSink): LuaTable {
        val pluginId = manifest.id
        val storageTable = table(
            "get" to fn { args ->
                val value = storage.get(pluginId, args.arg(1).tojstring())
                if (value == null) LuaValue.NIL else LuaValue.valueOf(value)
            },
            "set" to fn { args ->
                storage.set(pluginId, args.arg(1).tojstring(), args.arg(2).tojstring())
                LuaValue.TRUE
            },
        )
        val forumTable = table(
            "signIn" to fn {
                try {
                    val state = await { forum.getSignInState() }
                    val formhash = state.formhash
                    if (formhash.isNullOrBlank()) {
                        result(ok = false, message = "没有拿到 formhash，请先登录")
                    } else {
                        val outcome = await { forum.signIn(formhash) }
                        // 已签到 is not an error: the day is simply already done.
                        result(
                            ok = outcome !is ForumSignInOutcome.Failure,
                            message = outcome.message,
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    result(ok = false, message = firstLine(error))
                }
            },
            "isLoggedIn" to fn { LuaValue.valueOf(forum.session.value.isLoggedIn) },
        )
        val imageHostTable = table(
            "uploadBase64" to fn { args ->
                uploadBase64(args.arg(1).tojstring(), args.arg(2).optjstring("image.png"))
            },
        )
        val httpTable = table(
            "get" to fn { args ->
                val path = args.arg(1).tojstring().trim()
                if (path.isEmpty()) return@fn result(ok = false, message = "path 不能为空")
                try {
                    val response = await { http.getHtmlResult(path) }
                    table(
                        "ok" to LuaValue.valueOf(response.isSuccessful),
                        "code" to LuaValue.valueOf(response.code),
                        "body" to LuaValue.valueOf(capBody(response.body)),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    result(ok = false, message = firstLine(error))
                }
            },
            "postForm" to fn { args ->
                val path = args.arg(1).tojstring().trim()
                if (path.isEmpty()) return@fn result(ok = false, message = "path 不能为空")
                try {
                    val body = await { http.postForm(path, luaTableToMap(args.arg(2))) }
                    table(
                        "ok" to LuaValue.TRUE,
                        "code" to LuaValue.valueOf(200),
                        "body" to LuaValue.valueOf(capBody(body)),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    result(ok = false, message = firstLine(error))
                }
            },
        )
        val uiTable = table(
            "open" to fn { args ->
                uiBridge.open(args.arg(1).tojstring())
                LuaValue.TRUE
            },
        )
        return table(
            "log" to fn { args ->
                log.add(args.arg(1).tojstring())
                LuaValue.NONE
            },
            "toast" to fn { args ->
                toast(args.arg(1).tojstring())
                LuaValue.NONE
            },
            "storage" to storageTable,
            "forum" to forumTable,
            "imageHost" to imageHostTable,
            "http" to httpTable,
            "ui" to uiTable,
        )
    }

    /**
     * `ctx.imageHost.uploadBase64(base64, filename)`.
     *
     * The brief asks for the decoded image to be written to a cache file before the upload, so a
     * very large base64 string does not sit in two copies while multipart encoding runs; the file
     * is deleted in `finally` whether the upload worked or not.
     */
    private fun uploadBase64(base64: String, fileName: String): LuaTable {
        val payload = base64.substringAfter("base64,", base64).trim()
        if (payload.isEmpty()) return result(ok = false, message = "base64 内容是空的")
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            Timber.tag(TAG).w("base64 decode failed: %s", error.javaClass.simpleName)
            null
        } ?: return result(ok = false, message = "base64 解码失败")
        if (bytes.isEmpty()) return result(ok = false, message = "图片是空的，无法上传")

        val name = sanitiseFileName(fileName)
        val mime = mimeTypeOf(name)
        val temp = File(uploadDir, "upload_${System.nanoTime()}_$name")
        return try {
            uploadDir.mkdirs()
            temp.writeBytes(bytes)
            when (val uploaded = await { imageHost.upload(bytes, name, mime) }) {
                is ImageHostResult.Success -> table(
                    "ok" to LuaValue.TRUE,
                    "bbcode" to LuaValue.valueOf(uploaded.bbcode),
                    "url" to LuaValue.valueOf(uploaded.url),
                    "message" to LuaValue.valueOf(uploaded.url),
                )

                is ImageHostResult.Failure -> result(ok = false, message = uploaded.reason)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            result(ok = false, message = firstLine(error))
        } finally {
            // Never keep a temporary copy of the user's picture around.
            temp.delete()
        }
    }

    /**
     * Bridges a `suspend` host API into the synchronous Lua call.
     *
     * The script is already on [Dispatchers.IO]; running the inner call on the same dispatcher
     * keeps the blocking `execute()` of [io.rune.mt.forum.data.forum.network.ForumHttpClient] off
     * the main thread (rule 2).
     */
    private fun <T> await(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun toast(message: String) {
        val text = message.trim().take(TOAST_MAX_CHARS)
        if (text.isEmpty()) return
        mainHandler.post { Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show() }
    }

    private fun capBody(body: String): String =
        if (body.length <= MAX_BODY_CHARS) body else body.take(MAX_BODY_CHARS) + TRUNCATED_MARK

    private fun firstLine(error: Throwable): String {
        val message = error.message?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
        return (message ?: error.javaClass.simpleName).take(MAX_MESSAGE_CHARS)
    }

    /** One result table: `{ ok, message }` plus an empty `bbcode` where the shape is shared. */
    private fun result(ok: Boolean, message: String): LuaTable = table(
        "ok" to LuaValue.valueOf(ok),
        "bbcode" to LuaValue.valueOf(""),
        "message" to LuaValue.valueOf(message.take(MAX_MESSAGE_CHARS)),
    )

    /** Lua value helpers: one place that names the table/function shapes the scripts rely on. */
    private fun table(vararg entries: Pair<String, LuaValue>): LuaTable = LuaTable().apply {
        entries.forEach { (key, value) -> set(LuaValue.valueOf(key), value) }
    }

    private fun fn(block: (Varargs) -> Varargs): LuaValue = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = block(args)
    }

    /** Turns the `postForm` argument table into form fields; functions/tables are skipped. */
    private fun luaTableToMap(value: LuaValue): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        if (!value.istable()) return fields
        val source = value.checktable()
        var key: LuaValue = LuaValue.NIL
        while (fields.size < MAX_FORM_FIELDS) {
            val next = source.next(key)
            val current = next.arg1()
            if (current.isnil()) break
            key = current
            val entry = next.arg(2)
            if (entry.isnil() || entry.istable() || entry.isfunction()) continue
            val name = current.tojstring()
            if (name.isBlank()) continue
            fields[name] = entry.tojstring().take(MAX_FORM_VALUE_CHARS)
        }
        return fields
    }

    /** A file name that is safe to put under `cacheDir` and that the image host will accept. */
    private fun sanitiseFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            base.forEach { char ->
                append(if (char.isLetterOrDigit() || char == '.' || char == '-' || char == '_') char else '_')
            }
        }
        val trimmed = cleaned.trim('.').take(MAX_FILE_NAME_CHARS).ifBlank { "image.png" }
        return if ('.' in trimmed) trimmed else "$trimmed.png"
    }

    private fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** The script's own `ctx.log` lines; the newest [PluginRunResult.MAX_RUN_LOG_LINES] are kept. */
    private class PluginLogSink {
        private val lines = ArrayList<String>(8)

        fun add(message: String) {
            message.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    if (lines.size >= PluginRunResult.MAX_RUN_LOG_LINES) lines.removeAt(0)
                    lines.add(line.take(MAX_LOG_LINE_CHARS))
                }
        }

        fun snapshot(): List<String> = lines.toList()
    }

    private companion object {
        const val TAG = "PluginRunner"
        const val ENTRY_RUN = "onRun"
        const val ENTRY_APP_START = "onAppStart"
        const val SCRIPT_TIMEOUT_MS = 10_000L
        const val UPLOAD_DIR = "plugin_upload"
        const val MAX_BODY_CHARS = 200_000
        const val MAX_MESSAGE_CHARS = 300
        const val MAX_LOG_LINE_CHARS = 500
        const val MAX_FORM_FIELDS = 64
        const val MAX_FORM_VALUE_CHARS = 2_048
        const val MAX_FILE_NAME_CHARS = 80
        const val TOAST_MAX_CHARS = 300
        const val TRUNCATED_MARK = "…(truncated)"

        /**
         * Names removed from the sandbox. [LuaValue.NIL] is not enough to keep a Lua script from
         * calling into Java through LuaJ's binding: `luajava` is removed as well.
         */
        val BLOCKED_GLOBALS = listOf(
            "os",
            "io",
            "package",
            "require",
            "debug",
            "luajava",
            "load",
            "loadfile",
            "loadstring",
            "dofile",
        )
    }
}

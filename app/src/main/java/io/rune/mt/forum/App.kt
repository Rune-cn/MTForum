package io.rune.mt.forum

import io.rune.mt.forum.core.i18n.LocaleManager

import android.content.Context

import android.app.Application
import coil3.SingletonImageLoader
import io.rune.mt.forum.core.image.buildForumImageLoader
import android.os.Build
import com.kieronquinn.monetcompat.core.MonetCompat
import io.rune.mt.forum.core.crash.CrashHandler
import io.rune.mt.forum.core.safemode.SafeMode
import io.rune.mt.forum.core.env.AppConfig
import io.rune.mt.forum.data.forum.repository.ForumSessionStore
import io.rune.mt.forum.data.forum.network.ForumCookieJar
import io.rune.mt.forum.di.init.appModules
import io.rune.mt.forum.domain.forum.repository.ForumRepository
import io.rune.mt.forum.domain.mcp.McpServerController
import io.rune.mt.forum.domain.plugin.PluginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber

class App : Application(), KoinComponent {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val forumRepository by inject<ForumRepository>()
    private val forumSessionStore by inject<ForumSessionStore>()
    private val forumCookieJar by inject<ForumCookieJar>()
    private val mcpServerController by inject<McpServerController>()
    private val pluginRepository by inject<PluginRepository>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }
    override fun onCreate() {
        CrashHandler.init(this)
        // Break a blur-induced crash loop before any UI exists: if the previous run left a
        // crash dump, this session renders without blur.
        SafeMode.evaluate(this)
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            MonetCompat.setup(this)
            MonetCompat.enablePaletteCompat()
            MonetCompat.getInstance().updateMonetColors()
        }

        // Shell: log always enabled so release installs are debuggable too.
        Timber.plant(object : Timber.DebugTree() {
            override fun createStackElementTag(element: StackTraceElement) = super.createStackElementTag(element)
                ?.substringBefore('$')
                ?.take(23)
        })

        startKoin {
            // Koin Android Logger
            androidLogger()
            // Koin Android Context
            androidContext(this@App)
            // use modules
            modules(appModules)
        }

        // Install the image loader after Koin so the session jar is resolved eagerly and
        // deterministically (no "DI might not be ready yet" fallback). The same jar plus acw
        // solver lets Coil load cookie-gated attachments (`forum.php?mod=image`) that the bare
        // CDN loader could only get as an HTML challenge page. Placing this before any UI means
        // no image request can race the setup.
        val sessionJar = forumCookieJar
        SingletonImageLoader.setSafe { ctx -> buildForumImageLoader(ctx, sessionJar) }

        val restoreJob = restoreForumSession()

        // 插件: every enabled script plugin's onAppStart runs once per process, after the session
        // restore has settled and with a short delay so a script that reads
        // ctx.forum.isLoggedIn() sees the restored answer instead of a first-frame "false".
        // The whole pass is isolated: PluginRepositoryImpl runs each plugin in its own try/catch.
        appScope.launch {
            restoreJob.join()
            delay(PLUGIN_START_DELAY_MS)
            runCatching { pluginRepository.runEnabledOnAppStart() }
                .onFailure { error ->
                    Timber.tag("Plugins").w(error, "onAppStart pass failed")
                }
        }

        // Local MCP server (设置 → MCP 服务): the controller observes the persisted switch/port,
        // so this line only arms the observer. With the switch off (the default) no socket is
        // ever bound; turning it on later takes effect without restarting the app.
        mcpServerController.start()

        // After Koin startup the file-logging tree is planted, so this entry
        // is captured both to logcat and to the on-disk run log.
        Timber.i(
            "App start: v=%s code=%d level=%s pid=%d",
            AppConfig.VERSION_NAME,
            AppConfig.VERSION_CODE,
            AppConfig.LEVEL.name,
            android.os.Process.myPid(),
        )
    }

    /**
     * Restores the Discuz cookie set before any screen issues a request.
     *
     * Only login state and uid are logged; cookie names/values must never reach Timber.
     * Persistence after login/logout is owned by the account/login view models so a failed
     * restore does not erase the last good cookie set.
     */
    private fun restoreForumSession(): Job = appScope.launch {
        val cookies = try {
            forumSessionStore.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.tag("ForumSession").i("restore read failed: %s", error.javaClass.simpleName)
            return@launch
        }
        if (cookies.isEmpty()) {
            Timber.tag("ForumSession").i("restore: isLoggedIn=false uid=null")
            return@launch
        }
        try {
            forumRepository.restoreSession(cookies)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.tag("ForumSession").i("restore failed: %s", error.javaClass.simpleName)
        }
        val session = forumRepository.session.value
        Timber.tag("ForumSession").i(
            "restore: isLoggedIn=%s uid=%s",
            session.isLoggedIn,
            session.user?.uid,
        )
    }

    private companion object {
        /**
         * Delay between the session restore and the plugins' `onAppStart`.
         *
         * The restore is asynchronous, and a script that asks `ctx.forum.isLoggedIn()` on the first
         * frame would get `false` even for a member. Two seconds is long enough for the cookie
         * restore on a cold start and short enough to be invisible.
         */
        const val PLUGIN_START_DELAY_MS = 2_000L
    }
}

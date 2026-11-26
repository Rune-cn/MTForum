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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }
    override fun onCreate() {
        CrashHandler.init(this)
        // Break a blur-induced crash loop before any UI exists: if the previous run left a
        // crash dump, this session renders without blur.
        SafeMode.evaluate(this)
        super.onCreate()

        // Forum CDNs filter on UA and Referer, so images need our own loader rather than the
        // Coil default (which left some avatars blank). The session cookie jar is resolved
        // lazily: `setSafe` only stores the factory, and it runs on the first image request —
        // by then `startKoin` below has finished. The jar lets Coil load cookie-gated
        // attachments (`forum.php?mod=image`) and solve the acw challenge on that host.
        SingletonImageLoader.setSafe { ctx ->
            buildForumImageLoader(ctx, runCatching { forumCookieJar }.getOrNull())
        }

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

        restoreForumSession()

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
    private fun restoreForumSession() {
        appScope.launch {
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
    }
}

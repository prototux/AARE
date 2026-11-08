package net.prototux.aare.xposed

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Hooks loaded into a media app that only serves its Android Auto browse tree to Google's projection
 * host. Such an app (Spotify, VRadio, …) exposes a `MediaBrowserService` and, in `onGetRoot`, returns
 * a browsable root only when the caller is Android Auto — checked by package name, or by package name
 * plus signing certificate (the AOSP media sample's `PackageValidator`). AARE's own package is on
 * neither allowlist, so it is refused with a `SecurityException` or an empty root.
 *
 * On a phone where Android Auto's package is `com.google.android.projection.gearhead`, this makes
 * AARE's uid look like that caller to the app:
 *  - `onGetRoot(package, uid, hints)` is called with the package rewritten to Android Auto's when the
 *    caller is AARE, so a name-only check passes;
 *  - `getPackagesForUid` / `getNameForUid` map AARE's uid to Android Auto's package, so a validator
 *    that cross-checks the uid against the package (and then its signature) resolves Android Auto's
 *    real certificate instead of AARE's.
 *
 * Only AARE's uid is affected. A signature-checking validator still needs Android Auto installed so
 * its certificate is present to match; a name-only app (VRadio) needs only the first hook.
 */
object MediaHooks {

    private const val GEARHEAD = "com.google.android.projection.gearhead"

    /** Legacy framework browse service, and the media3 session/library services. */
    private val MEDIA_SERVICE_ACTIONS = listOf(
        "android.media.browse.MediaBrowserService",
        "androidx.media3.session.MediaLibraryService",
        "androidx.media3.session.MediaSessionService",
    )

    /** Installs the media hooks if [param]'s app exposes a media browse/session service; else no-op. */
    fun install(xposed: XposedInterface, param: PackageLoadedParam) {
        val uid = aareUid
        if (uid < 0) return
        val legacy = services(param.packageName, "android.media.browse.MediaBrowserService")
        val anyMedia = MEDIA_SERVICE_ACTIONS.any { services(param.packageName, it).isNotEmpty() }
        if (!anyMedia) return
        val cl = param.defaultClassLoader
        legacy.forEach { className -> hookOnGetRoot(xposed, cl, className, uid) }
        hookMedia3Controller(xposed, cl)
        hookUidToPackage(xposed, cl, uid)
    }

    /**
     * media3's `MediaSession.ControllerInfo.getPackageName()` → Android Auto's package when the
     * controller is AARE, so a media3 `MediaLibraryService` that checks the connecting controller's
     * package in `onConnect` accepts AARE. (A validator that also checks the signature still relies
     * on the uid→package mapping below, and so on Android Auto being installed for its certificate.)
     */
    private fun hookMedia3Controller(xposed: XposedInterface, cl: ClassLoader) {
        val cls = runCatching { cl.loadClass("androidx.media3.session.MediaSession\$ControllerInfo") }.getOrNull() ?: return
        val method = runCatching { cls.getMethod("getPackageName") }.getOrNull() ?: return
        xposed.hook(method).intercept { chain ->
            val pkg = chain.proceed()
            if (pkg == ModuleMain.AARE_PACKAGE) GEARHEAD else pkg
        }
    }

    /** `onGetRoot(String package, int uid, Bundle)` → called with Android Auto's package for AARE's uid. */
    private fun hookOnGetRoot(xposed: XposedInterface, cl: ClassLoader, className: String, uid: Int) {
        val cls = runCatching { cl.loadClass(className) }.getOrNull() ?: return
        val method = runCatching {
            cls.getMethod("onGetRoot", String::class.java, Int::class.javaPrimitiveType, Bundle::class.java)
        }.getOrNull() ?: return
        xposed.hook(method).intercept { chain ->
            if (chain.getArg(1) == uid) {
                val args = chain.args.toTypedArray()
                args[0] = GEARHEAD
                chain.proceed(args)
            } else {
                chain.proceed()
            }
        }
    }

    /** Map AARE's uid to Android Auto's package for the app's uid→package lookups. */
    private fun hookUidToPackage(xposed: XposedInterface, cl: ClassLoader, uid: Int) {
        val pm = runCatching { cl.loadClass("android.app.ApplicationPackageManager") }.getOrNull() ?: return
        runCatching { pm.getMethod("getPackagesForUid", Int::class.javaPrimitiveType) }.getOrNull()?.let { m ->
            xposed.hook(m).intercept { chain ->
                if (chain.getArg(0) == uid) arrayOf(GEARHEAD) else chain.proceed()
            }
        }
        runCatching { pm.getMethod("getNameForUid", Int::class.javaPrimitiveType) }.getOrNull()?.let { m ->
            xposed.hook(m).intercept { chain ->
                if (chain.getArg(0) == uid) GEARHEAD else chain.proceed()
            }
        }
    }

    /** The app's own service class names answering [action], from its manifest. */
    private fun services(packageName: String, action: String): List<String> = runCatching {
        val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
        val intent = Intent(action).setPackage(packageName)
        @Suppress("DEPRECATION")
        ctx.packageManager.queryIntentServices(intent, 0).mapNotNull { it.serviceInfo?.name }
    }.getOrDefault(emptyList())

    /** AARE's app uid, resolved once from this app process's package manager; -1 if absent. */
    private val aareUid: Int by lazy {
        runCatching {
            val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            ctx.packageManager.getApplicationInfo(ModuleMain.AARE_PACKAGE, 0).uid
        }.getOrDefault(-1)
    }
}

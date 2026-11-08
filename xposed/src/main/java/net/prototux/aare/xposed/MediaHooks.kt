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
    private const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"

    /** Installs the media hooks if [param]'s app exposes a MediaBrowserService; otherwise no-op. */
    fun install(xposed: XposedInterface, param: PackageLoadedParam) {
        val uid = aareUid
        if (uid < 0) return
        val services = mediaBrowserServices(param.packageName)
        if (services.isEmpty()) return
        val cl = param.defaultClassLoader
        services.forEach { className -> hookOnGetRoot(xposed, cl, className, uid) }
        hookUidToPackage(xposed, cl, uid)
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

    /** The app's own MediaBrowserService class names, from its manifest. */
    private fun mediaBrowserServices(packageName: String): List<String> = runCatching {
        val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
        val intent = Intent(MEDIA_BROWSER_SERVICE).setPackage(packageName)
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

package net.prototux.aare.xposed

import android.os.Binder
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Hooks loaded into a projection client app (anything in scope that isn't AARE or the system).
 *
 * Two kinds of app refuse a non-Google host, each with its own gate and one hook:
 *
 *  - **Car App Library apps** (Waze, and any `androidx.car.app` app with a host allowlist) reject a
 *    host whose signing certificate isn't on their list, in `HostValidator.isValidHost`.
 *  - **Google Maps** uses the private `ICarProjection` protocol and checks `CAPTURE_VIDEO_OUTPUT`
 *    (a signature permission an ordinary app can't hold) on the binding host, inside its own
 *    process, on every projection transaction.
 *
 * Each hook is scoped to AARE as the host and falls through to the original otherwise, so a real
 * Google Android Auto session is never affected.
 */
object AppProcessHooks {

    /** Google Maps: the app that uses the private ICarProjection protocol. */
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"

    private const val CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
    private const val PERMISSION_GRANTED = 0 // PackageManager.PERMISSION_GRANTED

    fun install(xposed: XposedInterface, param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        hookHostValidator(xposed, cl)
        if (param.packageName == MAPS_PACKAGE) hookCaptureVideoOutput(xposed, cl)
    }

    /**
     * `HostValidator.isValidHost(HostInfo)` → true when the host is AARE, so a Car App Library app
     * accepts AARE despite its non-Google signing certificate. (Waze's rejection is exactly this.)
     */
    private fun hookHostValidator(xposed: XposedInterface, cl: ClassLoader) {
        val hostValidator = runCatching { cl.loadClass("androidx.car.app.HostValidator") }.getOrNull() ?: return
        val hostInfo = runCatching { cl.loadClass("androidx.car.app.HostInfo") }.getOrNull() ?: return
        val method = runCatching { hostValidator.getDeclaredMethod("isValidHost", hostInfo) }.getOrNull() ?: return
        xposed.hook(method).intercept { chain ->
            if (hostPackage(chain.getArg(0)) == ModuleMain.AARE_PACKAGE) true else chain.proceed()
        }
    }

    /**
     * `ContextImpl.checkCallingPermission(CAPTURE_VIDEO_OUTPUT)` → granted when the IPC caller is
     * AARE. Google Maps runs this check on the host that binds its projection service; granting it
     * only for AARE's uid lets AARE host Maps' projected activity, and leaves every other caller and
     * every other permission untouched.
     */
    private fun hookCaptureVideoOutput(xposed: XposedInterface, cl: ClassLoader) {
        val contextImpl = runCatching { cl.loadClass("android.app.ContextImpl") }.getOrNull() ?: return
        val method = runCatching {
            contextImpl.getDeclaredMethod("checkCallingPermission", String::class.java)
        }.getOrNull() ?: return
        val uid = aareUid
        if (uid < 0) return
        xposed.hook(method).intercept { chain ->
            if (chain.getArg(0) == CAPTURE_VIDEO_OUTPUT && Binder.getCallingUid() == uid) {
                PERMISSION_GRANTED
            } else {
                chain.proceed()
            }
        }
    }

    /** The package name a `HostInfo` holds, read from its backing field (its getter is what we hook). */
    private fun hostPackage(hostInfo: Any?): String? = runCatching {
        hostInfo?.javaClass?.getDeclaredField("mPackageName")?.apply { isAccessible = true }?.get(hostInfo) as? String
    }.getOrNull()

    /** AARE's app uid, resolved once from this (client-app) process's package manager; -1 if absent. */
    private val aareUid: Int by lazy {
        runCatching {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as android.content.Context
            ctx.packageManager.getApplicationInfo(ModuleMain.AARE_PACKAGE, 0).uid
        }.getOrDefault(-1)
    }
}

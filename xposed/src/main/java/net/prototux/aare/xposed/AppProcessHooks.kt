package net.prototux.aare.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Hooks loaded into a projection client app (anything in scope that isn't AARE or the system).
 *
 * Two app-side gates keep a non-Google host out, and each has one hook:
 *  - the Car App Library's own `HostValidator`, which checks the host's package name and signing
 *    certificate against an allowlist (AARE's certificate isn't Google's, so it fails);
 *  - an app's own check that the connected host package is Google's Android Auto.
 *
 * Both are only relaxed for AARE as the host; a real Google session is unaffected because every
 * hook falls through to the original result (`chain.proceed()`) unless the host is AARE.
 */
object AppProcessHooks {

    fun install(xposed: XposedInterface, param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        hookHostValidator(xposed, cl)
        hookHostInfoPackageName(xposed, cl)
    }

    /** `HostValidator.isValidHost(HostInfo)` → true when the host is AARE. */
    private fun hookHostValidator(xposed: XposedInterface, cl: ClassLoader) {
        val hostValidator = runCatching { cl.loadClass("androidx.car.app.HostValidator") }.getOrNull() ?: return
        val hostInfo = runCatching { cl.loadClass("androidx.car.app.HostInfo") }.getOrNull() ?: return
        val method = runCatching { hostValidator.getDeclaredMethod("isValidHost", hostInfo) }.getOrNull() ?: return
        xposed.hook(method).intercept { chain ->
            if (hostPackage(chain.getArg(0)) == ModuleMain.AARE_PACKAGE) true else chain.proceed()
        }
    }

    /**
     * `HostInfo.getPackageName()` → Google's package when the real host is AARE, so an app that
     * only compares the host package name string (not its signature) treats AARE as Google's host.
     */
    private fun hookHostInfoPackageName(xposed: XposedInterface, cl: ClassLoader) {
        val hostInfo = runCatching { cl.loadClass("androidx.car.app.HostInfo") }.getOrNull() ?: return
        val method = runCatching { hostInfo.getDeclaredMethod("getPackageName") }.getOrNull() ?: return
        xposed.hook(method).intercept { chain ->
            if (hostPackage(chain.thisObject) == ModuleMain.AARE_PACKAGE) ModuleMain.GEARHEAD_PACKAGE else chain.proceed()
        }
    }

    /** The package name a `HostInfo` holds, read from its backing field (its getter is what we hook). */
    private fun hostPackage(hostInfo: Any?): String? = runCatching {
        hostInfo?.javaClass?.getDeclaredField("mPackageName")?.apply { isAccessible = true }?.get(hostInfo) as? String
    }.getOrNull()
}

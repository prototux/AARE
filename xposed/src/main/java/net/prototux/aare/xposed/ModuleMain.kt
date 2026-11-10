package net.prototux.aare.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed entry point (listed in META-INF/xposed/java_init.list). Routes each hooked process to
 * the hooks meant for it: system_server on its own callback, every other scoped app on package load.
 *
 * Every hook here is a compatibility shim for AARE itself (package [AARE_PACKAGE]), on a phone its
 * owner has rooted: it lets AARE stand in for Google's Android Auto host where an app or the system
 * only recognises Google's. Nothing targets another app's data or another user, and every grant is
 * gated on AARE's own package or uid, so an app the user didn't install AARE for is left untouched.
 *
 * The framework instantiates this with the no-arg constructor and attaches itself, so the hooking
 * methods inherited from [XposedModule] (such as `hook`) are usable from the callbacks below.
 */
class ModuleMain : XposedModule() {

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // One classloader per process is enough; skip extra loads of the same package.
        if (!param.isFirstPackage) return
        // Don't hook AARE's own process: it already knows it is the host.
        if (param.packageName == AARE_PACKAGE) return
        AppProcessHooks.install(this, param)
        MediaHooks.install(this, param)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        SystemHooks.install(this, param.classLoader)
    }

    companion object {
        const val AARE_PACKAGE = "net.prototux.aare"
    }
}

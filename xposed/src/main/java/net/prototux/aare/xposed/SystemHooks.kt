package net.prototux.aare.xposed

import android.app.AppOpsManager
import io.github.libxposed.api.XposedInterface

/**
 * Hooks loaded into system_server. They give AARE's own package what the Magisk privileged-app
 * module and the per-session consent dialog otherwise provide, so a rooted phone can skip both:
 *
 *  - AARE's declared privileged permissions ([AARE_PRIVILEGED_PERMISSIONS]) report as granted;
 *  - the `PROJECT_MEDIA` app-op is allowed, so `createScreenCaptureIntent()` returns a token
 *    without the system consent dialog.
 *
 * Both are gated on AARE's package/uid, and each hook falls through to the original result for
 * anything else. Framework method names drift between Android versions, so each hook is best-effort:
 * a target that isn't found is skipped rather than crashing system_server. Permissions the platform
 * enforces natively (audio capture in audioserver) still need the native privileged grant; this
 * only covers the framework-level checks.
 */
object SystemHooks {

    /** Must match tools/magisk/privapp-permissions-aare.xml. */
    private val AARE_PRIVILEGED_PERMISSIONS = setOf(
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.MODIFY_AUDIO_ROUTING",
    )

    private const val PERMISSION_GRANTED = 0 // PackageManager.PERMISSION_GRANTED

    fun install(xposed: XposedInterface, cl: ClassLoader) {
        hookAppOps(xposed, cl)
        hookPermissionCheck(xposed, cl)
    }

    /** `AppOpsService.checkOperation(code, uid, packageName)` → ALLOWED for AARE's PROJECT_MEDIA. */
    private fun hookAppOps(xposed: XposedInterface, cl: ClassLoader) {
        val cls = runCatching { cl.loadClass("com.android.server.appop.AppOpsService") }.getOrNull() ?: return
        val method = runCatching {
            cls.getDeclaredMethod("checkOperation", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        }.getOrNull() ?: return
        val op = projectMediaOp
        if (op < 0) return
        xposed.hook(method).intercept { chain ->
            val code = chain.getArg(0) as Int
            val pkg = chain.getArg(2) as? String
            if (pkg == ModuleMain.AARE_PACKAGE && code == op) AppOpsManager.MODE_ALLOWED else chain.proceed()
        }
    }

    /**
     * The framework permission check for a uid. Its home and signature move between versions
     * (`PermissionManagerService`, then `…ServiceImpl`), so try the known classes and hook the first
     * `checkUidPermission(String, int, …) : int` that resolves.
     */
    private fun hookPermissionCheck(xposed: XposedInterface, cl: ClassLoader) {
        val classNames = listOf(
            "com.android.server.pm.permission.PermissionManagerServiceImpl",
            "com.android.server.pm.permission.PermissionManagerService",
        )
        for (name in classNames) {
            val cls = runCatching { cl.loadClass(name) }.getOrNull() ?: continue
            val method = cls.declaredMethods.firstOrNull {
                it.name == "checkUidPermission" &&
                    it.returnType == Int::class.javaPrimitiveType &&
                    it.parameterTypes.contains(String::class.java) &&
                    it.parameterTypes.contains(Int::class.javaPrimitiveType)
            } ?: continue
            val uid = aareUid
            if (uid < 0) return
            xposed.hook(method).intercept { chain ->
                val permission = chain.args.firstOrNull { it is String } as? String
                val callerUid = chain.args.firstOrNull { it is Int } as? Int
                if (permission in AARE_PRIVILEGED_PERMISSIONS && callerUid == uid) PERMISSION_GRANTED else chain.proceed()
            }
            return
        }
    }

    /** `AppOpsManager.OP_PROJECT_MEDIA` is @hide; read it reflectively (system_server is unrestricted). */
    private val projectMediaOp: Int by lazy {
        runCatching {
            AppOpsManager::class.java.getDeclaredField("OP_PROJECT_MEDIA").apply { isAccessible = true }.getInt(null)
        }.getOrDefault(-1)
    }

    /**
     * AARE's app uid, resolved once in system_server. `currentApplication()` is usually null there,
     * so ask the package manager service directly (`AppGlobals.getPackageManager().getPackageUid`),
     * whose overloads vary by version; -1 if none resolves, which skips the permission hook.
     */
    private val aareUid: Int by lazy {
        runCatching {
            val ipm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null)
            val m = ipm.javaClass.methods.first {
                it.name == "getPackageUid" && it.returnType == Int::class.javaPrimitiveType &&
                    it.parameterTypes.firstOrNull() == String::class.java
            }
            val extra: Array<Any> = when (m.parameterTypes.size) {
                3 -> arrayOf(0L, 0)   // (String, long flags, int userId)
                else -> arrayOf(0, 0) // (String, int flags, int userId)
            }
            m.invoke(ipm, ModuleMain.AARE_PACKAGE, *extra) as Int
        }.getOrDefault(-1)
    }
}

package net.prototux.aare.setup

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.prototux.aare.BuildConfig
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import rikka.shizuku.Shizuku

/**
 * AARE's side of the Shizuku setup: binds [ShellSetupService] for one task, runs its commands,
 * logs each one with its result in the connection log, and checks the outcome itself.
 * Every call blocks: run them off the main thread.
 */
class ShizukuSetupClient(context: Context) {
    private val context = context.applicationContext

    /** One line of the outcome: [label] (a permission's name, or notification access) and how it went. */
    data class Item(val label: String, val ok: Boolean, val detail: String)

    /** What the "Grant AARE's permissions" step would grant now; empty when everything is granted. */
    fun missingPermissions(): List<String> = ShizukuSetup.toGrant(SetupPermissions.forSdk(Build.VERSION.SDK_INT)) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun notificationAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Grants each missing permission and the notification access; null if the helper didn't start. */
    fun grantAll(): List<Item>? = withHelper { s ->
        val items = missingPermissions().map { p ->
            val r = logged(s.grantPermission(p))
            val granted = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            Item(permissionLabel(p), granted, if (granted) "" else r.failure())
        }
        if (notificationAccess()) items
        else {
            val r = logged(s.allowNotificationListener())
            val allowed = notificationAccess() || r.ok
            items + Item(context.getString(R.string.shizuku_setup_notification_access), allowed, if (allowed) "" else r.failure())
        }
    }

    fun androidAutoState(): ShizukuSetup.AndroidAutoState = ShizukuSetup.androidAutoState(
        runCatching { context.packageManager.getApplicationEnabledSetting(ShizukuSetup.ANDROID_AUTO) }.getOrNull(),
    )

    /** pm enable / pm disable-user for Android Auto; null if it worked, else what went wrong. */
    fun setAndroidAutoEnabled(enabled: Boolean): String? {
        val r = withHelper { logged(it.setAndroidAutoEnabled(enabled)) } ?: return context.getString(R.string.shizuku_setup_helper_failed)
        val want = if (enabled) ShizukuSetup.AndroidAutoState.ENABLED else ShizukuSetup.AndroidAutoState.DISABLED
        return if (androidAutoState() == want) null else r.failure()
    }

    private class Result(val command: String, val exit: Int, val output: String) {
        val ok get() = ShizukuSetup.succeeded(exit, output)
        fun failure() = output.lineSequence().firstOrNull { it.isNotBlank() } ?: "exit code $exit"
    }

    private fun logged(b: Bundle): Result {
        val r = Result(b.getString(ShellSetupService.KEY_COMMAND).orEmpty(), b.getInt(ShellSetupService.KEY_EXIT, -1),
            b.getString(ShellSetupService.KEY_OUTPUT).orEmpty())
        DiagLog.add("Shizuku setup: ${r.command} -> ${if (r.ok) "ok" else "failed (${r.exit})"}" +
            if (r.output.isNotEmpty()) ": ${r.output.lineSequence().first()}" else "")
        return r
    }

    private fun permissionLabel(p: String): String = runCatching {
        context.packageManager.getPermissionInfo(p, 0).loadLabel(context.packageManager).toString()
            .replaceFirstChar { it.uppercase() }
    }.getOrDefault(p.substringAfterLast('.'))

    /** Binds the helper, runs [block], and removes the helper again (it isn't needed afterwards). */
    private fun <T> withHelper(block: (IShellSetup) -> T): T? {
        if (!available()) return null
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellSetupService::class.java.name))
            .daemon(false)
            .processNameSuffix("setup")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val bound = CountDownLatch(1)
        var service: IShellSetup? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder?.takeIf { it.pingBinder() }?.let(IShellSetup.Stub::asInterface)
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
            Log.w(TAG, "Shizuku bind", it)
            DiagLog.add("Shizuku setup: the helper didn't start")
            return null
        }
        try {
            val s = if (bound.await(8, TimeUnit.SECONDS)) service else null
            if (s == null) { DiagLog.add("Shizuku setup: the helper didn't start"); return null }
            return runCatching { block(s) }.onFailure { Log.w(TAG, "setup helper", it) }.getOrNull()
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    companion object {
        private const val TAG = "AareShizukuSetup"

        /** Shizuku is running and AARE is allowed to use it, on any Android version AARE runs on. */
        fun available(): Boolean = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
}

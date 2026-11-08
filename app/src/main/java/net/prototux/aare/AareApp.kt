package net.prototux.aare

import android.app.Application
import com.topjohnwu.superuser.Shell

class AareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagLog.init(this)
        // libsu, for the su-backed audio capture (CaptureMethod.SHIZUKU via SuSource). Lazy: this
        // configures the shell but doesn't start it, so it never asks for root on its own.
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10))
    }

    companion object {
        lateinit var instance: AareApp
            private set
    }
}

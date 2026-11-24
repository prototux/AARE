package net.prototux.aare

import android.app.Application
import android.os.Build
import net.prototux.aare.devlog.DevLog

class AareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // First, before anything writes a setting: it tells a new install from an update.
        Settings(this).settleDefaults()
        DiagLog.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings(this).developerMode) DevLog.start(this)
    }

    companion object {
        lateinit var instance: AareApp
            private set
    }
}

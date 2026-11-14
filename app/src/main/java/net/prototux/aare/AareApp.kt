package net.prototux.aare

import android.app.Application
import net.prototux.aare.devlog.DevLog

class AareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagLog.init(this)
        if (Settings(this).developerMode) DevLog.start(this)
    }

    companion object {
        lateinit var instance: AareApp
            private set
    }
}

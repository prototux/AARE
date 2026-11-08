package net.prototux.aare.audio

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/**
 * libsu starts this in a root process (via `su`), the same way Shizuku starts [ShellAudioService]
 * in its shell process. It exposes the same capture; [ShellAudioService] drops the root process to
 * the shell uid before recording, so CAPTURE_AUDIO_OUTPUT is granted.
 */
class RootAudioService : RootService() {
    override fun onBind(intent: Intent): IBinder = ShellAudioService(this)
}

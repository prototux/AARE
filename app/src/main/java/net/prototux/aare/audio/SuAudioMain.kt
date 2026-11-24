package net.prototux.aare.audio

import android.os.Looper
import net.prototux.aare.root.RootHandover

/**
 * The root audio helper, run by [RootAudio] the way scrcpy and Shizuku run a helper:
 * `su -c "CLASSPATH=<apk> app_process / net.prototux.aare.audio.SuAudioMain <appPackage>"`.
 *
 * It serves the very [ShellAudioService] the Shizuku helper does, dropped to the shell uid first
 * (see [dropRootToShell]): the same binder, so root gets everything Shizuku does. The binder goes
 * back to AARE by a broadcast only this start's token opens ([RootHandover]). The helper ends when
 * AARE closes its stdin, or dies, after stopping every capture (the phone gets its sound back).
 * No minification in release (optimization disabled), so the class survives under this exact name.
 */
object SuAudioMain {
    const val ACTION = "net.prototux.aare.AUDIO_HELPER"

    @JvmStatic
    fun main(args: Array<String>) {
        val appPackage = args.getOrNull(0) ?: return
        val token = RootHandover.readToken() ?: return
        // A bare app_process main thread has no Looper, and the system context (ActivityThread's
        // systemMain, behind ShellAudioService's context and AudioManager) creates a Handler on
        // the calling thread: without this it fails with "Can't create handler inside thread ...
        // that has not called Looper.prepare()". Shizuku's process has one already.
        Looper.prepareMainLooper()
        // Before anything else: the policy's recorder needs the shell's CAPTURE_AUDIO_OUTPUT. If it
        // can't, each capture fails, and says why in lastFailure().
        runCatching { dropRootToShell() }.onFailure { System.err.println("could not drop to the shell uid: ${describeFailure(it)}") }
        val service = ShellAudioService(null)
        // Sent as the shell, which the process now is: a package of another uid would be refused.
        val ctx = ShellContext(service.systemContext())
        if (!RootHandover.handBack(ctx, ACTION, appPackage, token, service)) service.destroy()
        Thread {
            runCatching { while (System.`in`.read() >= 0) Unit }
            service.destroy() // stops every capture, then exits
        }.apply { isDaemon = true }.start()
        Looper.loop()
    }
}

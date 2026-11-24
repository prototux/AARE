package net.prototux.aare.audio

import android.os.Looper
import android.os.Process
import android.system.Os
import net.prototux.aare.root.RootHandover

/**
 * The root audio helper, run by [RootAudio] the way scrcpy and Shizuku run a helper:
 * `su -c "CLASSPATH=<apk> app_process / net.prototux.aare.audio.SuAudioMain <appPackage>"`.
 *
 * It serves the very [ShellAudioService] the Shizuku helper does, as the shell uid (see
 * [asShellFromTheStart]): the same binder, so root gets everything Shizuku does. The binder goes
 * back to AARE by a broadcast only this start's token opens ([RootHandover]). The helper ends when
 * AARE closes its stdin, or dies, after stopping every capture (the phone gets its sound back).
 * No minification in release (optimization disabled), so the class survives under this exact name.
 */
object SuAudioMain {
    const val ACTION = "net.prototux.aare.AUDIO_HELPER"

    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() == 0) return asShellFromTheStart(args)
        val appPackage = args.getOrNull(0) ?: return
        val token = RootHandover.readToken() ?: return
        // A bare app_process main thread has no Looper, and the system context (ActivityThread's
        // systemMain, behind ShellAudioService's context and AudioManager) creates a Handler on
        // the calling thread: without this it fails with "Can't create handler inside thread ...
        // that has not called Looper.prepare()". Shizuku's process has one already.
        Looper.prepareMainLooper()
        val service = ShellAudioService(null)
        val system = service.systemContext()
        runCatching { actAsShellApp(system) }.onFailure { System.err.println("could not act as the shell app: ${describeFailure(it)}") }
        // Sent as the shell, which the process is: a package of another uid would be refused.
        val ctx = ShellContext(system)
        if (!RootHandover.handBack(ctx, ACTION, appPackage, token, service)) service.destroy()
        Thread {
            runCatching { while (System.`in`.read() >= 0) Unit }
            service.destroy() // stops every capture, then exits
        }.apply { isDaemon = true }.start()
        Looper.loop()
    }

    /**
     * Starts this helper again as the shell, in this process (exec): the policy's recorder needs the
     * shell's CAPTURE_AUDIO_OUTPUT, and every thread has to be the shell. Changing the uid in place
     * isn't enough: it changes only the calling thread, and app_process started its binder threads,
     * which serve AARE's calls, before main; the policy then failed with "Calling uid: 2000 doesn't
     * match source uid: 0". AARE's token is still unread on stdin, which the new image keeps.
     */
    private fun asShellFromTheStart(args: Array<String>) {
        try {
            Os.setgid(SHELL_UID)
            Os.setuid(SHELL_UID)
            // CLASSPATH (AARE's APK) comes with the environment.
            val env = System.getenv().map { (k, v) -> "$k=$v" }.toTypedArray()
            Os.execve(APP_PROCESS, arrayOf(APP_PROCESS, "/", SuAudioMain::class.java.name) + args, env)
        } catch (e: Exception) {
            System.err.println("could not start again as the shell: ${describeFailure(e)}")
        }
    }

    private const val SHELL_UID = 2000
    private const val APP_PROCESS = "/system/bin/app_process"
}

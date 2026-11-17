package net.prototux.aare.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import net.prototux.aare.DiagLog

/**
 * Puts phone calls on the phone's own loudspeaker and microphone while connected to the car
 * (Settings.callsOnPhoneSpeaker, off by default).
 *
 * Calls normally go wherever Android routes them, usually the car's Bluetooth hands-free link. Some
 * setups have no working hands-free microphone (wireless adapters that don't pass it through, head
 * units with a broken one), so the other side hears nothing. With this on, every call that goes
 * off-hook during the session is moved to the phone's speaker, and handed back to Android's choice
 * once it ends or the session does.
 *
 * Telecom owns the route of a cellular call and may move it again (a Bluetooth device connecting
 * mid-call, the user picking another route on the phone): the speaker is asked for once more a
 * moment after the call starts, when the hands-free link has usually settled, but not fought over.
 */
class CallSpeaker(context: Context) {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** This class moved the current call to the speaker, so it is the one to undo it. */
    private var routed = false
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    toSpeaker()
                    main.postDelayed(::toSpeaker, SETTLE_MS)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> release()
            }
        }
    }

    fun start() {
        if (started || audio == null) return
        started = true
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "no call-state broadcasts", it) }
        // A call already going on when the car connects moves too.
        if (Calls.state(context) == TelephonyManager.CALL_STATE_OFFHOOK) toSpeaker()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        release()
    }

    @Suppress("DEPRECATION") // isSpeakerphoneOn is the only way before Android 12
    private fun toSpeaker() {
        val am = audio ?: return
        if (!started) return
        main.removeCallbacksAndMessages(null)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                speaker != null && am.setCommunicationDevice(speaker)
            } else {
                am.isSpeakerphoneOn = true
                true
            }
        }.getOrDefault(false)
        if (ok && !routed) DiagLog.add("calls: on the phone's speaker and microphone")
        if (!ok) DiagLog.add("calls: Android didn't move the call to the phone's speaker")
        routed = routed || ok
    }

    @Suppress("DEPRECATION")
    private fun release() {
        main.removeCallbacksAndMessages(null)
        if (!routed) return
        routed = false
        val am = audio ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false
        }.onFailure { Log.w(TAG, "release", it) }
    }

    private companion object {
        const val TAG = "AareCallSpeaker"
        /** How long after going off-hook the speaker is asked for again, once Bluetooth has settled. */
        const val SETTLE_MS = 1_500L
    }
}

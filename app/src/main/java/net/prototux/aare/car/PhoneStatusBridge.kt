package net.prototux.aare.car

import aap_protobuf.service.phonestatus.message.PhoneStatusInputOuterClass.PhoneStatusInput
import aap_protobuf.service.phonestatus.message.PhoneStatusOuterClass.PhoneStatus
import aap_protobuf.shared.InstrumentClusterInputOuterClass.InstrumentClusterInput.InstrumentClusterAction
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.protobuf.ByteString
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.channels.PhoneStatusChannel
import net.prototux.aare.aap.channels.sendBatteryStatus
import java.io.ByteArrayOutputStream

/**
 * The phone's own state for the car: the current call and the signal on the phone status channel
 * (head units that draw their own phone screen or show calls in the cluster), and the battery
 * level and whether calls are possible on the control channel.
 *
 * Android only tells a non-dialer app about one call (idle, ringing or off-hook), so at most one
 * call is reported. Everything runs on its own thread: contact lookups read the disk.
 */
@SuppressLint("MissingPermission") // every permission is checked, or the call is wrapped
class PhoneStatusBridge(
    private val context: Context,
    private val aap: AapSession,
    /** Null when the head unit has no phone status service: battery and call availability still go out. */
    private val channel: PhoneStatusChannel?,
) {
    private val thread = HandlerThread("aare-phonestatus").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var running = false

    private var lastBattery = -1
    private var lastCritical = false
    private var lastSent: PhoneStatus? = null

    private var callState = TelephonyManager.CALL_STATE_IDLE
    /** When the call went off-hook (elapsedRealtime), for its duration. */
    private var callSinceMs = 0L
    /** The current call already went off-hook (answered or dialled): [callSinceMs] is its start. */
    private var wasOffhook = false
    /** The number the phone-state broadcast gave for the current call (needs READ_CALL_LOG). */
    private var heardNumber: String? = null
    /** The active call's number: kept across a waiting call's RINGING/OFFHOOK blip so declining it doesn't lose it. */
    private var activeNumber: String? = null
    /** Whoever is ringing in right now: a fresh incoming call, or a second one waiting. */
    private var ringingNumber: String? = null

    private class Contact(val name: String?, val type: String?, val photo: ByteArray?)
    private val contacts = HashMap<String, Contact?>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onBattery(i)
    }
    private val callReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION") // EXTRA_INCOMING_NUMBER: still the only number a non-dialer app gets
        override fun onReceive(c: Context, i: Intent) {
            // Sent twice with READ_CALL_LOG, once with the number and once without; an idle one
            // must not leave a number behind for the next call.
            val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (i.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_IDLE && !number.isNullOrBlank()) heardNumber = number
            refresh()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            // Every second during a call, for its duration; otherwise just signal and availability.
            handler.postDelayed(this, if (callState == TelephonyManager.CALL_STATE_IDLE) IDLE_TICK_MS else CALL_TICK_MS)
        }
    }

    fun start() {
        running = true
        // Both are system broadcasts; the battery one is sticky, so the current level comes at once.
        runCatching { ContextCompat.registerReceiver(context, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler, ContextCompat.RECEIVER_EXPORTED) }
            .onFailure { Log.w(TAG, "no battery updates", it) }
        runCatching { ContextCompat.registerReceiver(context, callReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), null, handler, ContextCompat.RECEIVER_EXPORTED) }
            .onFailure { Log.w(TAG, "no call-state broadcasts", it) }
        handler.post(tick)
    }

    fun stop() {
        running = false
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(callReceiver) }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    /** The car's own call buttons. Called on the session's reader thread. */
    fun onInput(input: PhoneStatusInput) {
        handler.post {
            val state = Calls.state(context)
            Log.i(TAG, "car phone input ${input.input.action} (call state $state)")
            when (input.input.action) {
                InstrumentClusterAction.ENTER, InstrumentClusterAction.CALL -> if (state == TelephonyManager.CALL_STATE_RINGING) Calls.answer(context)
                // Only decline a ringing call: BACK must never hang up an active one just because
                // the driver left the cluster screen.
                InstrumentClusterAction.BACK -> if (state == TelephonyManager.CALL_STATE_RINGING) Calls.end(context)
                // Placing a call from the car's input is left out: a stray press must never dial.
                else -> Unit
            }
            refresh()
        }
    }

    // ------------------------------------------------------------------ battery

    private fun onBattery(i: Intent) {
        if (!running) return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        if (level < 0) return
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        val percent = level * 100 / scale
        val critical = percent <= CRITICAL_PERCENT && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0
        // The broadcast comes for every voltage and temperature change: only the level matters.
        if (percent == lastBattery && critical == lastCritical) return
        lastBattery = percent
        lastCritical = critical
        aap.sendBatteryStatus(percent, critical)
    }

    // ------------------------------------------------------------------ calls and signal

    private fun refresh() {
        if (!running || aap.isClosed) return
        val ch = channel ?: return
        val state = Calls.state(context)
        val now = SystemClock.elapsedRealtime()
        if (state != callState) {
            // The duration counts from the answer (or the dial), not from the first ring. Call
            // waiting goes OFFHOOK -> RINGING -> OFFHOOK: only the call's first off-hook starts
            // it, not the waiting call's blip on an already-active one.
            if (state == TelephonyManager.CALL_STATE_OFFHOOK && !wasOffhook) { callSinceMs = now; wasOffhook = true }
            if (state == TelephonyManager.CALL_STATE_IDLE) { heardNumber = null; activeNumber = null; ringingNumber = null; wasOffhook = false }
            // Straight to off-hook is an outgoing call, which may not say its number: if it was
            // just dialled from the car, use that.
            if (callState == TelephonyManager.CALL_STATE_IDLE && state == TelephonyManager.CALL_STATE_OFFHOOK) {
                activeNumber = Calls.lastPlaced?.takeIf { now - it.second < 30_000 }?.first
            }
            // A ringing call just got answered: it becomes the active one. If there was already
            // an active number, this OFFHOOK is a waiting call being declined, not an answer.
            if (callState == TelephonyManager.CALL_STATE_RINGING && state == TelephonyManager.CALL_STATE_OFFHOOK && activeNumber == null) {
                activeNumber = ringingNumber
            }
            callState = state
        }
        if (state == TelephonyManager.CALL_STATE_RINGING) heardNumber?.let { ringingNumber = it }
        else if (state == TelephonyManager.CALL_STATE_OFFHOOK && activeNumber == null) heardNumber?.let { activeNumber = it }
        val status = PhoneStatus.newBuilder()
        signal()?.let { status.setSignalStrength(it) }
        if (state != TelephonyManager.CALL_STATE_IDLE) status.addCalls(call(state, now))
        val built = status.build()
        if (built == lastSent) return
        lastSent = built
        ch.status(built)
    }

    private fun call(state: Int, now: Long): PhoneStatus.Call {
        val ringing = state == TelephonyManager.CALL_STATE_RINGING
        val muted = !ringing && runCatching { context.getSystemService(AudioManager::class.java).isMicrophoneMute }.getOrDefault(false)
        val call = PhoneStatus.Call.newBuilder()
            .setPhoneState(when { ringing -> PhoneStatus.State.INCOMING; muted -> PhoneStatus.State.MUTED; else -> PhoneStatus.State.IN_CALL })
            .setCallDurationSeconds(if (ringing) 0 else ((now - callSinceMs) / 1000).toInt().coerceAtLeast(0))
        val number = if (ringing) ringingNumber else activeNumber
        val contact = number?.let(::lookup)
        number?.let { call.setCallerNumber(it) }
        // Without contacts access, the dialer's call notification still names the caller.
        (contact?.name ?: net.prototux.aare.notify.CarNotificationListener.caller.value)?.let { call.setCallerId(it) }
        contact?.type?.let { call.setCallerNumberType(it) }
        contact?.photo?.let { call.setCallerThumbnail(ByteString.copyFrom(it)) }
        return call.build()
    }

    /** Signal bars (0 to 4), as the status bar shows them; null without a SIM. TelephonyManager.signalStrength is Android 9+. */
    private fun signal(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        if (tm.simState != TelephonyManager.SIM_STATE_READY) return null
        return runCatching { tm.signalStrength?.level }.getOrNull()
    }

    // ------------------------------------------------------------------ contacts

    /** Name, number type and photo of [number]'s contact, once per number per connection. */
    private fun lookup(number: String): Contact? {
        if (contacts.containsKey(number)) return contacts[number]
        val found = if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) null else runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cols = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.TYPE,
                ContactsContract.PhoneLookup.LABEL, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val type = if (c.isNull(1)) null
                else ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(1), c.getString(2))?.toString()
                Contact(c.getString(0), type, c.getString(3)?.let { thumbnail(Uri.parse(it)) })
            }
        }.getOrNull()
        contacts[number] = found
        return found
    }

    /** The contact's thumbnail (already a small JPEG); shrunk only if some app stored a big one. */
    private fun thumbnail(uri: Uri): ByteArray? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        if (bytes.size <= MAX_THUMB_BYTES) return@runCatching bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val small = Bitmap.createScaledBitmap(bmp, THUMB_SIZE, THUMB_SIZE * bmp.height / maxOf(1, bmp.width), true)
        ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }.getOrNull()

    companion object {
        private const val TAG = "AarePhoneStatus"
        private const val CALL_TICK_MS = 1_000L
        private const val IDLE_TICK_MS = 10_000L
        /** Android's own low-battery "critical" level. */
        private const val CRITICAL_PERCENT = 5
        private const val MAX_THUMB_BYTES = 16 * 1024
        private const val THUMB_SIZE = 96
    }
}

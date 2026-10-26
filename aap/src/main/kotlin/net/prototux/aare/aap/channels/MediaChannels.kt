package net.prototux.aare.aap.channels

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.UpdateUiConfigReplyOuterClass.UpdateUiConfigReply
import aap_protobuf.service.control.message.UpdateUiConfigRequestOuterClass.UpdateUiConfigRequest
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.shared.message.StopOuterClass.Stop
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import aap_protobuf.service.media.source.message.MicrophoneRequestOuterClass.MicrophoneRequest
import aap_protobuf.service.media.source.message.MicrophoneResponseOuterClass.MicrophoneResponse
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.media.video.message.VideoFocusNotificationOuterClass.VideoFocusNotification
import aap_protobuf.service.media.video.message.VideoFocusReasonOuterClass.VideoFocusReason
import aap_protobuf.service.media.video.message.VideoFocusRequestNotificationOuterClass.VideoFocusRequestNotification
import net.prototux.aare.aap.AapMessage
import net.prototux.aare.aap.MediaMsg
import net.prototux.aare.aap.Priority
import net.prototux.aare.aap.ServiceChannel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Common part of the phone→head unit media streams (video and audio sinks on the head unit):
 * setup, config, start/stop, and ack-based flow control.
 *
 * The head unit tells us in its Config how many media messages may be in flight without an ack
 * ([maxUnacked]); every DATA message is acked. We never exceed that window.
 */
abstract class MediaSinkChannel(service: Service, name: String, private val codec: MediaCodecType) : ServiceChannel(service, name) {
    @Volatile var ready = false
        private set
    @Volatile var maxUnacked = 1
        private set
    @Volatile var started = false
        protected set
    protected var configIndices: List<Int> = emptyList()
    private val sessionCounter = AtomicInteger(0)
    @Volatile protected var mediaSession = 0
    protected val unacked = AtomicInteger(0)
    /**
     * Head units on newer protocol versions don't ack audio. If no ack ever comes after a
     * generous number of messages, we stop counting rather than stall the stream.
     */
    @Volatile var ackless = false
        private set
    @Volatile private var ackSeen = false
    private val sentSinceStart = AtomicInteger(0)

    // Read-only per-stream telemetry. This distinguishes queued DATA, actually written DATA,
    // and head-unit ACK messages; a zero ACK count is not inferred from an unset latency value.
    private val dataQueued = AtomicLong(0)
    private val dataWritten = AtomicLong(0)
    private val ackMessages = AtomicLong(0)
    private val ackUnits = AtomicLong(0)
    val diagnosticDataQueued: Long get() = dataQueued.get()
    val diagnosticDataWritten: Long get() = dataWritten.get()
    val diagnosticAckMessages: Long get() = ackMessages.get()
    val diagnosticAckUnits: Long get() = ackUnits.get()
    val diagnosticInFlight: Int get() = unacked.get()

    override fun onOpened() {
        send(MediaMsg.SETUP, Setup.newBuilder().setType(codec).build())
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMsg.CONFIG -> {
                val cfg = Config.parseFrom(msg.bodyBytes())
                maxUnacked = if (cfg.hasMaxUnacked() && cfg.maxUnacked > 0) cfg.maxUnacked else 1
                configIndices = cfg.configurationIndicesList
                ready = cfg.status == Config.Status.STATUS_READY
                onConfig(cfg)
            }
            MediaMsg.ACK -> {
                val ack = Ack.parseFrom(msg.bodyBytes())
                ackSeen = true
                val n = if (ack.hasAck()) ack.ack else 1
                ackMessages.incrementAndGet()
                ackUnits.addAndGet(n.coerceAtLeast(0).toLong())
                // Never go negative: some head units ack frames sent before a restart.
                unacked.updateAndGet { maxOf(0, it - n) }
                onAcked()
            }
            else -> onOtherMessage(msg)
        }
    }

    protected open fun onConfig(cfg: Config) {}
    protected open fun onAcked() {}
    protected open fun onOtherMessage(msg: AapMessage) {}

    /** Starts a media session with the given configuration index. */
    protected fun startStream(configIndex: Int) {
        mediaSession = sessionCounter.incrementAndGet()
        unacked.set(0)
        sentSinceStart.set(0)
        dataQueued.set(0)
        dataWritten.set(0)
        ackMessages.set(0)
        ackUnits.set(0)
        send(MediaMsg.START, Start.newBuilder().setSessionId(mediaSession).setConfigurationIndex(configIndex).build())
        started = true
    }

    protected fun stopStream() {
        if (!started) return
        started = false
        send(MediaMsg.STOP, Stop.getDefaultInstance())
    }

    /**
     * Counts a message we tried to send (sent or dropped for lack of window). Head units on newer
     * protocol versions may never ack: once well past the window without a single ack, stop
     * waiting for acks. Dropped messages count too, or a full window would hide the problem.
     */
    protected fun noteAttempt() {
        if (!ackSeen && !ackless && sentSinceStart.incrementAndGet() > 2 * maxUnacked + ACKLESS_AFTER) {
            ackless = true
            unacked.set(0)
        }
    }

    /** Whether the in-flight window has room for one more message. */
    protected fun canSend() = ackless || unacked.get() < maxUnacked

    /**
     * Sends a DATA message. [buf] must have 10 bytes of headroom before [offset] (id + timestamp),
     * which this fills in so the payload is never copied.
     */
    protected fun sendData(buf: ByteArray, offset: Int, length: Int, timestampUs: Long, priority: Int, onSent: (() -> Unit)? = null) {
        val start = offset - 10
        require(start >= 0) { "need 10 bytes of headroom" }
        buf[start] = (MediaMsg.DATA shr 8).toByte()
        buf[start + 1] = MediaMsg.DATA.toByte()
        for (i in 0 until 8) buf[start + 2 + i] = (timestampUs ushr (56 - 8 * i)).toByte()
        unacked.incrementAndGet()
        noteAttempt()
        if (ackless) unacked.set(0)
        val plain = if (start == 0) buf else buf.copyOfRange(start, offset + length)
        dataQueued.incrementAndGet()
        session.sendRaw(id, plain, length + 10, priority = priority, onSent = {
            dataWritten.incrementAndGet()
            onSent?.invoke()
        })
    }

    protected fun sendCodecConfigData(data: ByteArray, offset: Int, length: Int, priority: Int) {
        val plain = ByteArray(2 + length)
        plain[0] = (MediaMsg.CODEC_CONFIG shr 8).toByte()
        plain[1] = MediaMsg.CODEC_CONFIG.toByte()
        System.arraycopy(data, offset, plain, 2, length)
        session.sendRaw(id, plain, priority = priority)
    }

    override fun onClosed() {
        ready = false
        started = false
    }

    companion object {
        const val ACKLESS_AFTER = 16
    }
}

/**
 * The projected display. The phone picks one of the head unit's video configurations, streams
 * H.264 and reacts to video focus (the driver switching to the car's own screens and back).
 */
class VideoChannel(
    service: Service,
    private val listener: Listener,
    /** H.264 Baseline unless the head unit only offers H.265 and the phone can encode it. */
    val codec: MediaCodecType = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP,
) : MediaSinkChannel(service, "video", codec) {

    interface Listener {
        /** Choose among the head unit's configurations; return the index into [configs]. */
        fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int

        /** Start (or restart) the encoder for this configuration. */
        fun onVideoStart(index: Int, config: VideoConfiguration)

        /** Projection visible or not. When it becomes visible, send a key frame. */
        fun onVideoFocus(projected: Boolean)

        /** Frames were dropped to catch up: the next frame sent must be a key frame. */
        fun onKeyFrameNeeded()

        /** The head unit changed the UI layout (insets, margins, theme) of the running stream. */
        fun onUiConfig(config: UiConfig) {}
    }

    val configs: List<VideoConfiguration> = service.mediaSinkService.videoConfigsList
    @Volatile var chosen: VideoConfiguration? = null
        private set
    @Volatile var focused = false
        private set

    private class Pending(val buf: ByteArray, val offset: Int, val length: Int, val ptsUs: Long, val key: Boolean, val recycle: (ByteArray) -> Unit)
    private val pending = ArrayDeque<Pending>()
    private val lock = Any()
    @Volatile private var codecConfig: ByteArray? = null
    private var needKeyFrame = true
    @Volatile var droppedFrames = 0L
        private set

    // How well the head unit keeps up, for the phone's adaptive bitrate.
    /** Frames waiting for room in the ack window. */
    @Volatile var pendingFrames = 0
        private set
    /** Times the backlog overflowed and was dropped (not the drops while waiting for a key frame). */
    @Volatile var congestionDrops = 0L
        private set
    /** Send to ack of the latest acked frame: transport, head-unit queue and decode. */
    @Volatile var ackLatencyMs = 0L
        private set
    /** Session queue to socket write of the latest frame: grows when the link (TCP) backs up. */
    @Volatile var sendLatencyMs = 0L
        private set
    private val sentAt = ArrayDeque<Long>()

    override val openPriority = 0

    override fun onConfig(cfg: Config) {
        if (!ready) return
        val index = listener.chooseConfiguration(configs, configIndices).coerceIn(0, configs.size - 1)
        chosen = configs[index]
        synchronized(lock) { sentAt.clear() }
        startStream(index)
        listener.onVideoStart(index, configs[index])
        // Ask to be shown; head units answer with a focus notification.
        send(MediaMsg.VIDEO_FOCUS_REQUEST, VideoFocusRequestNotification.newBuilder()
            .setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).setReason(VideoFocusReason.UNKNOWN).build())
    }

    override fun onOtherMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMsg.VIDEO_FOCUS_NOTIFICATION -> {
                val n = VideoFocusNotification.parseFrom(msg.bodyBytes())
                val projected = n.focus == VideoFocusMode.VIDEO_FOCUS_PROJECTED ||
                    n.focus == VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS
                setFocus(projected)
            }
            MediaMsg.VIDEO_FOCUS_REQUEST -> {
                // Some head units ask us to change focus; we comply and confirm.
                val r = VideoFocusRequestNotification.parseFrom(msg.bodyBytes())
                val projected = r.mode == VideoFocusMode.VIDEO_FOCUS_PROJECTED
                send(MediaMsg.VIDEO_FOCUS_NOTIFICATION, VideoFocusNotification.newBuilder()
                    .setFocus(if (projected) VideoFocusMode.VIDEO_FOCUS_PROJECTED else VideoFocusMode.VIDEO_FOCUS_NATIVE)
                    .setUnsolicited(false).build())
                setFocus(projected)
            }
            MediaMsg.UPDATE_UI_CONFIG_REQUEST -> {
                // The head unit changes the layout at runtime (insets, theme): apply it and
                // confirm with the config we now use, like the official phone.
                val ui = UpdateUiConfigRequest.parseFrom(msg.bodyBytes()).uiConfig
                chosen = chosen?.toBuilder()?.setUiConfig(ui)?.build()
                listener.onUiConfig(ui)
                send(MediaMsg.UPDATE_UI_CONFIG_REPLY, UpdateUiConfigReply.newBuilder().setUiConfig(ui).build())
            }
            MediaMsg.UPDATE_UI_CONFIG_REPLY -> Unit
            else -> Unit
        }
    }

    private fun setFocus(projected: Boolean) {
        val changed = projected != focused
        focused = projected
        if (projected) {
            synchronized(lock) { needKeyFrame = true; pending.clear() }
            // resend codec config: some head units reset their decoder when focus returns
            codecConfig?.let { sendCodecConfigData(it, 0, it.size, Priority.VIDEO) }
        }
        if (changed || projected) listener.onVideoFocus(projected)
    }

    /** Asks the head unit to show its own UI (e.g. the driver chose "exit" on the phone). */
    fun requestNativeFocus() {
        send(MediaMsg.VIDEO_FOCUS_REQUEST, VideoFocusRequestNotification.newBuilder()
            .setMode(VideoFocusMode.VIDEO_FOCUS_NATIVE).setReason(VideoFocusReason.LAUNCH_NATIVE).build())
    }

    fun requestProjectedFocus() {
        send(MediaMsg.VIDEO_FOCUS_REQUEST, VideoFocusRequestNotification.newBuilder()
            .setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).setReason(VideoFocusReason.UNKNOWN).build())
    }

    fun sendCodecConfig(data: ByteArray, offset: Int, length: Int) {
        codecConfig = data.copyOfRange(offset, offset + length)
        if (started) sendCodecConfigData(data, offset, length, Priority.VIDEO)
    }

    /**
     * Queues an encoded frame (with 10 bytes of headroom before [offset]). [recycle] gets the
     * buffer back once it has been written or dropped.
     */
    fun sendFrame(buf: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean, recycle: (ByteArray) -> Unit) {
        if (!started || !focused) { recycle(buf); return }
        synchronized(lock) {
            if (needKeyFrame && !keyFrame) {
                droppedFrames++
                recycle(buf)
                return
            }
            if (keyFrame) {
                // A key frame supersedes everything still waiting.
                while (pending.isNotEmpty()) { pending.removeFirst().let { it.recycle(it.buf) }; droppedFrames++ }
                needKeyFrame = false
            } else if (pending.size >= MAX_PENDING) {
                // The head unit can't keep up: drop the backlog and resync on a key frame.
                while (pending.isNotEmpty()) { pending.removeFirst().let { it.recycle(it.buf) }; droppedFrames++ }
                recycle(buf); droppedFrames++
                congestionDrops++; pendingFrames = 0
                needKeyFrame = true
                listener.onKeyFrameNeeded()
                return
            }
            pending.addLast(Pending(buf, offset, length, ptsUs, keyFrame, recycle))
            pump()
        }
    }

    override fun onAcked() {
        synchronized(lock) {
            // Acks are cumulative: whatever is beyond the in-flight count has been acked, and the
            // newest of those gives the latency.
            var acked = -1L
            while (sentAt.size > unacked.get()) acked = sentAt.removeFirst()
            if (acked >= 0) ackLatencyMs = (System.nanoTime() - acked) / 1_000_000
            pump()
        }
    }

    private fun pump() {
        while (pending.isNotEmpty() && canSend()) {
            val p = pending.removeFirst()
            val t = System.nanoTime()
            if (ackless) sentAt.clear() else sentAt.addLast(t)
            sendData(p.buf, p.offset, p.length, p.ptsUs, Priority.VIDEO) {
                sendLatencyMs = (System.nanoTime() - t) / 1_000_000
                p.recycle(p.buf)
            }
        }
        pendingFrames = pending.size
    }

    override fun onClosed() {
        super.onClosed()
        focused = false
        synchronized(lock) { while (pending.isNotEmpty()) pending.removeFirst().let { it.recycle(it.buf) }; sentAt.clear(); pendingFrames = 0 }
    }

    companion object {
        /** About a quarter second at 30 fps: more than that and we're just adding latency. */
        const val MAX_PENDING = 8
    }
}

/** One of the head unit's audio outputs: media, guidance (navigation voice), system sounds, telephony. */
class AudioChannel(service: Service, private val listener: Listener? = null) :
    MediaSinkChannel(service, "audio:${service.mediaSinkService.audioType}", MediaCodecType.MEDIA_CODEC_AUDIO_PCM) {

    interface Listener {
        fun onAudioReady(channel: AudioChannel, config: AudioConfiguration)
        /** The car ran out of sound to play on this stream (reader thread). */
        fun onUnderflow(channel: AudioChannel) {}
    }

    val streamType: AudioStreamType = service.mediaSinkService.audioType
    /** Underflows the car reported on this stream (0x800B). */
    @Volatile var underflows = 0
        private set

    override fun onOtherMessage(msg: AapMessage) {
        if (msg.id != MediaMsg.AUDIO_UNDERFLOW_NOTIFICATION) return
        // Body (session id) is optional in practice: some head units send it empty.
        underflows++
        listener?.onUnderflow(this)
    }
    /** The configuration the head unit picked in its Config (the index START announces), else the first. */
    val config: AudioConfiguration?
        get() = service.mediaSinkService.audioConfigsList.let { it.getOrNull(configIndices.firstOrNull() ?: 0) ?: it.firstOrNull() }
    @Volatile var droppedChunks = 0L
        private set

    override fun onConfig(cfg: Config) {
        if (ready) config?.let { listener?.onAudioReady(this, it) }
    }

    /** Opens a playback session on the head unit (call when sound starts). */
    fun startPlayback() {
        if (ready && !started) startStream(configIndices.firstOrNull() ?: 0)
    }

    /** Closes the session (call after a short silence, so the head unit can restore its own audio). */
    fun stopPlayback() = stopStream()

    /**
     * Sends a PCM chunk (10 bytes of headroom before [offset]). Returns false, dropping the chunk,
     * when the head unit is too far behind: audio must stay in real time.
     */
    /** Whether [sendPcm] would send a chunk now rather than drop it. */
    val hasRoom: Boolean get() = started && (ackless || unacked.get() < maxUnacked + SLACK)

    fun sendPcm(buf: ByteArray, offset: Int, length: Int, timestampUs: Long): Boolean {
        if (!started) return false
        if (!ackless && unacked.get() >= maxUnacked + SLACK) { droppedChunks++; noteAttempt(); return false }
        sendData(buf, offset, length, timestampUs, Priority.AUDIO)
        return true
    }

    companion object {
        /** Head units ack audio in bursts; allow a little beyond the window before dropping. */
        const val SLACK = 4
    }
}

/** The car's microphone (a media source on the head unit). */
class MicrophoneChannel(service: Service, private val listener: Listener) :
    ServiceChannel(service, "mic") {

    interface Listener {
        fun onMicReady(config: AudioConfiguration?) {}
        fun onMicOpened(ok: Boolean) {}
        /** PCM from the car (usually 16 kHz mono 16-bit). Called on the reader thread. */
        fun onMicData(data: ByteArray, offset: Int, length: Int, timestampUs: Long)
    }

    val config: AudioConfiguration? = service.mediaSourceService.audioConfig
    @Volatile var open = false
        private set
    /** We asked for the mic and haven't closed it since: a late answer must not reopen it. */
    @Volatile private var requested = false
    private var sessionId = 0

    override fun onOpened() {
        send(MediaMsg.SETUP, Setup.newBuilder().setType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).build())
    }

    fun openMic() {
        requested = true
        send(MediaMsg.MICROPHONE_REQUEST, MicrophoneRequest.newBuilder().setOpen(true)
            .setAncEnabled(false).setEcEnabled(false).setMaxUnacked(2).build())
    }

    fun closeMic() {
        requested = false
        open = false
        send(MediaMsg.MICROPHONE_REQUEST, MicrophoneRequest.newBuilder().setOpen(false).build())
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMsg.CONFIG -> listener.onMicReady(config)
            MediaMsg.MICROPHONE_RESPONSE -> {
                // An answer to a request since closed (or to the close itself) changes nothing.
                if (!requested) return
                val r = MicrophoneResponse.parseFrom(msg.bodyBytes())
                open = r.status == 0
                sessionId = r.sessionId
                listener.onMicOpened(open)
            }
            MediaMsg.DATA -> {
                if (msg.bodyLength < 8) return
                var ts = 0L
                for (i in 0 until 8) ts = (ts shl 8) or (msg.body[msg.bodyOffset + i].toLong() and 0xff)
                session.send(id, MediaMsg.ACK, Ack.newBuilder().setSessionId(sessionId).setAck(1).build(), priority = Priority.AUDIO)
                if (open) listener.onMicData(msg.body, msg.bodyOffset + 8, msg.bodyLength - 8, ts)
            }
            MediaMsg.CODEC_CONFIG -> Unit
        }
    }

    override fun onClosed() {
        open = false
        requested = false
    }
}

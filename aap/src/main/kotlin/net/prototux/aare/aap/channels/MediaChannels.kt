package net.prototux.aare.aap.channels

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.UpdateUiConfigReplyOuterClass.UpdateUiConfigReply
import aap_protobuf.service.control.message.UpdateUiConfigRequestOuterClass.UpdateUiConfigRequest
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.MediaOptionsOuterClass.MediaOptions
import aap_protobuf.service.media.shared.message.MediaStatsOuterClass.MediaStats
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

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
    /**
     * The id the next START announces. The first session is 0 and the
     * id moves on at each STOP, so a restart after a stop is a new session.
     */
    private val sessionCounter = AtomicInteger(0)
    @Volatile protected var mediaSession = 0
    protected val unacked = AtomicInteger(0)
    /**
     * Some head units don't ack (newer protocol versions), and some ack for a while then go quiet
     * mid-stream. Either way we eventually stop counting acks rather than stall the stream; an ack
     * arriving later turns counting back on. A [dropsOverflow] stream is ackless from its START.
     */
    @Volatile var ackless = false
        private set
    /** Messages tried (sent or dropped) since the last ack, or since the stream started. */
    private val sinceAck = AtomicInteger(0)
    @Volatile private var ackSeen = false
    /** When the last ack arrived (System.nanoTime), or the stream started. */
    @Volatile private var lastAckNs = System.nanoTime()
    /** How long a head unit that has acked may go without one, window full, before we stop waiting (#82). */
    @Volatile var ackStallMs = ACK_STALL_MS
    /** This stream (since START), for diagnostics: DATA messages sent, ack messages, longest wait for an ack with data in flight. */
    @Volatile var streamSent = 0
        private set
    @Volatile var streamAcks = 0
        private set
    @Volatile var maxAckGapMs = 0L
        private set
    /** The head unit's latest playback statistics (MEDIA_STATS), for diagnostics; null until one comes. */
    @Volatile var mediaStats: MediaStats? = null
        private set

    override fun onOpened() {
        send(MediaMsg.SETUP, Setup.newBuilder().setType(codec).build())
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMsg.CONFIG -> {
                val cfg = Config.parseFrom(msg.bodyBytes())
                maxUnacked = if (cfg.hasMaxUnacked() && cfg.maxUnacked > 0) cfg.maxUnacked else 1
                // At least this window applies in drop overflow mode (it never waits on it).
                if (dropsOverflow) maxUnacked = maxOf(maxUnacked, DROP_OVERFLOW_MIN_UNACKED)
                configIndices = cfg.configurationIndicesList
                ready = cfg.status == Config.Status.STATUS_READY
                onConfig(cfg)
            }
            MediaMsg.ACK -> {
                // Acks on a drop overflow stream pace nothing: they are ignored.
                if (dropsOverflow) return
                val ack = Ack.parseFrom(msg.bodyBytes())
                sinceAck.set(0)
                ackSeen = true
                val now = System.nanoTime()
                if (unacked.get() > 0) maxAckGapMs = maxOf(maxAckGapMs, (now - lastAckNs) / 1_000_000)
                streamAcks++
                lastAckNs = now
                // Acks are back: let the car pace us again (its window is its flow control).
                if (ackless) { ackless = false; unacked.set(0) }
                val n = if (ack.hasAck()) ack.ack else 1
                // Never go negative: some head units ack frames sent before a restart.
                unacked.updateAndGet { maxOf(0, it - n) }
                onAcked()
            }
            MediaMsg.MEDIA_STATS -> onMediaStats(msg)
            else -> onOtherMessage(msg)
        }
    }

    /**
     * A head unit reports how playback goes every few seconds when the START's media options ask
     * for it, as some do. Kept and logged only; one we can't read is dropped.
     */
    private fun onMediaStats(msg: AapMessage) {
        val stats = runCatching { MediaStats.parseFrom(msg.bodyBytes()) }.getOrNull()
            ?: return session.log.d("$name: unreadable media stats (${msg.bodyLength} bytes)")
        mediaStats = stats
        session.log.d("$name media stats: ${describe(stats)}")
    }

    private fun describe(s: MediaStats) = buildList {
        if (s.hasFrameCount()) add("frames ${s.frameCount}")
        if (s.hasAudioUnderrunCount()) add("underruns ${s.audioUnderrunCount}")
        if (s.hasAudioOverflowCount()) add("overflows ${s.audioOverflowCount}")
        if (s.hasVideoOverflowCount()) add("video overflows ${s.videoOverflowCount}")
        if (s.hasAudioDelayUs()) add("audio delay ${s.audioDelayUs.avg} us")
        if (s.hasVideoDelayUs()) add("video delay ${s.videoDelayUs.avg} us")
    }.joinToString()

    /**
     * Send priority of this stream's DATA. START and STOP use it too, so they stay in order with
     * the data around them instead of overtaking it at control priority.
     */
    protected abstract val dataPriority: Int

    /**
     * Whether this stream runs in "drop overflow" mode: its START says so (field 3 = 2) and
     * carries media options (field 4), and from then on it sends without waiting for acks and
     * ignores those that come; the head unit drops what it can't take. Only when asked for this
     * car (vehicles.json `dropOverflow`), and only to head units new enough for it (video 6.0 and
     * later, audio 5.0 and later): every other head unit keeps the ack-paced stream. Valid once the
     * channel is attached (the head unit's version is known before any channel opens).
     */
    abstract val dropsOverflow: Boolean

    /** The media options a [dropsOverflow] stream's START carries. */
    protected abstract fun startMediaOptions(): MediaOptions

    protected open fun onConfig(cfg: Config) {}
    protected open fun onAcked() {}
    protected open fun onOtherMessage(msg: AapMessage) {}

    /** Starts a media session with the given configuration index. */
    protected fun startStream(configIndex: Int) {
        mediaSession = sessionCounter.get()
        unacked.set(0)
        sinceAck.set(0)
        lastAckNs = System.nanoTime()
        streamSent = 0; streamAcks = 0; maxAckGapMs = 0
        val start = Start.newBuilder().setSessionId(mediaSession).setConfigurationIndex(configIndex)
        if (dropsOverflow) {
            start.setAckMode(ACK_MODE_DROP_OVERFLOW).setMediaOptions(startMediaOptions())
            ackless = true
        }
        send(MediaMsg.START, start.build(), dataPriority)
        started = true
    }

    protected fun stopStream() {
        if (!started) return
        started = false
        sessionCounter.incrementAndGet()
        send(MediaMsg.STOP, Stop.getDefaultInstance(), dataPriority)
    }

    /**
     * Counts a message we tried to send (sent or dropped for lack of window). Head units may stop
     * acking: some never ack (newer protocol versions), some ack for a while then go quiet mid-
     * stream (#82: music plays a second, then the in-flight window fills and never drains, so every
     * chunk is dropped and the car falls silent until reconnect). Either way, once we are well past
     * the window with no ack in sight, stop waiting for acks so the stream keeps flowing.
     *
     * A head unit that has acked is only given up on after [ackStallMs] without an ack: holding
     * acks back while its buffer is full is how a car slows us down, and counting messages
     * mistook that for a stall (a few hundred ms of held acks), so the stream went unpaced for
     * good, overran the car (crackling) and the car eventually dropped the channel. Dropped
     * messages count too, or a full, stuck window would hide the stall.
     */
    protected fun noteAttempt() {
        if (ackless) return
        val count = sinceAck.incrementAndGet()
        val stalled = if (!ackSeen) count > 2 * maxUnacked + ACKLESS_AFTER
            else count > 2 * maxUnacked + ACKLESS_AFTER && (System.nanoTime() - lastAckNs) / 1_000_000 >= ackStallMs
        if (stalled) {
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
        streamSent++
        noteAttempt()
        if (ackless) unacked.set(0)
        val plain = if (start == 0) buf else buf.copyOfRange(start, offset + length)
        session.sendRaw(id, plain, length + 10, priority = priority, onSent = onSent)
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
        /** START field 3 for a stream the head unit paces by dropping, not by holding acks back. */
        const val ACK_MODE_DROP_OVERFLOW = 2
        const val DROP_OVERFLOW_MIN_UNACKED = 24
        const val ACKLESS_AFTER = 16
        /** No ack for this long (window full) from a head unit that did ack: it has stalled (#82). */
        const val ACK_STALL_MS = 2_000L
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
    /** Allow drop overflow mode on this head unit (see [dropsOverflow]); off by default. */
    private val allowDropOverflow: Boolean = false,
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

        /**
         * The head unit changed the UI layout (insets, margins) of the running stream: lay the
         * projection out with [config], then call [applied]. The head unit gets its reply once a
         * frame has followed.
         */
        fun onUiConfig(config: UiConfig, applied: () -> Unit) = applied()
    }

    val configs: List<VideoConfiguration> = service.mediaSinkService.videoConfigsList
    @Volatile var chosen: VideoConfiguration? = null
        private set
    /** The configuration index of the running stream, to START a new session on the same one. */
    @Volatile private var startIndex = 0
    @Volatile var focused = false
        private set
    /** The head unit has sent a focus notification or request since the stream started. */
    @Volatile var focusHeard = false
        private set

    private class Pending(val buf: ByteArray, val offset: Int, val length: Int, val ptsUs: Long, val key: Boolean, val recycle: (ByteArray) -> Unit)
    private val pending = ArrayDeque<Pending>()
    private val lock = Any()
    @Volatile private var codecConfig: ByteArray? = null
    /** A UI config request that came before the configuration was chosen: applied once it is. */
    @Volatile private var earlyUiRequest: UpdateUiConfigRequest? = null
    /** Applied UI configs, confirmed to the head unit after the next frame sent. */
    private val uiReplies = ConcurrentLinkedQueue<UiConfig>()
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
    override val dataPriority = Priority.VIDEO
    override val dropsOverflow: Boolean get() = allowDropOverflow && session.headUnitAtLeast(6, 0)
    override fun startMediaOptions() = StartMediaOptions.video()

    override fun onConfig(cfg: Config) {
        // Whatever its status: video starts on any Config (it only checks the
        // status on audio), and a head unit that says "wait" here would otherwise never get video.
        val index = listener.chooseConfiguration(configs, configIndices).coerceIn(0, configs.size - 1)
        chosen = configs[index]
        startIndex = index
        focusHeard = false
        synchronized(lock) { sentAt.clear() }
        startStream(index)
        listener.onVideoStart(index, configs[index])
        earlyUiRequest?.let { earlyUiRequest = null; onUiConfigRequest(it) }
        // Ask to be shown; head units answer with a focus notification. At the data priority so
        // it follows START: the head unit must not be asked to show a stream it hasn't started.
        send(MediaMsg.VIDEO_FOCUS_REQUEST, VideoFocusRequestNotification.newBuilder()
            .setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).setReason(VideoFocusReason.UNKNOWN).build(), dataPriority)
    }

    override fun onOtherMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMsg.VIDEO_FOCUS_NOTIFICATION -> {
                val n = VideoFocusNotification.parseFrom(msg.bodyBytes())
                setFocus(n.focus)
            }
            MediaMsg.VIDEO_FOCUS_REQUEST -> {
                // Some head units ask us to change focus; we comply and confirm.
                val r = VideoFocusRequestNotification.parseFrom(msg.bodyBytes())
                val projected = isProjected(r.mode)
                // Confirm the projected mode it asked for (with or without input focus).
                send(MediaMsg.VIDEO_FOCUS_NOTIFICATION, VideoFocusNotification.newBuilder()
                    .setFocus(if (projected) r.mode else VideoFocusMode.VIDEO_FOCUS_NATIVE)
                    .setUnsolicited(false).build())
                setFocus(r.mode)
            }
            MediaMsg.UPDATE_UI_CONFIG_REQUEST -> onUiConfigRequest(UpdateUiConfigRequest.parseFrom(msg.bodyBytes()))
            MediaMsg.UPDATE_UI_CONFIG_REPLY -> Unit
            else -> Unit
        }
    }

    /**
     * The head unit changes the layout at runtime (insets, margins), as [UiConfigUpdate] says. As
     * the reply carries the config now in use and goes only once the
     * projection is laid out with it and a frame has followed; a request it refuses gets no reply
     * (ending the session over it is one option; AARE only logs it).
     */
    private fun onUiConfigRequest(request: UpdateUiConfigRequest) {
        val current = chosen ?: run { earlyUiRequest = request; return }
        val ui = current.uiConfig.takeIf { current.hasUiConfig() }
        when (val r = UiConfigUpdate.apply(ui, request, session.headUnitAtLeast(4, 3))) {
            is UiConfigUpdate.Refused -> session.log.w("video: UI config update refused, ${r.why}")
            is UiConfigUpdate.Accepted -> {
                chosen = current.toBuilder().setUiConfig(r.config).build()
                listener.onUiConfig(r.config) {
                    // Nothing on the car's screen to wait for: confirm at once.
                    if (started && focused) uiReplies.add(r.config) else sendUiConfigReply(r.config)
                }
            }
        }
    }

    private fun sendUiConfigReply(config: UiConfig) =
        send(MediaMsg.UPDATE_UI_CONFIG_REPLY, UpdateUiConfigReply.newBuilder().setUiConfig(config).build(), dataPriority)

    private fun setFocus(mode: VideoFocusMode) {
        focusHeard = true
        val projected = isProjected(mode)
        val changed = projected != focused
        focused = projected
        if (projected) {
            // Some head units throw their decoder away while they show their own screen (the
            // reverse camera on LG GEN5, #109) and only build a new one on a START: if we stopped
            // the stream for a full native focus, start a fresh session now so the picture returns.
            if (!started) startStream(startIndex)
            synchronized(lock) { needKeyFrame = true; while (pending.isNotEmpty()) pending.removeFirst().let { it.recycle(it.buf) } }
            // Resend the codec config after the (re)start, the same as at connection.
            codecConfig?.let { sendCodecConfigData(it, 0, it.size, Priority.VIDEO) }
        } else if (mode == VideoFocusMode.VIDEO_FOCUS_NATIVE) {
            // Full native: the head unit shows its own screen (reverse, "exit to car"). Stop the
            // stream so it can tear its decoder down cleanly; we re-START when projection returns.
            // A transient native (a brief overlay) keeps the stream open; we just stop feeding it.
            stopStream()
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
            // Layout changes applied since the last frame are confirmed behind this one.
            var reply = uiReplies.poll()
            while (reply != null) { sendUiConfigReply(reply); reply = uiReplies.poll() }
        }
        pendingFrames = pending.size
    }

    override fun onClosed() {
        super.onClosed()
        focused = false
        uiReplies.clear(); earlyUiRequest = null
        synchronized(lock) { while (pending.isNotEmpty()) pending.removeFirst().let { it.recycle(it.buf) }; sentAt.clear(); pendingFrames = 0 }
    }

    companion object {
        /** About a quarter second at 30 fps: more than that and we're just adding latency. */
        const val MAX_PENDING = 8

        /** Our projection is on screen, with or without input focus (native, transient native: not). */
        private fun isProjected(mode: VideoFocusMode) =
            mode == VideoFocusMode.VIDEO_FOCUS_PROJECTED || mode == VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS
    }
}

/** One of the head unit's audio outputs: media, guidance (navigation voice), system sounds, telephony. */
class AudioChannel(
    service: Service,
    private val listener: Listener? = null,
    /** Allow drop overflow mode on this head unit (see [dropsOverflow]); off by default. */
    private val allowDropOverflow: Boolean = false,
) :
    MediaSinkChannel(service, "audio:${service.mediaSinkService.audioType}", MediaCodecType.MEDIA_CODEC_AUDIO_PCM) {

    interface Listener {
        fun onAudioReady(channel: AudioChannel, config: AudioConfiguration)
        /** The car ran out of sound to play on this stream (reader thread). */
        fun onUnderflow(channel: AudioChannel) {}
    }

    val streamType: AudioStreamType = service.mediaSinkService.audioType
    override val dataPriority = Priority.AUDIO
    override val dropsOverflow: Boolean get() = allowDropOverflow && session.headUnitAtLeast(5, 0)
    override fun startMediaOptions() = StartMediaOptions.audio(streamType, session.headUnitVersion?.first ?: 0)
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
class MicrophoneChannel(
    service: Service,
    private val listener: Listener,
    /** Send SETUP when the channel opens; a head unit can be told not to get it (vehicles.json micSetup). */
    private val sendSetup: Boolean = true,
) : ServiceChannel(service, "mic") {

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
        if (sendSetup) send(MediaMsg.SETUP, Setup.newBuilder().setType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).build())
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

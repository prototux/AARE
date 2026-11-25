package net.prototux.aare.audio

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import net.prototux.aare.aap.channels.AudioChannel

/**
 * What [CarAudioOut] needs of a car audio channel: the session's [AudioChannel] (through
 * [AudioChannelSink]), or a fake in tests. See AudioChannel for what each member means.
 */
interface CarAudioSink {
    val streamType: AudioStreamType
    val config: AudioConfiguration?
    /** The car's stream is open. */
    val started: Boolean
    /** The car's ack window has room for another message. */
    val hasRoom: Boolean
    fun startPlayback()
    fun stopPlayback()
    fun sendPcm(buf: ByteArray, offset: Int, length: Int, timestampUs: Long): Boolean

    // For the connection log.
    val ackless: Boolean
    val dropsOverflow: Boolean
    val underflows: Int
    val droppedChunks: Long
    val streamSent: Int
    val streamAcks: Int
    val maxUnacked: Int
    val maxAckGapMs: Long
}

/** [CarAudioSink] over a session's audio channel. */
class AudioChannelSink(private val channel: AudioChannel) : CarAudioSink {
    override val streamType get() = channel.streamType
    override val config get() = channel.config
    override val started get() = channel.started
    override val hasRoom get() = channel.hasRoom
    override fun startPlayback() = channel.startPlayback()
    override fun stopPlayback() = channel.stopPlayback()
    override fun sendPcm(buf: ByteArray, offset: Int, length: Int, timestampUs: Long) = channel.sendPcm(buf, offset, length, timestampUs)
    override val ackless get() = channel.ackless
    override val dropsOverflow get() = channel.dropsOverflow
    override val underflows get() = channel.underflows
    override val droppedChunks get() = channel.droppedChunks
    override val streamSent get() = channel.streamSent
    override val streamAcks get() = channel.streamAcks
    override val maxUnacked get() = channel.maxUnacked
    override val maxAckGapMs get() = channel.maxAckGapMs
}

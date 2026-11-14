package net.prototux.aare.aap.sim

import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.PhoneDescription
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.ServiceChannel
import net.prototux.aare.aap.SessionOptions
import net.prototux.aare.aap.SocketTransport
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.MediaPlaybackChannel
import net.prototux.aare.aap.channels.MicrophoneChannel
import net.prototux.aare.aap.channels.NavigationStatusChannel
import net.prototux.aare.aap.channels.SensorChannel
import net.prototux.aare.aap.channels.VideoChannel
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A desktop "phone": listens like the developer head-unit server (TCP 5277) and streams an H.264
 * file to whatever head unit connects (Google's DHU, openauto, headunit-revived...).
 *
 *   ffmpeg -f lavfi -i testsrc=size=1280x720:rate=30 -t 20 -c:v libx264 -profile:v baseline \
 *          -bsf:v h264_mp4toannexb -x264-params keyint=30 test.h264
 *   ./gradlew :aap:runPhoneSim --args="test.h264"
 *
 * Optional args: [h264 file] [port] [pem cert] [pem pkcs8 key]
 */
fun main(args: Array<String>) {
    val file = File(args.getOrElse(0) { "test.h264" })
    val port = args.getOrNull(1)?.toInt() ?: 5277
    val identity = if (args.size >= 4) loadPem(File(args[2]), File(args[3])) else
        SelfSignedCertificate.generate("AARE phone").let { PhoneIdentity(it.certificate, it.keyPair.private) }
    val units = AnnexB.accessUnits(file.readBytes())
    println("loaded ${units.config.size} bytes of SPS/PPS and ${units.frames.size} frames from $file")

    ServerSocket(port).use { server ->
        println("listening on $port")
        while (true) {
            val socket = server.accept()
            println("head unit connected from ${socket.remoteSocketAddress}")
            val done = CountDownLatch(1)
            val streaming = AtomicBoolean(false)
            var video: VideoChannel? = null
            val session = AapSession(
                SocketTransport(socket), identity,
                PhoneDescription("AARE PhoneSim", "Android", instanceId = "aare-phonesim"),
                object : AapSession.Callbacks {
                    override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> =
                        response.channelsList.mapNotNull { s ->
                            when {
                                s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 -> VideoChannel(s, object : VideoChannel.Listener {
                                    override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int {
                                        configs.forEachIndexed { i, c -> println("  video config $i: ${c.codecResolution} ${c.frameRate} dpi=${c.density} margins=${c.widthMargin}x${c.heightMargin}") }
                                        return preferred.firstOrNull() ?: 0
                                    }
                                    override fun onVideoStart(index: Int, config: VideoConfiguration) {
                                        video!!.sendCodecConfig(units.config, 0, units.config.size)
                                    }
                                    override fun onVideoFocus(projected: Boolean) {
                                        println("video focus: ${if (projected) "PROJECTED" else "NATIVE"}")
                                        if (projected && streaming.compareAndSet(false, true)) Thread { stream(video!!, units, streaming) }.start()
                                        if (!projected) streaming.set(false)
                                    }
                                    override fun onKeyFrameNeeded() = println("head unit fell behind; waiting for next key frame")
                                }).also { video = it }
                                s.hasMediaSinkService() && s.mediaSinkService.audioType != AudioStreamType.AUDIO_STREAM_TELEPHONY -> AudioChannel(s)
                                s.hasMediaSourceService() -> MicrophoneChannel(s, object : MicrophoneChannel.Listener {
                                    override fun onMicData(data: ByteArray, offset: Int, length: Int, timestampUs: Long) = Unit
                                })
                                s.hasInputSourceService() -> InputChannel(s, object : InputChannel.Listener {
                                    override fun onInput(report: InputReport) = println("input: ${report.toString().replace('\n', ' ')}")
                                })
                                s.hasSensorSourceService() -> SensorChannel(s, object : SensorChannel.Listener {
                                    override fun onSensors(batch: SensorBatch) = println("sensors: ${batch.toString().replace('\n', ' ')}")
                                })
                                s.hasNavigationStatusService() -> NavigationStatusChannel(s)
                                s.hasMediaPlaybackService() -> MediaPlaybackChannel(s)
                                else -> null
                            }
                        }

                    override fun onRunning(session: AapSession) {
                        println("RUNNING with head unit ${session.headUnitVersion}")
                        session.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
                    }

                    override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
                        println("closed during ${session.closedDuring}: $reason ${error ?: ""}")
                        streaming.set(false)
                        done.countDown()
                    }
                },
                options = SessionOptions(sendPings = true),
            )
            session.start()
            done.await()
        }
    }
}

private fun stream(video: VideoChannel, units: AnnexB.Units, streaming: AtomicBoolean) {
    var i = 0
    var sent = 0
    val start = System.nanoTime()
    while (streaming.get()) {
        val f = units.frames[i % units.frames.size]
        val buf = ByteArray(10 + f.data.size)
        System.arraycopy(f.data, 0, buf, 10, f.data.size)
        video.sendFrame(buf, 10, f.data.size, (System.nanoTime() - start) / 1000, f.key) {}
        sent++
        if (sent % 150 == 0) println("sent $sent frames, dropped ${video.droppedFrames}")
        i++
        Thread.sleep(33)
    }
}

private fun loadPem(cert: File, key: File): PhoneIdentity {
    val cf = java.security.cert.CertificateFactory.getInstance("X.509")
    val certs = cert.inputStream().use { cf.generateCertificates(it) }.map { it as java.security.cert.X509Certificate }
    val der = java.util.Base64.getMimeDecoder().decode(
        key.readText().lines().filter { !it.startsWith("-----") }.joinToString(""))
    val pk = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
    return PhoneIdentity(certs.first(), pk, certs.drop(1))
}

/** Splits an Annex-B stream into SPS/PPS and access units (one picture per frame). */
object AnnexB {
    class Frame(val data: ByteArray, val key: Boolean)
    class Units(val config: ByteArray, val frames: List<Frame>)

    fun accessUnits(bytes: ByteArray): Units {
        val nals = split(bytes)
        val config = java.io.ByteArrayOutputStream()
        val frames = ArrayList<Frame>()
        var pending = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            val type = nal[nal.indexOfStartEnd()].toInt() and 0x1f
            when (type) {
                7, 8 -> if (frames.isEmpty()) config.write(nal) else pending.write(nal)
                1, 5 -> {
                    pending.write(nal)
                    frames += Frame(pending.toByteArray(), type == 5)
                    pending = java.io.ByteArrayOutputStream()
                }
                else -> pending.write(nal)
            }
        }
        return Units(config.toByteArray(), frames)
    }

    private fun ByteArray.indexOfStartEnd(): Int = if (this[2].toInt() == 1) 3 else 4

    private fun split(b: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                starts += if (i > 0 && b[i - 1].toInt() == 0) i - 1 else i
                i += 3
            } else i++
        }
        return starts.mapIndexed { k, s -> b.copyOfRange(s, if (k + 1 < starts.size) starts[k + 1] else b.size) }
    }
}

package net.prototux.aare.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.host.CarAppClusterScreen
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.ui.car.ClusterTurnCard
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.video.ProjectionDisplay
import net.prototux.aare.video.VideoEncoder

/**
 * The instrument cluster's own video, for head units that offer a cluster display (a second
 * video sink with display_type CLUSTER): the navigation app's cluster session when it has one
 * (FEATURE_CLUSTER), otherwise AARE's turn card (next maneuver, distance, road, arrival).
 */
class ClusterDisplay(private val context: Context, private val runtime: CarRuntime) : VideoChannel.Listener {
    private val main = Handler(Looper.getMainLooper())
    var channel: VideoChannel? = null
        private set
    private var encoder: VideoEncoder? = null
    private var display: ProjectionDisplay? = null

    fun open(service: aap_protobuf.service.ServiceOuterClass.Service): VideoChannel = VideoChannel(service, this).also { channel = it }

    // The cluster usually offers one configuration: take the smallest, it's a small screen.
    override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int =
        CarSession.videoCandidates(configs, preferred, TAG)
            .minByOrNull { CarSession.resolution(configs[it].codecResolution).second } ?: 0

    override fun onVideoStart(index: Int, config: VideoConfiguration) {
        main.post { runCatching { start(config) }.onFailure { Log.w(TAG, "cluster video", it) } }
    }

    override fun onVideoFocus(projected: Boolean) {
        main.post {
            encoder?.setSuspended(!projected)
            if (projected) encoder?.requestKeyFrame()
        }
    }

    override fun onKeyFrameNeeded() {
        encoder?.requestKeyFrame()
    }

    private fun start(config: VideoConfiguration) {
        stop()
        val ch = channel ?: return
        val (w, h) = CarSession.resolution(config.codecResolution)
        val fps = if (config.frameRate == VideoFrameRateType.VIDEO_FPS_60) 60 else 30
        val dpi = if (config.density > 0) config.density else CarSession.defaultDensity(minOf(w, h))
        Log.i(TAG, "cluster video ${w}x$h@$fps ${dpi}dpi")
        // lateinit: the sink below needs to know whether its encoder is still the current one.
        lateinit var enc: VideoEncoder
        enc = VideoEncoder(w, h, fps, sink = object : VideoEncoder.Sink {
            override fun onCodecConfig(data: ByteArray, offset: Int, length: Int) = ch.sendCodecConfig(data, offset, length)
            override fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean) =
                ch.sendFrame(data, offset, length, ptsUs, keyFrame) { encoder?.recycle(it) }
            override fun onError(e: Exception) {
                Log.e(TAG, "cluster encoder failed, restarting", e)
                main.post {
                    // Stopped (the session ended), or this encoder was already replaced.
                    if (encoder !== enc) return@post
                    // A cluster that can't restart goes dark; the main display carries on.
                    runCatching { start(config) }.onFailure { Log.w(TAG, "cluster video restart", it); stop() }
                }
            }
        })
        enc.start()
        encoder = enc
        // Assigned before show(), so that a failing show() still has stop() release the display.
        val pd = ProjectionDisplay(context, w, h, dpi, enc.inputSurface) { Content() }
        display = pd
        pd.show()
    }

    @Composable
    private fun Content() {
        val ui = runtime.ui
        CarTheme(night = true) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val nav = ui.app(ui.activeNavAppId)
                val conn = nav?.takeIf { ui.trip != null }?.let { runtime.clusterConnection(it) }
                if (conn != null) CarAppClusterScreen(conn, runtime, Modifier.fillMaxSize())
                else ClusterTurnCard(ui, runtime, Modifier.fillMaxSize())
            }
        }
    }

    fun stop() {
        display?.release(); display = null
        encoder?.stop(); encoder = null
    }

    private companion object {
        const val TAG = "AareCluster"
    }
}

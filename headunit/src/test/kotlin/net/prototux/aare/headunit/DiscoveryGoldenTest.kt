package net.prototux.aare.headunit

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.cert.SelfSignedCertificate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The service discovery response, byte for byte, as the head unit sent it before the test-harness
 * options existed: with none of them set it must not change.
 */
class DiscoveryGoldenTest {
    @Test
    fun defaultOptions() = check("default", HeadUnit.Options())

    @Test
    fun existingOptions() =
        check("big", HeadUnit.Options(width = 1920, height = 1080, dpi = 240, fps60 = true, rightHandDrive = true, cluster = true, noTouch = true))

    @Test
    fun portrait() = check("portrait", HeadUnit.Options(width = 720, height = 1280))

    @Test
    fun portrait1080() = check("portrait1080", HeadUnit.Options(width = 1080, height = 1920, dpi = 240))

    @Test
    fun smallWithName() = check("small", HeadUnit.Options(width = 800, height = 480, name = "Some car"))

    private fun check(golden: String, o: HeadUnit.Options) =
        assertEquals(GOLDEN.getValue(golden), Base64.getEncoder().encodeToString(headUnit(o).discovery().toByteArray()))

    companion object {
        /** Serialized by the head unit before the test options existed. */
        private val GOLDEN = mapOf(
            "default" to "ChMIARoPCAMiCwgCEAIYACAAKKABChIIAhoOCAEQAxoICID3AhAQGAIKEQgDGg0IARABGgcIgH0QEBgBChEIBBoNCAEQAhoHCIB9EBAYAQoPCAUqCwgBEgcIgH0QEBgBCiYIBiIiChgDBAUGExQVFhdUVVdYfn+AgASBgASCgAQSBgiAChDQBQpaCAcSVgoCCA0KAggKCgIIAQoCCAMKAggICgIIBwoCCAIKAggECgIIBQoCCAYKAggLCgIIDAoCCA4KAggPCgIIEAoCCBEKAggSCgIIEwoCCBQKAggVCgIIFhgBCgkICEIFCPQDEAIKBAgJSgAKBAgPUgAKGQgKMhUKE1NLSVBfVEhJU19CTFVFVE9PVEgwAHIWQUFSRSBkZXNrdG9wIGhlYWQgdW5pdIIBDQoLCLgXEOgHGMgBIAWKATMKBEFBUkUSB0Rlc2t0b3AaBDIwMjYqBEFBUkUyEWRlc2t0b3AgaGVhZCB1bml0QgMxLjA=",
            "big" to "ChMIARoPCAMiCwgDEAEYACAAKPABChcICxoTCAMiCwgBEAIYACAAKKABMAE4AQoSCAIaDggBEAMaCAiA9wIQEBgCChEIAxoNCAEQARoHCIB9EBAYAQoRCAQaDQgBEAIaBwiAfRAQGAEKDwgFKgsIARIHCIB9EBAYAQoeCAYiGgoYAwQFBhMUFRYXVFVXWH5/gIAEgYAEgoAECloIBxJWCgIIDQoCCAoKAggBCgIIAwoCCAgKAggHCgIIAgoCCAQKAggFCgIIBgoCCAsKAggMCgIIDgoCCA8KAggQCgIIEQoCCBIKAggTCgIIFAoCCBUKAggWGAEKCQgIQgUI9AMQAgoECAlKAAoECA9SAAoZCAoyFQoTU0tJUF9USElTX0JMVUVUT09USDABchZBQVJFIGRlc2t0b3AgaGVhZCB1bml0ggENCgsIuBcQ6AcYyAEgBYoBMwoEQUFSRRIHRGVza3RvcBoEMjAyNioEQUFSRTIRZGVza3RvcCBoZWFkIHVuaXRCAzEuMA==",
            "portrait" to "ChMIARoPCAMiCwgGEAIYACAAKKABChIIAhoOCAEQAxoICID3AhAQGAIKEQgDGg0IARABGgcIgH0QEBgBChEIBBoNCAEQAhoHCIB9EBAYAQoPCAUqCwgBEgcIgH0QEBgBCiYIBiIiChgDBAUGExQVFhdUVVdYfn+AgASBgASCgAQSBgjQBRCACgpaCAcSVgoCCA0KAggKCgIIAQoCCAMKAggICgIIBwoCCAIKAggECgIIBQoCCAYKAggLCgIIDAoCCA4KAggPCgIIEAoCCBEKAggSCgIIEwoCCBQKAggVCgIIFhgBCgkICEIFCPQDEAIKBAgJSgAKBAgPUgAKGQgKMhUKE1NLSVBfVEhJU19CTFVFVE9PVEgwAHIWQUFSRSBkZXNrdG9wIGhlYWQgdW5pdIIBDQoLCLgXEOgHGMgBIAWKATMKBEFBUkUSB0Rlc2t0b3AaBDIwMjYqBEFBUkUyEWRlc2t0b3AgaGVhZCB1bml0QgMxLjA=",
            "portrait1080" to "ChMIARoPCAMiCwgHEAIYACAAKPABChIIAhoOCAEQAxoICID3AhAQGAIKEQgDGg0IARABGgcIgH0QEBgBChEIBBoNCAEQAhoHCIB9EBAYAQoPCAUqCwgBEgcIgH0QEBgBCiYIBiIiChgDBAUGExQVFhdUVVdYfn+AgASBgASCgAQSBgi4CBCADwpaCAcSVgoCCA0KAggKCgIIAQoCCAMKAggICgIIBwoCCAIKAggECgIIBQoCCAYKAggLCgIIDAoCCA4KAggPCgIIEAoCCBEKAggSCgIIEwoCCBQKAggVCgIIFhgBCgkICEIFCPQDEAIKBAgJSgAKBAgPUgAKGQgKMhUKE1NLSVBfVEhJU19CTFVFVE9PVEgwAHIWQUFSRSBkZXNrdG9wIGhlYWQgdW5pdIIBDQoLCLgXEOgHGMgBIAWKATMKBEFBUkUSB0Rlc2t0b3AaBDIwMjYqBEFBUkUyEWRlc2t0b3AgaGVhZCB1bml0QgMxLjA=",
            "small" to "ChMIARoPCAMiCwgBEAIYACAAKKABChIIAhoOCAEQAxoICID3AhAQGAIKEQgDGg0IARABGgcIgH0QEBgBChEIBBoNCAEQAhoHCIB9EBAYAQoPCAUqCwgBEgcIgH0QEBgBCiYIBiIiChgDBAUGExQVFhdUVVdYfn+AgASBgASCgAQSBgigBhDgAwpaCAcSVgoCCA0KAggKCgIIAQoCCAMKAggICgIIBwoCCAIKAggECgIIBQoCCAYKAggLCgIIDAoCCA4KAggPCgIIEAoCCBEKAggSCgIIEwoCCBQKAggVCgIIFhgBCgkICEIFCPQDEAIKBAgJSgAKBAgPUgAKGQgKMhUKE1NLSVBfVEhJU19CTFVFVE9PVEgwAHIIU29tZSBjYXKCAQ0KCwi4FxDoBxjIASAFigEzCgRBQVJFEgdEZXNrdG9wGgQyMDI2KgRBQVJFMhFkZXNrdG9wIGhlYWQgdW5pdEIDMS4w",
        )

        private val identity by lazy { SelfSignedCertificate.generate("test").let { PhoneIdentity(it.certificate, it.keyPair.private) } }

        /** A head unit on a dead transport: enough to build its messages. */
        fun headUnit(o: HeadUnit.Options) = HeadUnit(object : Transport {
            override val name = "test"
            override val input = ByteArrayInputStream(ByteArray(0))
            override val output = ByteArrayOutputStream()
            override fun close() {}
        }, identity, o, object : HeadUnit.Listener {
            override fun status(text: String) {}
            override fun videoStarted(width: Int, height: Int) {}
            override fun video(data: ByteArray, offset: Int, length: Int) {}
            override fun videoCodecConfig(data: ByteArray, offset: Int, length: Int) {}
            override fun audioStart(stream: AudioStreamType, config: AudioConfiguration) {}
            override fun audio(stream: AudioStreamType, data: ByteArray, offset: Int, length: Int) {}
            override fun audioStop(stream: AudioStreamType) {}
            override fun micOpened() {}
            override fun micClosed() {}
            override fun navigation(text: String) {}
            override fun nowPlaying(text: String) {}
            override fun closed(reason: String) {}
        })
    }
}

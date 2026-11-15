package net.prototux.aare.aap.channels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AacTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun audioSpecificConfigForTheRatesHeadUnitsUse() {
        // AAC-LC (object type 2), 48 kHz (index 3), stereo
        assertArrayEquals(bytes(0x11, 0x90), Aac.audioSpecificConfig(48_000, 2))
        // 16 kHz (index 8), mono: navigation prompts
        assertArrayEquals(bytes(0x14, 0x08), Aac.audioSpecificConfig(16_000, 1))
        assertArrayEquals(bytes(0x12, 0x10), Aac.audioSpecificConfig(44_100, 2))
    }

    @Test
    fun adtsHeaderCarriesProfileRateChannelsAndLength() {
        val buf = ByteArray(9)
        Aac.writeAdtsHeader(buf, 2, 48_000, 2, 100)
        // 107 bytes in all (header included), buffer fullness 0x7FF, one raw block
        assertArrayEquals(bytes(0xFF, 0xF1, 0x4C, 0x80, 0x0D, 0x7F, 0xFC), buf.copyOfRange(2, 9))
        Aac.writeAdtsHeader(buf, 0, 16_000, 1, 2000)
        // 2007 = 0x7D7: 13 bits split over bytes 3 to 5
        assertArrayEquals(bytes(0xFF, 0xF1, 0x60, 0x40, 0xFA, 0xFF, 0xFC), buf.copyOfRange(0, 7))
    }

    @Test
    fun aRateAacCannotSignalIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Aac.audioSpecificConfig(47_000, 2) }
    }
}

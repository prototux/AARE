package net.prototux.aare.host

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavAppFallbackTest {
    @Test
    fun nullWhenNoneOfTheFallbackAppsAreInstalled() {
        assertNull(pickFallbackNavPackage(listOf("com.waze", "com.google.android.apps.maps")))
        assertNull(pickFallbackNavPackage(emptyList()))
    }

    @Test
    fun theOnlyOneInstalledWinsWithoutConsultingRandom() {
        // A Random that would throw if asked for a number: with one candidate it must not be.
        val random = object : Random() {
            override fun nextBits(bitCount: Int) = throw AssertionError("should not need a random pick")
        }
        assertEquals("com.skt.tmap.ku", pickFallbackNavPackage(listOf("com.waze", "com.skt.tmap.ku"), random))
    }

    @Test
    fun picksAmongInstalledFallbackAppsOnly() {
        val installed = listOf("com.locnall.KimGiSa", "com.nhn.android.nmap", "com.waze")
        repeat(20) {
            val pick = pickFallbackNavPackage(installed, Random(it))
            assertTrue(pick in KOREAN_NAV_FALLBACK_PACKAGES)
            assertTrue(pick in installed)
        }
    }
}

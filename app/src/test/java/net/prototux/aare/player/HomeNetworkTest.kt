package net.prototux.aare.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.URL

class HomeNetworkTest {
    @Test fun homeHostsAreLocalNamesAndPrivateAddresses() {
        for (h in listOf(
            "192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.254",
            "169.254.3.4", "127.0.0.1", "localhost", "jellyfin.local", "JF.LOCAL.",
            "[::1]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]", "[fe80::1%wlan0]",
            "[::ffff:192.168.1.10]",
        )) {
            assertTrue(h, HomeNetwork.isHomeHost(h))
        }
    }

    @Test fun publicHostsAndNonCanonicalAddressesRefused() {
        for (h in listOf(
            "example.org", "jf.lan", "local", ".local", "evil.local.example.org",
            "8.8.8.8", "1.1.1.1", "11.0.0.1", "172.15.0.1", "172.32.0.1", "192.169.1.1",
            "100.64.0.1", "10.1", "010.0.0.1", "[2001:db8::1]", "[fe00::1]", "[fec0::1]",
            "[::ffff:8.8.8.8]", "",
        )) {
            assertFalse(h, HomeNetwork.isHomeHost(h))
        }
    }

    @Test fun plainHttpNeedsOptionAndHomeHost() {
        assertNull(HomeNetwork.plainHttpRefusal(URL("https://example.org/x"), allowLanHttp = false))
        assertNull(HomeNetwork.plainHttpRefusal(URL("https://192.168.1.20:8096/x"), allowLanHttp = false))

        assertEquals(false, HomeNetwork.plainHttpRefusal(URL("http://192.168.1.20:8096/"), allowLanHttp = false)?.outsideHome)
        assertEquals(false, HomeNetwork.plainHttpRefusal(URL("http://jellyfin.local:8096/"), allowLanHttp = false)?.outsideHome)
        assertNull(HomeNetwork.plainHttpRefusal(URL("http://192.168.1.20:8096/"), allowLanHttp = true))
        assertNull(HomeNetwork.plainHttpRefusal(URL("http://jellyfin.local:8096/"), allowLanHttp = true))

        assertEquals(true, HomeNetwork.plainHttpRefusal(URL("http://example.org:8096/"), allowLanHttp = false)?.outsideHome)
        assertEquals(true, HomeNetwork.plainHttpRefusal(URL("http://example.org:8096/"), allowLanHttp = true)?.outsideHome)
        assertEquals(true, HomeNetwork.plainHttpRefusal(URL("http://8.8.8.8:8096/"), allowLanHttp = true)?.outsideHome)
    }

    @Test fun localNameMustResolveOnlyToHomeAddresses() {
        val url = URL("http://jf.local:8096/System/Info")
        val home = InetAddress.getByName("192.168.1.20")
        val linkLocal6 = InetAddress.getByName("fe80::1")
        val pub = InetAddress.getByName("8.8.8.8")

        HomeNetwork.requireHomeAddress(url, allowLanHttp = true) { arrayOf(home) }
        HomeNetwork.requireHomeAddress(url, allowLanHttp = true) { arrayOf(home, linkLocal6) }

        try {
            HomeNetwork.requireHomeAddress(url, allowLanHttp = true) { arrayOf(pub) }
            fail("a .local name resolving to a public IP passed")
        } catch (e: PlainHttpRefused) {
            assertTrue(e.outsideHome)
        }

        try {
            HomeNetwork.requireHomeAddress(url, allowLanHttp = true) { arrayOf(pub, home) }
            fail("a .local name with a public IP in its lookup passed")
        } catch (e: PlainHttpRefused) {
            assertTrue(e.outsideHome)
        }

        try {
            HomeNetwork.requireHomeAddress(url, allowLanHttp = false) { arrayOf(home) }
            fail("plain http passed with the option off")
        } catch (e: PlainHttpRefused) {
            assertFalse(e.outsideHome)
        }

        // https skips lookup and passes regardless of option.
        HomeNetwork.requireHomeAddress(URL("https://example.org/x"), allowLanHttp = false) {
            fail("lookup must not be called for https")
            emptyArray()
        }
    }
}

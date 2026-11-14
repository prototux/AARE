package net.prototux.aare.devlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    private fun redact(line: String) = LogRedactor().redact(line)

    @Test fun macKeepsTheVendorPart() = assertEquals(
        "wireless: RFCOMM connected to 18:9E:AD:xx:xx:xx",
        redact("wireless: RFCOMM connected to 18:9E:AD:2F:0B:12"),
    )

    @Test fun macLowerCase() = assertEquals("18:9e:ad:xx:xx:xx", redact("18:9e:ad:2f:0b:12"))

    @Test fun ipv4KeepsTheNetworkPart() {
        assertEquals("AAP server 192.168.x.x:5288", redact("AAP server 192.168.43.1:5288"))
        assertEquals("/10.0.x.x:40000", redact("/10.0.0.12:40000"))
    }

    @Test fun loopbackStays() {
        assertEquals("listening on 127.0.0.1:5277", redact("listening on 127.0.0.1:5277"))
        assertEquals("bind 0.0.0.0:1", redact("bind 0.0.0.0:1"))
    }

    @Test fun ipv6LinkLocal() = assertEquals(
        "connect failed: /<ipv6>%wlan0",
        redact("connect failed: /fe80::1c2b:3aff:fe4d:5e6f%wlan0"),
    )

    @Test fun ipv6FullForm() = assertEquals("a <ipv6> b", redact("a 2001:db8:1:2:3:4:5:6 b"))

    @Test fun timeIsNotIpv6() = assertEquals("at 18:09:24 now", redact("at 18:09:24 now"))

    @Test fun ssidTemplates() {
        val cases = mapOf(
            "wireless: \"Car WiFi\" available as 123" to "wireless: \"<ssid>\" available as 123",
            "wireless: requesting network \"Car WiFi\" (WPA2)" to "wireless: requesting network \"<ssid>\" (WPA2)",
            "no address on \"Car WiFi\" after 15 s" to "no address on \"<ssid>\" after 15 s",
            "Android refused to add \"Car WiFi\"" to "Android refused to add \"<ssid>\"",
            "wireless: joining \"Car WiFi\" threw" to "wireless: joining \"<ssid>\" threw",
            "wireless: joined \"Car WiFi\" as 192.168.43.20 (gateway 192.168.43.1)" to
                "wireless: joined \"<ssid>\" as 192.168.x.x (gateway 192.168.x.x)",
            "could not join \"Car WiFi\": timeout" to "could not join \"<ssid>\": timeout",
            "wireless: already connected to \"Car WiFi\", using that network" to
                "wireless: already connected to \"<ssid>\", using that network",
            "credentials ssid=\"Car WiFi\" bssid=-" to "credentials ssid=\"<ssid>\" bssid=-",
            "ignoring malformed BSSID \"zz\"" to "ignoring malformed BSSID \"<ssid>\"",
            "wifi \"Car WiFi\" 192.168.43.1:5288 (i20)" to "wifi \"<ssid>\" 192.168.x.x:5288 (i20)",
        )
        for ((input, expected) in cases) assertEquals(input, expected, redact(input))
    }

    @Test fun certificateNameStays() {
        val line = "connecting usb with certificate \"Android Auto 2026\""
        assertEquals(line, redact(line))
    }

    @Test fun sha256Shortened() = assertEquals(
        "not trusted (SHA-256 abababab…)",
        redact("not trusted (SHA-256 " + "ab".repeat(32) + ")"),
    )

    @Test fun usbSerialMasked() = assertEquals(
        "trusted head unit certificate for usb:Jancar|AAuto|<serial>",
        redact("trusted head unit certificate for usb:Jancar|AAuto|0123456789"),
    )

    @Test fun usbSerialMaskedWhenTheManufacturerHasSpaces() = assertEquals(
        "not trusted: usb:Car Maker Inc.|Head Unit 2|<serial>: SHA-256 abababab…",
        redact("not trusted: usb:Car Maker Inc.|Head Unit 2|SN0123456789: SHA-256 " + "ab".repeat(32)),
    )

    @Test fun dnFields() = assertEquals(
        "head unit cert: CN=<x>,O=Maker,C=DE",
        redact("head unit cert: CN=HU-12345,O=Maker,C=DE"),
    )

    @Test fun trackMetadata() = assertEquals(
        "PlaybackInfo(packageName=app.x, title=<title>, artist=<artist>, album=<album>, art=null)",
        redact("PlaybackInfo(packageName=app.x, title=Song, artist=Band, album=Disc, art=null)"),
    )

    @Test fun autoReplyName() = assertEquals(
        "driving: auto-replied to <name>",
        redact("driving: auto-replied to Mario Rossi"),
    )

    @Test fun uriPathMasked() {
        assertEquals("no art for content://media/<path>", redact("no art for content://media/external/audio/123"))
        val github = "see https://github.com/prototux/AARE/issues/1"
        assertEquals(github, redact(github))
    }

    @Test fun credentials() = assertEquals("password=<x> sent", redact("password=hunter2 sent"))

    @Test fun coordinates() {
        assertEquals("…?latitude=<lat>&longitude=<lon>", redact("…?latitude=44.6&longitude=10.9"))
        assertEquals("at <lat>, <lon>", redact("at 44.64712, 10.92524"))
    }

    @Test fun phoneAndEmail() {
        assertEquals("call <phone>", redact("call +39 333 1234567"))
        assertEquals("from <email>", redact("from a.b@example.org"))
    }

    @Test fun paths() {
        assertEquals("/home/<user>/x", redact("/home/gennaro/x"))
        assertEquals("/storage/<volume>/Music", redact("/storage/1A2B-3C4D/Music"))
    }

    @Test fun knownValuesOnBoundaries() {
        assertEquals(
            "Connessione a <name> (wireless)",
            LogRedactor(mapOf("i20RR" to "<name>")).redact("Connessione a i20RR (wireless)"),
        )
        assertEquals(
            "<name> on",
            LogRedactor(mapOf("My Car" to "<name>", "My Car Audio" to "<name>")).redact("My Car Audio on"),
        )
    }

    @Test fun knownValuesGluedToLettersOrDigitsStay() {
        val known = LogRedactor(mapOf("500" to "<name>", "Car" to "<name>"))
        assertEquals("pingIntervalMs=1500 CarSession: <name> <name>", known.redact("pingIntervalMs=1500 CarSession: 500 Car"))
        assertEquals("(<name>) \"<name>\"", known.redact("(500) \"Car\""))
    }

    @Test fun headUnitDisplayName() {
        assertEquals(
            "head unit: Hyundai i20 Jancar AAuto [<name>] services: video#1, audio:AUDIO_STREAM_MEDIA#2, input#3",
            redact("head unit: Hyundai i20 Jancar AAuto [My i20 [2]] services: video#1, audio:AUDIO_STREAM_MEDIA#2, input#3"),
        )
        assertEquals(
            "head unit: Maker Model [<name>] services: video#1, input#3",
            redact("head unit: Maker Model [CN=Jane Doe, title=x] services: video#1, input#3"),
        )
    }

    @Test fun headUnitEmptyDisplayNameStays() = assertEquals(
        "head unit: Maker Model [] services: video#1",
        redact("head unit: Maker Model [] services: video#1"),
    )

    @Test fun shortKnownValuesIgnored() =
        assertEquals("about", LogRedactor(mapOf("ab" to "<name>")).redact("about"))

    @Test fun logcatPrefixUntouched() {
        val lines = listOf(
            "2026-11-12 18:09:24.123  5443  5443 I Aare: rx AapMessage(ch=0, id=0xb, enc len=6)",
            "1.4.0",
            "Bitmap@33c2406",
            "positionMs=123456789",
            "uid 10353",
        )
        for (line in lines) assertEquals(line, redact(line))
    }

    private val sha = "ab".repeat(32)

    @Test fun macBeforeColonInPinnedHeadUnitsTemplate() {
        assertEquals(
            "trusted head unit certificate for 18:9E:AD:xx:xx:xx: SHA-256 abababab…",
            redact("trusted head unit certificate for 18:9E:AD:2F:0B:12: SHA-256 $sha"),
        )
        assertEquals(
            "trusted head unit certificate for usb:Jancar|AAuto|<serial>: SHA-256 abababab…",
            redact("trusted head unit certificate for usb:Jancar|AAuto|0123456789: SHA-256 $sha"),
        )
    }

    @Test fun longColonHexRunsAndTimesAreNotMacs() {
        val line = "ids 00:11:22:33:44:55:66 at 18:09:24:123"
        assertEquals(line, redact(line))
    }

    @Test fun emailRuleIsLinearOnLongRuns() {
        val line = "a".repeat(4000)
        val start = System.nanoTime()
        repeat(100) { assertEquals(line, redact(line)) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 3000)
    }

    @Test fun derEncodedDnAttributes() = assertEquals(
        "head unit cert: CN=<x>,2.5.4.5=#<x>,O=Maker,C=DE",
        redact("head unit cert: CN=HU,2.5.4.5=#1309414243313233343536,O=Maker,C=DE"),
    )

    @Test fun derEmailOid() = assertEquals(
        "1.2.840.113549.1.9.1=#<x>,O=Maker",
        redact("1.2.840.113549.1.9.1=#1611616263406578616d706c652e6f7267,O=Maker"),
    )

    @Test fun lowerCaseUidIsNotADnField() {
        val line = "AudioRecord uid=10353 pid=5443 session 12 started"
        assertEquals(line, redact(line))
    }

    @Test fun ssidContainingAQuote() = assertEquals(
        "wifi \"<ssid>\" 192.168.x.x:5288",
        redact("wifi \"Bob\"s Car\" 192.168.43.1:5288"),
    )

    @Test fun emptyAuthorityFileUri() = assertEquals(
        "file:///<path>",
        redact("file:///storage/emulated/0/Music/x.mp3"),
    )

    @Test fun quotedCredentialValue() {
        assertEquals("\"password\" : \"<x>\"", redact("\"password\" : \"hunter2\""))
        assertEquals("{\"serial\":\"<x>\"}", redact("{\"serial\":\"abc123\"}"))
        assertEquals("password: \"<x>\" ok", redact("password: \"my pass\" ok"))
    }

    @Test fun coordinateKeyCaseKept() =
        assertEquals("Latitude=<lat>&LON=<lon>", redact("Latitude=44.6&LON=10.9"))

    @Test fun ipv4MappedIpv6() =
        assertEquals("from /<ipv6>:5288", redact("from /::ffff:192.168.43.20:5288"))

    @Test fun macAfterAWordEndingInAHexLetter() {
        assertEquals("mac:18:9E:AD:xx:xx:xx", redact("mac:18:9E:AD:2F:0B:12"))
        assertEquals("id:18:9E:AD:xx:xx:xx", redact("id:18:9E:AD:2F:0B:12"))
    }

    @Test fun credentialsWithOtherSeparators() {
        assertEquals("serial#<x>", redact("serial#123"))
        assertEquals("imei-<x>", redact("imei-351234567890123"))
        assertEquals("psk(<x>", redact("psk(abc)"))
        assertEquals("password=<x> sent", redact("password=<x> sent"))
    }

    @Test fun dnEscapedComma() = assertEquals(
        "CN=<x>,O=Maker",
        redact("CN=Smith\\, John,O=Maker"),
    )
}

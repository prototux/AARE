package net.prototux.aare.aap.wireless

import aap_protobuf.aaw.StatusOuterClass.Status
import aap_protobuf.aaw.WifiConnectionStatusOuterClass.WifiConnectionStatus
import aap_protobuf.aaw.WifiInfoResponseOuterClass.WifiInfoResponse
import aap_protobuf.aaw.WifiPing.WifiPingRequest
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import aap_protobuf.aaw.WifiStartResponseOuterClass.WifiStartResponse
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiProjectionProtocolInfo
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest
import aap_protobuf.aaw.WifiVersionResponseOuterClass.WifiVersionResponse
import aap_protobuf.service.wifiprojection.message.AccessPointTypeOuterClass.AccessPointType
import com.google.protobuf.MessageLite
import net.prototux.aare.aap.AapLog
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs the phone side against a scripted head unit over in-memory pipes. */
class WirelessBootstrapTest {

    /** One direction of the RFCOMM link; closing [output] is the peer hanging up. */
    private class Link {
        private val pipe = Pipe.open()
        val input: InputStream = Channels.newInputStream(pipe.source())
        val output: OutputStream = Channels.newOutputStream(pipe.sink())
    }

    /** The head unit's end of the link. */
    private class FakeHu(private val toPhone: OutputStream, private val fromPhone: InputStream) {
        fun send(id: Int, msg: MessageLite) = writeWppFrame(toPhone, id, msg.toByteArray())
        fun send(vararg frames: Pair<Int, MessageLite>) =
            toPhone.write(frames.fold(ByteArray(0)) { acc, (id, msg) -> acc + encodeWppFrame(id, msg.toByteArray()) })

        fun read(): WppFrame = readWppFrame(fromPhone) ?: throw AssertionError("phone closed the link")

        /** Next frame that is not one of the phone's keepalive pings. */
        fun expect(id: Int): WppFrame {
            var frame = read()
            while (frame.id == WppMessageId.PING_REQUEST && id != WppMessageId.PING_REQUEST) frame = read()
            assertEquals("expected ${WppMessageId.name(id)}, got $frame", id, frame.id)
            return frame
        }

        fun hangUp() = toPhone.close()
    }

    private class Phone(val join: (WifiCredentials) -> JoinResult) : WirelessBootstrap.Listener {
        val credentials = LinkedBlockingQueue<WifiCredentials>()
        val ready = LinkedBlockingQueue<Pair<String, Int>>()
        override fun connectWifi(credentials: WifiCredentials): JoinResult {
            this.credentials.put(credentials)
            return join(credentials)
        }
        override fun onReady(ip: String, port: Int) = ready.put(ip to port)
    }

    private val quiet = object : AapLog {
        override fun i(msg: String) = println("I/wpp: $msg")
        override fun w(msg: String, t: Throwable?) = println("W/wpp: $msg ${t ?: ""}")
        override fun e(msg: String, t: Throwable?) = println("E/wpp: $msg ${t ?: ""}")
    }

    private val huToPhone = Link()
    private val phoneToHu = Link()
    private val hu = FakeHu(huToPhone.output, phoneToHu.input)
    private var bootstrap: WirelessBootstrap? = null
    private var failure: Throwable? = null
    private val finished = CountDownLatch(1)

    private fun start(
        phone: Phone,
        options: WirelessBootstrap.Options = WirelessBootstrap.Options(messageTimeoutMs = 3_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 0),
    ): WirelessBootstrap {
        val b = WirelessBootstrap(huToPhone.input, phoneToHu.output, phone, quiet, options)
        bootstrap = b
        thread(name = "phone") {
            try { b.run() } catch (t: Throwable) { failure = t } finally { finished.countDown() }
        }
        return b
    }

    private fun awaitEnd() = assertTrue("bootstrap did not finish", finished.await(5, TimeUnit.SECONDS))

    @After
    fun tearDown() {
        bootstrap?.close()
        runCatching { hu.hangUp() }
    }

    private fun startRequest(ip: String = "10.0.0.1", port: Int = 5288) =
        WifiStartRequest.newBuilder().setIpAddress(ip).setPort(port).build()

    private fun infoResponse(mode: Int = WppSecurityMode.WPA2_PERSONAL, bssid: String = "aa:bb:cc:dd:ee:ff") =
        WifiInfoResponse.newBuilder()
            .setSsid("AndroidAuto-HU").setPassword("secret-passphrase").setBssid(bssid)
            .setSecurityMode(mode).setAccessPointType(AccessPointType.DYNAMIC).build()

    private fun status(frame: WppFrame) = WifiConnectionStatus.parseFrom(frame.payload).status.number

    /** A WPP 4.2 head unit like Google's receiver library, which also offers WPP over TCP. */
    private fun versionRequest() =
        WifiVersionRequest.newBuilder().setMajor(4).setMinor(2)
            .setWppInfo(WifiProjectionProtocolInfo.newBuilder().setIpAddress("0.0.0.0").setPort(1234)).build()

    @Test(timeout = 10_000)
    fun versionAndStartBackToBackLikeOpenauto() {
        val phone = Phone { JoinResult.Joined(localAddress = "10.0.0.23", gateway = "10.0.0.1") }
        val b = start(phone)

        // openauto writes an empty version request and the start request in one go
        hu.send(WppMessageId.VERSION_REQUEST to WifiVersionRequest.newBuilder().setMajor(4).setMinor(2).build(),
            WppMessageId.START_REQUEST to startRequest(port = 5000))
        val version = WifiVersionResponse.parseFrom(hu.expect(WppMessageId.VERSION_RESPONSE).payload)
        assertEquals(4, version.major)
        assertEquals(2, version.minor)
        assertEquals(WppStatus.SUCCESS, version.status)
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())

        val start = WifiStartResponse.parseFrom(hu.expect(WppMessageId.START_RESPONSE).payload)
        assertEquals(WppStatus.SUCCESS, start.status.number)
        assertEquals("10.0.0.23", start.ipAddress)
        assertEquals(WppStatus.SUCCESS, status(hu.expect(WppMessageId.CONNECTION_STATUS)))
        assertEquals("10.0.0.1" to 5000, phone.ready.poll(2, TimeUnit.SECONDS))

        val credentials = phone.credentials.single()
        assertEquals("AndroidAuto-HU", credentials.ssid)
        assertEquals("secret-passphrase", credentials.password)
        assertEquals("AA:BB:CC:DD:EE:FF", credentials.bssid)
        assertEquals(WifiSecurity.WPA2_PERSONAL, credentials.security)
        assertEquals(AccessPointType.DYNAMIC.number, credentials.accessPointType)
        assertTrue("password must not be logged", "secret" !in credentials.toString())

        // the link is held for the session and keeps answering pings
        assertEquals(WirelessBootstrap.State.READY, b.state)
        val ping = WifiPingRequest.newBuilder().setTimestamp(1234).build()
        hu.send(WppMessageId.PING_REQUEST, ping)
        assertArrayEquals(ping.toByteArray(), hu.expect(WppMessageId.PING_RESPONSE).payload)

        hu.hangUp()
        awaitEnd()
        assertNull(failure)
        assertEquals(WirelessBootstrap.State.DONE, b.state)
    }

    @Test(timeout = 10_000)
    fun bareStartRequestLikeTheDongle() {
        val joinStarted = CountDownLatch(1)
        val phone = Phone { joinStarted.countDown(); Thread.sleep(500); JoinResult.Joined() }
        start(phone, WirelessBootstrap.Options(messageTimeoutMs = 3_000, keepaliveIntervalMs = 100, frameGapMs = 0))

        hu.send(WppMessageId.START_REQUEST, startRequest(ip = "192.168.4.1"))
        // the dongle aborts unless the very next frame is the info request
        assertEquals(WppMessageId.INFO_REQUEST, hu.read().id)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse(mode = WppSecurityMode.WPA2_WPA3_PERSONAL, bssid = ""))
        assertTrue(joinStarted.await(2, TimeUnit.SECONDS))
        // then it reads two frames, whatever they are (here keepalive pings), and hangs up
        assertEquals(WppMessageId.PING_REQUEST, hu.read().id)
        hu.read()
        hu.hangUp()

        // losing the link after the credentials is not fatal: the TCP connect is what matters
        assertEquals("192.168.4.1" to 5288, phone.ready.poll(3, TimeUnit.SECONDS))
        awaitEnd()
        assertNull(failure)
        val credentials = phone.credentials.single()
        assertNull(credentials.bssid)
        assertEquals(WifiSecurity.WPA2_WPA3_PERSONAL, credentials.security)
    }

    @Test(timeout = 10_000)
    fun unsolicitedCredentialsSkipTheInfoRequest() {
        val phone = Phone { JoinResult.Joined(localAddress = "10.0.0.5") }
        start(phone)

        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        hu.send(WppMessageId.START_REQUEST, startRequest())
        assertEquals(WppMessageId.START_RESPONSE, hu.read().id)
        assertEquals(WppMessageId.CONNECTION_STATUS, hu.read().id)
        assertEquals("10.0.0.1" to 5288, phone.ready.poll(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000)
    fun pingsAreAnsweredWhileJoining() {
        val release = CountDownLatch(1)
        val phone = Phone { release.await(); JoinResult.Joined() }
        start(phone)

        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        phone.credentials.take()
        hu.send(WppMessageId.PING_REQUEST, WifiPingRequest.newBuilder().setTimestamp(42).build())
        assertEquals(42, WifiPingRequest.parseFrom(hu.expect(WppMessageId.PING_RESPONSE).payload).timestamp)
        release.countDown()
        hu.expect(WppMessageId.START_RESPONSE)
        hu.expect(WppMessageId.CONNECTION_STATUS)
    }

    @Test(timeout = 10_000)
    fun joinFailureIsReportedAndNothingIsStarted() {
        val phone = Phone { JoinResult.Failed(WppStatus.PHONE_WIFI_DISABLED, "Wi-Fi is off") }
        start(phone)

        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        // only a failed WifiConnectionStatus: no WifiStartResponse claiming success
        val frame = hu.read()
        assertEquals(WppMessageId.CONNECTION_STATUS, frame.id)
        val connectionStatus = WifiConnectionStatus.parseFrom(frame.payload)
        assertEquals(WppStatus.PHONE_WIFI_DISABLED, connectionStatus.status.number)
        // a text for the driver, not ours
        assertEquals(WirelessBootstrap.PHONE_WIFI_DISABLED_MESSAGE, connectionStatus.errorMessage)

        awaitEnd()
        val e = failure as WirelessBootstrapException
        assertEquals(WppStatus.PHONE_WIFI_DISABLED, e.status)
        assertFalse("a Wi-Fi problem on the phone isn't the car not being ready", e.whileNegotiating)
        assertNull(phone.ready.poll())
        assertNull("the phone closes the link after a failure", readWppFrame(phoneToHu.input))
    }

    @Test(timeout = 10_000)
    fun otherJoinFailuresCarryNoErrorText() {
        start(Phone { JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "no address on \"AndroidAuto-HU\" after 40 s") })
        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        val connectionStatus = WifiConnectionStatus.parseFrom(hu.expect(WppMessageId.CONNECTION_STATUS).payload)
        assertEquals(WppStatus.WIFI_NETWORK_UNAVAILABLE, connectionStatus.status.number)
        assertFalse(connectionStatus.hasErrorMessage())
        awaitEnd()
    }

    @Test(timeout = 10_000)
    fun badPortIsReportedAsAConnectionStatus() {
        start(Phone { throw AssertionError("must not try to join") })
        hu.send(WppMessageId.START_REQUEST, startRequest(port = 70_000))
        // a WifiConnectionStatus, not a WifiStartResponse
        assertEquals(WppStatus.INVALID_HOST, status(hu.expect(WppMessageId.CONNECTION_STATUS)))
        awaitEnd()
        assertEquals(WppStatus.INVALID_HOST, (failure as WirelessBootstrapException).status)
    }

    @Test(timeout = 10_000)
    fun portZeroIsNotRefusedBelowWpp45() {
        val phone = Phone { JoinResult.Joined() }
        start(phone)
        hu.send(WppMessageId.START_REQUEST, startRequest(port = 0))
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        assertEquals("10.0.0.1" to 0, phone.ready.poll(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000)
    fun noConnectionStatusForWpp45HeadUnits() {
        val phone = Phone { JoinResult.Joined() }
        start(phone)
        hu.send(WppMessageId.VERSION_REQUEST to WifiVersionRequest.newBuilder().setMajor(4).setMinor(5).build(),
            WppMessageId.START_REQUEST to startRequest())
        hu.expect(WppMessageId.VERSION_RESPONSE)
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        hu.expect(WppMessageId.START_RESPONSE)
        assertNotNull(phone.ready.poll(2, TimeUnit.SECONDS))
        val next = LinkedBlockingQueue<Result<WppFrame>>()
        thread(name = "hu-reader", isDaemon = true) { next.put(runCatching { hu.read() }) }
        assertNull("no WifiConnectionStatus for WPP 4.5+", next.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test(timeout = 10_000)
    fun quotesAroundCredentialsAreStrippedBelowWpp45() {
        val phone = Phone { JoinResult.Joined() }
        start(phone)
        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse().toBuilder().setSsid("\"My Car\"").setPassword("\"pass\"word\"").build())
        val credentials = phone.credentials.poll(2, TimeUnit.SECONDS)!!
        assertEquals("My Car", credentials.ssid)
        assertEquals("pass\"word", credentials.password)
    }

    @Test(timeout = 10_000)
    fun quotesAreKeptFromWpp45HeadUnits() {
        val phone = Phone { JoinResult.Joined() }
        start(phone)
        hu.send(WppMessageId.VERSION_REQUEST to WifiVersionRequest.newBuilder().setMajor(5).setMinor(0).build(),
            WppMessageId.START_REQUEST to startRequest())
        hu.expect(WppMessageId.VERSION_RESPONSE)
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse().toBuilder().setSsid("\"My Car\"").build())
        assertEquals("\"My Car\"", phone.credentials.poll(2, TimeUnit.SECONDS)!!.ssid)
    }

    @Test
    fun unquoteRemovesOnePairOnly() {
        assertEquals("a", WirelessBootstrap.unquoted("\"a\""))
        assertEquals("\"a\"", WirelessBootstrap.unquoted("\"\"a\"\""))
        assertEquals("", WirelessBootstrap.unquoted("\"\""))
        assertEquals("\"", WirelessBootstrap.unquoted("\""))
        assertEquals("\"a", WirelessBootstrap.unquoted("\"a"))
    }

    @Test(timeout = 10_000)
    fun unsupportedSecurityIsRefusedWithoutJoining() {
        val phone = Phone { throw AssertionError("must not try to join") }
        start(phone)

        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse(mode = WppSecurityMode.WPA2_ENTERPRISE))
        assertEquals(WppStatus.WIFI_INCORRECT_CREDENTIALS, status(hu.expect(WppMessageId.CONNECTION_STATUS)))
        awaitEnd()
        assertTrue(failure is WirelessBootstrapException)
    }

    @Test(timeout = 10_000)
    fun silentHeadUnitTimesOut() {
        start(Phone { JoinResult.Joined() }, WirelessBootstrap.Options(firstMessageTimeoutMs = 300, keepaliveIntervalMs = 0, frameGapMs = 0))
        awaitEnd()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("timed out after 300 ms waiting for the head unit's first message"))
        assertTrue((failure as WirelessBootstrapException).whileNegotiating)
    }

    @Test(timeout = 10_000)
    fun theFirstMessageEndsTheFirstMessageTimeout() {
        start(Phone { JoinResult.Joined() }, WirelessBootstrap.Options(
            firstMessageTimeoutMs = 300, messageTimeoutMs = 1_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 0))
        hu.send(WppMessageId.VERSION_REQUEST, versionRequest())
        hu.expect(WppMessageId.VERSION_RESPONSE)
        Thread.sleep(500) // past the first-message timeout, within the message timeout
        assertEquals(1L, finished.count)
        awaitEnd()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("timed out after 1000 ms waiting for a WifiStartRequest"))
    }

    @Test
    fun adaptersAreRecognisedByTheirBluetoothName() {
        assertTrue(WirelessBootstrap.isDongle("Intercooler-1234"))
        assertTrue(WirelessBootstrap.isDongle("AndroidAuto-5f2a"))
        assertFalse(WirelessBootstrap.isDongle("androidauto-5f2a"))
        assertFalse(WirelessBootstrap.isDongle("MY VW"))
        assertFalse(WirelessBootstrap.isDongle(null))
    }

    @Test(timeout = 10_000)
    fun infoRequestIsRetried() {
        val phone = Phone { JoinResult.Joined() }
        start(phone, WirelessBootstrap.Options(messageTimeoutMs = 3_000, infoRetryMs = 200, keepaliveIntervalMs = 0, frameGapMs = 0))

        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.expect(WppMessageId.INFO_REQUEST) // first one "lost"
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        hu.expect(WppMessageId.START_RESPONSE)
        assertNotNull(phone.ready.poll(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000)
    fun unansweredInfoRequestsEndTheLinkForARedial() {
        start(Phone { throw AssertionError("must not try to join") },
            WirelessBootstrap.Options(messageTimeoutMs = 5_000, infoRetryMs = 200, infoAttempts = 3, keepaliveIntervalMs = 0, frameGapMs = 0))

        hu.send(WppMessageId.START_REQUEST, startRequest())
        val startedAt = System.nanoTime()
        repeat(3) { hu.expect(WppMessageId.INFO_REQUEST) }
        awaitEnd()
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        val e = failure as WirelessBootstrapException
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("no WifiInfoResponse after 3 requests"))
        // not the 5 s message timeout: 3 requests 200 ms apart
        assertTrue("gave up after $tookMs ms", tookMs < 2_000)
        assertTrue("the caller redials", e.whileNegotiating)
    }

    @Test(timeout = 10_000)
    fun missingHeadUnitAddressFallsBackToTheGateway() {
        val phone = Phone { JoinResult.Joined(localAddress = "192.168.49.20", gateway = "192.168.49.1") }
        start(phone)

        hu.send(WppMessageId.START_REQUEST, startRequest(ip = ""))
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        assertEquals("192.168.49.1" to 5288, phone.ready.poll(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000)
    fun headUnitHangingUpEarlyFails() {
        val phone = Phone { JoinResult.Joined() }
        start(phone)
        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.hangUp()
        awaitEnd()
        assertTrue((failure as WirelessBootstrapException).whileNegotiating)
        assertNull(phone.ready.poll())
    }

    @Test(timeout = 10_000)
    fun headUnitHangingUpWithoutAWordIsACarNotReady() {
        // The Mach-E right after the Bluetooth link comes up (#74): accepts RFCOMM, says nothing, hangs up.
        start(Phone { JoinResult.Joined() })
        hu.hangUp()
        awaitEnd()
        val e = failure as WirelessBootstrapException
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("before sending its WifiStartRequest"))
        assertTrue(e.whileNegotiating)
    }

    @Test(timeout = 10_000)
    fun closeCancelsAWaitingBootstrap() {
        val b = start(Phone { JoinResult.Joined() })
        Thread.sleep(100)
        b.close()
        awaitEnd()
        assertTrue(failure is WirelessBootstrapException)
        assertFalse("cancelling isn't retried", (failure as WirelessBootstrapException).whileNegotiating)
    }

    @Test(timeout = 10_000)
    fun versionResponseCarriesPhoneInfo() {
        start(Phone { JoinResult.Joined() }, WirelessBootstrap.Options(
            messageTimeoutMs = 3_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 0,
            instanceId = "phone-id-1", deviceName = "Pixel",
        ))

        hu.send(WppMessageId.VERSION_REQUEST, versionRequest())
        val response = WifiVersionResponse.parseFrom(hu.expect(WppMessageId.VERSION_RESPONSE).payload)
        assertTrue("no phone_info", response.hasDeviceInfo())
        assertEquals("phone-id-1", response.deviceInfo.deviceId)
        assertEquals("Pixel", response.deviceInfo.deviceName)
        assertFalse("bluetooth_address is not sent", response.deviceInfo.hasBluetoothAddress())
    }

    @Test(timeout = 10_000)
    fun noPhoneInfoWithoutInstanceId() {
        start(Phone { JoinResult.Joined() }, WirelessBootstrap.Options(
            messageTimeoutMs = 3_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 0, deviceName = "Pixel",
        ))

        hu.send(WppMessageId.VERSION_REQUEST, versionRequest())
        assertFalse(WifiVersionResponse.parseFrom(hu.expect(WppMessageId.VERSION_RESPONSE).payload).hasDeviceInfo())
    }

    @Test(timeout = 10_000)
    fun phonePingsAfterTheVersionExchange() {
        start(Phone { JoinResult.Joined() }, WirelessBootstrap.Options(
            messageTimeoutMs = 3_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 100,
        ))

        // Google's receiver says nothing more and closes the link 10 s later unless the phone pings
        hu.send(WppMessageId.VERSION_REQUEST, versionRequest())
        hu.expect(WppMessageId.VERSION_RESPONSE)
        val answeredAt = System.nanoTime()
        repeat(3) {
            val frame = hu.read()
            assertEquals("expected WifiPingRequest, got $frame", WppMessageId.PING_REQUEST, frame.id)
            assertTrue(WifiPingRequest.parseFrom(frame.payload).timestamp > 0)
        }
        val tookMs = (System.nanoTime() - answeredAt) / 1_000_000
        assertTrue("3 pings took $tookMs ms", tookMs < 1_000)
    }

    @Test(timeout = 10_000)
    fun pingsContinueThroughTheWholeFlow() {
        val phone = Phone { JoinResult.Joined() }
        start(phone, WirelessBootstrap.Options(
            messageTimeoutMs = 3_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 100,
        ))

        // the head unit starts by itself a moment after the version exchange; the phone pings meanwhile
        hu.send(WppMessageId.VERSION_REQUEST, versionRequest())
        hu.expect(WppMessageId.VERSION_RESPONSE)
        hu.expect(WppMessageId.PING_REQUEST)
        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        assertEquals(WppStatus.SUCCESS, WifiStartResponse.parseFrom(hu.expect(WppMessageId.START_RESPONSE).payload).status.number)
        assertEquals(WppStatus.SUCCESS, status(hu.expect(WppMessageId.CONNECTION_STATUS)))
        assertEquals("10.0.0.1" to 5288, phone.ready.poll(2, TimeUnit.SECONDS))

        // and on while the link is held for the session
        repeat(2) { hu.expect(WppMessageId.PING_REQUEST) }
        hu.hangUp()
        awaitEnd()
        assertNull(failure)
    }

    @Test(timeout = 10_000)
    fun noPhonePingsWithoutVersionRequest() {
        start(Phone { JoinResult.Joined() }, WirelessBootstrap.Options(
            messageTimeoutMs = 3_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 100,
        ))

        hu.send(WppMessageId.START_REQUEST, startRequest(ip = "192.168.4.1"))
        assertEquals(WppMessageId.INFO_REQUEST, hu.read().id)
        // the dongle aborts on anything else; the info response is withheld, so nothing else is due
        val next = LinkedBlockingQueue<Result<WppFrame>>()
        thread(name = "hu-reader", isDaemon = true) { next.put(runCatching { hu.read() }) }
        assertNull("the phone sent something unprompted", next.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test(timeout = 10_000)
    fun repeatedVersionRequestKeepsOnePingStream() {
        start(Phone { JoinResult.Joined() }, WirelessBootstrap.Options(
            messageTimeoutMs = 3_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 500,
        ))

        hu.send(WppMessageId.VERSION_REQUEST to versionRequest(), WppMessageId.VERSION_REQUEST to versionRequest())
        hu.expect(WppMessageId.VERSION_RESPONSE)
        hu.expect(WppMessageId.VERSION_RESPONSE)
        val first = WifiPingRequest.parseFrom(hu.expect(WppMessageId.PING_REQUEST).payload).timestamp
        val second = WifiPingRequest.parseFrom(hu.expect(WppMessageId.PING_REQUEST).payload).timestamp
        assertTrue("pings ${second - first} ms apart", second - first >= 250)
    }

    @Test(timeout = 10_000)
    fun noSecondPingStreamWhileJoining() {
        val phone = Phone { Thread.sleep(300); JoinResult.Joined() }
        start(phone, WirelessBootstrap.Options(
            messageTimeoutMs = 3_000, keepaliveIntervalMs = 50, frameGapMs = 0, pingIntervalMs = 10_000,
        ))

        hu.send(WppMessageId.VERSION_REQUEST to versionRequest(), WppMessageId.START_REQUEST to startRequest())
        assertEquals(WppMessageId.VERSION_RESPONSE, hu.read().id)
        assertEquals(WppMessageId.INFO_REQUEST, hu.read().id)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        // the join keepalive would ping every 50 ms here; the link pinger's first ping is not due yet
        assertEquals(WppMessageId.START_RESPONSE, hu.read().id)
        assertEquals(WppMessageId.CONNECTION_STATUS, hu.read().id)
    }

    @Test(timeout = 10_000)
    fun startResponseStatusIsEncoded() {
        // Google's receiver reads a WifiStartResponse without status as a failure
        start(Phone { JoinResult.Joined(localAddress = "10.0.0.23") })

        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        val response = WifiStartResponse.parseFrom(hu.expect(WppMessageId.START_RESPONSE).payload)
        assertTrue(response.hasStatus())
        assertEquals(Status.STATUS_SUCCESS, response.status)
    }

    @Test(timeout = 10_000)
    fun versionRequestWithoutMajorIsAnsweredWithZero() {
        start(Phone { JoinResult.Joined() })
        hu.send(WppMessageId.VERSION_REQUEST, WifiVersionRequest.getDefaultInstance())
        val response = WifiVersionResponse.parseFrom(hu.expect(WppMessageId.VERSION_RESPONSE).payload)
        assertTrue(response.hasMajor())
        assertEquals(0, response.major)
        assertEquals(0, response.minor)
        assertEquals(WppStatus.SUCCESS, response.status)
    }

    @Test(timeout = 10_000)
    fun incompatibleVersionIsRefusedButTheHeadUnitMayStillStart() {
        val phone = Phone { JoinResult.Joined() }
        start(phone)
        hu.send(WppMessageId.VERSION_REQUEST, WifiVersionRequest.newBuilder().setMajor(3).setMinor(0).build())
        val response = WifiVersionResponse.parseFrom(hu.expect(WppMessageId.VERSION_RESPONSE).payload)
        assertEquals(WppStatus.NO_COMPATIBLE_VERSION, response.status)
        assertEquals(3, response.major)

        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        hu.send(WppMessageId.INFO_RESPONSE, infoResponse())
        hu.expect(WppMessageId.START_RESPONSE)
        assertNotNull(phone.ready.poll(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000)
    fun headUnitChannelsThePhoneCannotUseAreRefused() {
        start(Phone { JoinResult.Joined() }, WirelessBootstrap.Options(
            messageTimeoutMs = 3_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 0,
            phoneWifiFrequencies = { setOf(2412, 2437, 2462) },
        ))
        hu.send(WppMessageId.VERSION_REQUEST, WifiVersionRequest.newBuilder().setMajor(4).setMinor(2)
            .addSupportedWifiChannels(5180).addSupportedWifiChannels(5745).build())
        val response = WifiVersionResponse.parseFrom(hu.expect(WppMessageId.VERSION_RESPONSE).payload)
        assertEquals(WppStatus.NO_SUPPORTED_WIFI_CHANNELS, response.status)
    }

    @Test
    fun versionStatusFollowsAndroidAuto() {
        val any = listOf(2437)
        val phone = setOf(2437)
        assertEquals(WppStatus.SUCCESS, WirelessBootstrap.versionStatus(0, 0, emptyList(), phone))
        for (major in listOf(1, 4, 5, 6)) assertEquals("WPP $major", WppStatus.SUCCESS, WirelessBootstrap.versionStatus(major, 0, any, phone))
        for (major in listOf(2, 3, 7, 10, -1)) {
            assertEquals("WPP $major", WppStatus.NO_COMPATIBLE_VERSION, WirelessBootstrap.versionStatus(major, 0, any, phone))
        }
        // the channel check only applies below 4.5
        assertEquals(WppStatus.NO_SUPPORTED_WIFI_CHANNELS, WirelessBootstrap.versionStatus(4, 4, emptyList(), phone))
        assertEquals(WppStatus.SUCCESS, WirelessBootstrap.versionStatus(4, 5, emptyList(), phone))
        assertEquals(WppStatus.NO_SUPPORTED_WIFI_CHANNELS, WirelessBootstrap.versionStatus(1, 7, listOf(5180), phone))
    }

    @Test
    fun channelCheckFollowsAndroidAuto() {
        val phone = setOf(2412, 5180)
        assertTrue(WirelessBootstrap.channelsUsable(listOf(5745, 5180), phone))
        assertFalse(WirelessBootstrap.channelsUsable(listOf(5745), phone))
        assertFalse("no channels listed", WirelessBootstrap.channelsUsable(emptyList(), phone))
        assertTrue("channel numbers are not checked", WirelessBootstrap.channelsUsable(listOf(36), phone))
        assertTrue("5000 is not checked", WirelessBootstrap.channelsUsable(listOf(5000), phone))
        assertTrue("unknown phone frequencies skip the check", WirelessBootstrap.channelsUsable(emptyList(), null))
    }

    @Test
    fun securityModesMapToWhatThePhoneCanConfigure() {
        assertEquals(WifiSecurity.WPA2_PERSONAL, WppSecurityMode.toSecurity(8, "pw"))
        assertEquals(WifiSecurity.WPA3_PERSONAL, WppSecurityMode.toSecurity(32, "pw"))
        assertEquals(WifiSecurity.WPA2_WPA3_PERSONAL, WppSecurityMode.toSecurity(40, "pw"))
        assertEquals(WifiSecurity.OPEN, WppSecurityMode.toSecurity(1, ""))
        // aasdk's in-session numbering and an unset field
        assertEquals(WifiSecurity.WPA2_PERSONAL, WppSecurityMode.toSecurity(5, "pw"))
        assertEquals(WifiSecurity.WPA2_PERSONAL, WppSecurityMode.toSecurity(0, "pw"))
        assertEquals(WifiSecurity.UNSUPPORTED, WppSecurityMode.toSecurity(2, "pw"))
        assertEquals(WifiSecurity.UNSUPPORTED, WppSecurityMode.toSecurity(24, "pw"))
    }

    @Test
    fun framingMatchesTheWireFormat() {
        val bytes = encodeWppFrame(WppMessageId.START_RESPONSE, byteArrayOf(0x18, 0x00))
        assertArrayEquals(byteArrayOf(0, 2, 0, 7, 0x18, 0), bytes)
        val frame = readWppFrame(bytes.inputStream())!!
        assertEquals(WppMessageId.START_RESPONSE, frame.id)
        assertArrayEquals(byteArrayOf(0x18, 0), frame.payload)
        assertNull(readWppFrame(ByteArray(0).inputStream()))
    }

    @Test
    fun payloadsOver1024BytesAreALinkError() {
        assertEquals(WPP_MAX_PAYLOAD, readWppFrame(encodeWppFrame(WppMessageId.INFO_RESPONSE, ByteArray(1024)).inputStream())!!.payload.size)
        val tooLong = encodeWppFrame(WppMessageId.INFO_RESPONSE, ByteArray(1025))
        val e = runCatching { readWppFrame(tooLong.inputStream()) }.exceptionOrNull()
        assertTrue("expected an IOException, got $e", e is IOException)
    }

    @Test(timeout = 10_000)
    fun oversizedFrameEndsTheBootstrapLikeABrokenLink() {
        start(Phone { throw AssertionError("must not try to join") })
        hu.send(WppMessageId.START_REQUEST, startRequest())
        hu.expect(WppMessageId.INFO_REQUEST)
        huToPhone.output.write(encodeWppFrame(WppMessageId.INFO_RESPONSE, ByteArray(2000)))
        awaitEnd()
        // a broken link while negotiating: the caller redials
        assertTrue((failure as WirelessBootstrapException).whileNegotiating)
    }
}

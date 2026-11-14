package net.prototux.aare.aap.wireless

import aap_protobuf.aaw.WifiConnectionRejectionOuterClass.WifiConnectionRejection
import aap_protobuf.aaw.WifiInfoResponseOuterClass.WifiInfoResponse
import aap_protobuf.aaw.WifiSetupInfoOuterClass.WifiAccessPointInfo
import aap_protobuf.aaw.WifiSetupInfoOuterClass.WifiSetupInfo
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import aap_protobuf.aaw.WifiStartResponseOuterClass.WifiStartResponse
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiProjectionProtocolInfo
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WppCarInfo
import aap_protobuf.aaw.WifiVersionResponseOuterClass.WifiVersionResponse
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import net.prototux.aare.aap.AapLog
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** WifiSetupInfo, wpp_info and the exchange over TCP, against a scripted head unit over in-memory pipes. */
class WppOverTcpTest {

    private class Link {
        private val pipe = Pipe.open()
        val input: InputStream = Channels.newInputStream(pipe.source())
        val output: OutputStream = Channels.newOutputStream(pipe.sink())
    }

    /** [takeSetup]: what onSetupInfo answers. */
    private class Phone(@Volatile var takeSetup: Boolean = true) : WirelessBootstrap.Listener {
        val joins = LinkedBlockingQueue<WifiCredentials>()
        val ready = LinkedBlockingQueue<Pair<String, Int>>()
        val endpoints = LinkedBlockingQueue<Pair<WppEndpoint, WppVersion>>()
        val setups = LinkedBlockingQueue<WppSetup>()
        val rejections = LinkedBlockingQueue<Int>()
        override fun connectWifi(credentials: WifiCredentials): JoinResult {
            joins.put(credentials)
            return JoinResult.Joined(localAddress = "10.0.0.23", gateway = "10.0.0.1")
        }
        override fun onReady(ip: String, port: Int) = ready.put(ip to port)
        override fun onWppEndpoint(endpoint: WppEndpoint, version: WppVersion) = endpoints.put(endpoint to version)
        override fun onSetupInfo(setup: WppSetup): Boolean {
            setups.put(setup)
            return takeSetup
        }
        override fun onRejected(reason: Int) = rejections.put(reason)
    }

    private val log = object : AapLog {
        override fun i(msg: String) = println("I/wpp: $msg")
        override fun w(msg: String, t: Throwable?) = println("W/wpp: $msg ${t ?: ""}")
        override fun e(msg: String, t: Throwable?) = println("E/wpp: $msg ${t ?: ""}")
    }

    private val huToPhone = Link()
    private val phoneToHu = Link()
    private val phone = Phone()
    private var bootstrap: WirelessBootstrap? = null
    @Volatile private var failure: Throwable? = null
    private val finished = CountDownLatch(1)

    private val quiet = WirelessBootstrap.Options(messageTimeoutMs = 3_000, keepaliveIntervalMs = 0, frameGapMs = 0, pingIntervalMs = 0)
    private val token = byteArrayOf(1, 2, 3, 4, 5)
    private val overTcp = quiet.copy(tcp = WirelessBootstrap.TcpLink(JoinResult.Joined("10.0.0.23", "10.0.0.1"), token))

    private fun start(options: WirelessBootstrap.Options) {
        val b = WirelessBootstrap(huToPhone.input, phoneToHu.output, phone, log, options)
        bootstrap = b
        thread(name = "phone") {
            try { b.run() } catch (t: Throwable) { failure = t } finally { finished.countDown() }
        }
    }

    private fun send(id: Int, msg: MessageLite) = writeWppFrame(huToPhone.output, id, msg.toByteArray())

    /** The phone's next frame; null once it closed the link. */
    private fun read(): WppFrame? = readWppFrame(phoneToHu.input)

    private fun expect(id: Int): WppFrame {
        val frame = read() ?: throw AssertionError("phone closed the link, expected ${WppMessageId.name(id)}")
        assertEquals("expected ${WppMessageId.name(id)}, got $frame", id, frame.id)
        return frame
    }

    private fun awaitEnd() = assertTrue("bootstrap did not finish", finished.await(5, TimeUnit.SECONDS))

    @After
    fun tearDown() {
        bootstrap?.close()
        runCatching { huToPhone.output.close() }
    }

    private fun setupInfo(
        major: Int = 4,
        minor: Int = 5,
        port: Int? = 7000,
        ip: String = "192.168.1.1",
        channels: List<Int> = listOf(5180),
    ) = WifiSetupInfo.newBuilder().setMajor(major).setMinor(minor).setSetupToken(ByteString.copyFrom(token))
        .setWppInfo(WifiProjectionProtocolInfo.newBuilder().setIpAddress(ip).apply { if (port != null) setPort(port) })
        .setAccessPoint(WifiAccessPointInfo.newBuilder().setSsid("\"HU\"").setPassword("secret-passphrase")
            .setBssid("aa:bb:cc:dd:ee:ff").setSecurityMode(WppSecurityMode.WPA2_PERSONAL).addAllSupportedWifiChannels(channels))
        .build()

    private fun versionRequest(major: Int, minor: Int, make: String = "Ford") =
        WifiVersionRequest.newBuilder().setMajor(major).setMinor(minor)
            .setCarInfo(WppCarInfo.newBuilder().setMake(make).setHeadUnitModel("HU"))
            .setWppInfo(WifiProjectionProtocolInfo.newBuilder().setIpAddress("0.0.0.0").setPort(1234)).build()

    // ---- over RFCOMM ----

    @Test(timeout = 10_000)
    fun setupInfoEndsTheRfcommExchangeWithoutJoining() {
        start(quiet.copy(wppOverTcp = true))
        send(WppMessageId.SETUP_INFO, setupInfo())
        val setup = phone.setups.poll(2, TimeUnit.SECONDS)!!
        assertEquals(WppEndpoint("192.168.1.1", 7000), setup.endpoint)
        assertArrayEquals(token, setup.setupToken)
        // a WPP 4.5 head unit's SSID keeps its quotes
        assertEquals("\"HU\"", setup.credentials.ssid)
        assertEquals("AA:BB:CC:DD:EE:FF", setup.credentials.bssid)
        awaitEnd()
        assertNull(failure)
        assertEquals(WirelessBootstrap.State.DONE, bootstrap!!.state)
        assertNull("no WifiInfoRequest, the link is closed", runCatching { read() }.getOrNull())
        assertTrue(phone.joins.isEmpty())
    }

    @Test(timeout = 10_000)
    fun setupInfoIsIgnoredByAPhoneWithoutWppOverTcp() {
        start(quiet)
        send(WppMessageId.SETUP_INFO, setupInfo())
        send(WppMessageId.START_REQUEST, WifiStartRequest.newBuilder().setIpAddress("10.0.0.1").setPort(5288).build())
        expect(WppMessageId.INFO_REQUEST)
        assertTrue(phone.setups.isEmpty())
    }

    /** Sends [info] and checks the phone ignored it: the exchange goes on over RFCOMM. */
    private fun assertIgnored(info: WifiSetupInfo) {
        send(WppMessageId.SETUP_INFO, info)
        send(WppMessageId.START_REQUEST, WifiStartRequest.newBuilder().setIpAddress("10.0.0.1").setPort(5288).build())
        expect(WppMessageId.INFO_REQUEST)
        assertNull(failure)
        assertEquals(1L, finished.count)
    }

    @Test(timeout = 10_000)
    fun setupInfoBelowWpp45IsIgnored() {
        start(quiet.copy(wppOverTcp = true))
        assertIgnored(setupInfo(minor = 2))
        assertTrue(phone.setups.isEmpty())
    }

    @Test(timeout = 10_000)
    fun setupInfoWithoutPortIsIgnored() {
        start(quiet.copy(wppOverTcp = true))
        assertIgnored(setupInfo(port = null))
        assertTrue(phone.setups.isEmpty())
    }

    @Test(timeout = 10_000)
    fun setupInfoWithAHostNameIsIgnored() {
        start(quiet.copy(wppOverTcp = true))
        assertIgnored(setupInfo(ip = "hu.local"))
        assertTrue(phone.setups.isEmpty())
    }

    @Test(timeout = 10_000)
    fun setupInfoWithNoUsableChannelIsIgnored() {
        start(quiet.copy(wppOverTcp = true, phoneWifiFrequencies = { setOf(2412) }))
        assertIgnored(setupInfo(channels = listOf(5180)))
        assertTrue(phone.setups.isEmpty())
    }

    @Test(timeout = 10_000)
    fun aMalformedSetupInfoIsIgnored() {
        start(quiet.copy(wppOverTcp = true))
        writeWppFrame(huToPhone.output, WppMessageId.SETUP_INFO, byteArrayOf(0x0a, 0x7f))
        send(WppMessageId.START_REQUEST, WifiStartRequest.newBuilder().setIpAddress("10.0.0.1").setPort(5288).build())
        expect(WppMessageId.INFO_REQUEST)
        assertNull(failure)
    }

    @Test(timeout = 10_000)
    fun aDeclinedSetupInfoCarriesOnOverRfcomm() {
        phone.takeSetup = false
        start(quiet.copy(wppOverTcp = true))
        assertIgnored(setupInfo())
        assertEquals(WppVersion(4, 5), phone.setups.poll(2, TimeUnit.SECONDS)!!.version)
        // its version was not taken either: without a version the quotes of the SSID are still dropped
        send(WppMessageId.INFO_RESPONSE, WifiInfoResponse.newBuilder().setSsid("\"HU\"").setPassword("secret-passphrase")
            .setSecurityMode(WppSecurityMode.WPA2_PERSONAL).build())
        expect(WppMessageId.START_RESPONSE)
        assertEquals("HU", phone.joins.poll(2, TimeUnit.SECONDS)!!.ssid)
    }

    @Test(timeout = 10_000)
    fun wppInfoIsReportedAndTheExchangeGoesOn() {
        start(quiet.copy(wppOverTcp = true))
        send(WppMessageId.VERSION_REQUEST, versionRequest(4, 2))
        expect(WppMessageId.VERSION_RESPONSE)
        assertEquals(WppEndpoint("0.0.0.0", 1234) to WppVersion(4, 2), phone.endpoints.poll(2, TimeUnit.SECONDS))
        send(WppMessageId.START_REQUEST, WifiStartRequest.newBuilder().setIpAddress("10.0.0.1").setPort(5288).build())
        expect(WppMessageId.INFO_REQUEST)
    }

    @Test(timeout = 10_000)
    fun wppInfoOfA41HeadUnitOutsideTheAllowlistIsNotKept() {
        start(quiet.copy(wppOverTcp = true))
        send(WppMessageId.VERSION_REQUEST, versionRequest(4, 1, make = "Ford"))
        expect(WppMessageId.VERSION_RESPONSE)
        send(WppMessageId.START_REQUEST, WifiStartRequest.newBuilder().setIpAddress("10.0.0.1").setPort(5288).build())
        expect(WppMessageId.INFO_REQUEST)
        assertTrue(phone.endpoints.isEmpty())
    }

    @Test(timeout = 10_000)
    fun wppInfoIsOnlyLoggedWithoutWppOverTcp() {
        start(quiet)
        send(WppMessageId.VERSION_REQUEST, versionRequest(4, 2))
        val response = WifiVersionResponse.parseFrom(expect(WppMessageId.VERSION_RESPONSE).payload)
        assertFalse(response.hasSetupToken())
        assertTrue(phone.endpoints.isEmpty())
    }

    @Test
    fun wppInfoIsKeptAsAndroidAutoDoes() {
        assertFalse(WirelessBootstrap.keepsWppInfo(4, 0, "Google", "HU", adapter = false))
        assertFalse(WirelessBootstrap.keepsWppInfo(0, 0, "Google", "HU", adapter = false))
        assertTrue(WirelessBootstrap.keepsWppInfo(4, 1, "GMC", "HU", adapter = false))
        assertTrue(WirelessBootstrap.keepsWppInfo(4, 1, "Chevrolet Motor Division", "HU", adapter = false))
        assertTrue(WirelessBootstrap.keepsWppInfo(4, 1, "bmw", "HU", adapter = false))
        assertFalse(WirelessBootstrap.keepsWppInfo(4, 1, "Ford", "HU", adapter = false))
        assertTrue(WirelessBootstrap.keepsWppInfo(4, 2, "Ford", "HU", adapter = false))
        assertTrue(WirelessBootstrap.keepsWppInfo(5, 0, "Ford", "HU", adapter = false))
        // adapters and "[AAR]" head units whatever their version
        assertTrue(WirelessBootstrap.keepsWppInfo(1, 0, "Ford", "HU", adapter = true))
        assertTrue(WirelessBootstrap.keepsWppInfo(1, 0, "Volvo", "connect_l [AAR]", adapter = false))
    }

    // ---- over TCP ----

    @Test(timeout = 10_000)
    fun overTcpTheTokenGoesBackAndNoInfoIsRequested() {
        start(overTcp)
        send(WppMessageId.VERSION_REQUEST, WifiVersionRequest.newBuilder().setMajor(4).setMinor(5).build())
        val version = WifiVersionResponse.parseFrom(expect(WppMessageId.VERSION_RESPONSE).payload)
        assertArrayEquals(token, version.setupToken.toByteArray())
        send(WppMessageId.START_REQUEST, WifiStartRequest.newBuilder().setIpAddress("192.168.1.1").setPort(5288).build())
        // straight to the start response: no WifiInfoRequest
        val start = WifiStartResponse.parseFrom(expect(WppMessageId.START_RESPONSE).payload)
        assertEquals(WppStatus.SUCCESS, start.status.number)
        assertEquals("192.168.1.1" to 5288, phone.ready.poll(2, TimeUnit.SECONDS))
        assertTrue("nothing joined over TCP", phone.joins.isEmpty())
    }

    @Test(timeout = 10_000)
    fun theTokenGoesOutOnce() {
        start(overTcp)
        send(WppMessageId.VERSION_REQUEST, WifiVersionRequest.newBuilder().setMajor(4).setMinor(5).build())
        assertTrue(WifiVersionResponse.parseFrom(expect(WppMessageId.VERSION_RESPONSE).payload).hasSetupToken())
        send(WppMessageId.VERSION_REQUEST, WifiVersionRequest.newBuilder().setMajor(4).setMinor(5).build())
        assertFalse(WifiVersionResponse.parseFrom(expect(WppMessageId.VERSION_RESPONSE).payload).hasSetupToken())
    }

    @Test(timeout = 10_000)
    fun noTokenForAHeadUnitBelowWpp45() {
        start(overTcp)
        send(WppMessageId.VERSION_REQUEST, WifiVersionRequest.newBuilder().setMajor(4).setMinor(2).build())
        assertFalse(WifiVersionResponse.parseFrom(expect(WppMessageId.VERSION_RESPONSE).payload).hasSetupToken())
    }

    @Test(timeout = 10_000)
    fun aRejectionOverTcpIsReported() {
        start(overTcp)
        send(WppMessageId.VERSION_REQUEST, WifiVersionRequest.newBuilder().setMajor(4).setMinor(5).build())
        expect(WppMessageId.VERSION_RESPONSE)
        send(WppMessageId.CONNECTION_REJECTION,
            WifiConnectionRejection.newBuilder().setReason(WppRejectionReason.INVALID_SETUP_TOKEN).build())
        assertEquals(WppRejectionReason.INVALID_SETUP_TOKEN, phone.rejections.poll(2, TimeUnit.SECONDS))
        awaitEnd()
        assertTrue(failure is WirelessBootstrapException)
    }

    @Test(timeout = 10_000)
    fun aSetupInfoOverTcpIsIgnored() {
        start(overTcp.copy(wppOverTcp = true))
        send(WppMessageId.SETUP_INFO, setupInfo())
        send(WppMessageId.START_REQUEST, WifiStartRequest.newBuilder().setIpAddress("192.168.1.1").setPort(5288).build())
        expect(WppMessageId.START_RESPONSE)
        assertTrue(phone.setups.isEmpty())
    }

    @Test(timeout = 10_000)
    fun aLateInfoResponseOverTcpIsHarmless() {
        start(overTcp)
        send(WppMessageId.INFO_RESPONSE, WifiInfoResponse.newBuilder().setSsid("HU").setPassword("pw").setSecurityMode(8).build())
        send(WppMessageId.START_REQUEST, WifiStartRequest.newBuilder().setIpAddress("192.168.1.1").setPort(5288).build())
        expect(WppMessageId.START_RESPONSE)
        assertTrue(phone.joins.isEmpty())
    }
}

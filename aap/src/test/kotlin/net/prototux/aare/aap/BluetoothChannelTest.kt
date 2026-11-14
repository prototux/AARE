package net.prototux.aare.aap

import aap_protobuf.service.bluetooth.message.BluetoothPairingMethodOuterClass.BluetoothPairingMethod
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.channels.BluetoothChannel
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The Bluetooth service channel: the pairing method the phone asks for, and the authentication answer. */
class BluetoothChannelTest {
    private lateinit var hu: HeadUnitSimulator
    private var bluetooth: BluetoothChannel? = null
    private val running = CountDownLatch(1)
    private val authData = LinkedBlockingQueue<String>()

    private fun connect(minor: Int, methods: List<BluetoothPairingMethod>) {
        val server = ServerSocket(0)
        val huSocket = Socket("127.0.0.1", server.localPort)
        val phoneSocket = server.accept()
        server.close()
        hu = HeadUnitSimulator(SocketTransport(huSocket), protocolMinor = minor, bluetoothMethods = methods)
        val id = SelfSignedCertificate.generate("phone")
        AapSession(
            SocketTransport(phoneSocket),
            PhoneIdentity(id.certificate, id.keyPair.private),
            PhoneDescription(deviceName = "Test phone", label = "AARE", instanceId = "test-1"),
            callbacks = object : AapSession.Callbacks {
                override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> =
                    response.channelsList.filter { it.hasBluetoothService() }.map { s ->
                        BluetoothChannel(s, PHONE_ADDRESS, object : BluetoothChannel.Listener {
                            override fun onAuthenticationData(authData: String) { this@BluetoothChannelTest.authData.add(authData) }
                        }).also { bluetooth = it }
                    }
                override fun onRunning(session: AapSession) = running.countDown()
                override fun onClosed(session: AapSession, reason: String, error: Throwable?) = Unit
            },
        ).start()
        hu.start()
        assertTrue("session should run", running.await(10, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() { if (::hu.isInitialized) hu.stop() }

    @Test
    fun numericComparisonWinsWhenTheCarOffersBoth() {
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON, BluetoothChannel.pairingMethod(listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON, BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN)))
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON, BluetoothChannel.pairingMethod(listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN, BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON)))
    }

    @Test
    fun pinOnlyWithoutNumericComparison() {
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN, BluetoothChannel.pairingMethod(listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_OOB, BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN)))
    }

    @Test
    fun otherwiseTheCarsFirstMethodThenNumericComparison() {
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_OOB, BluetoothChannel.pairingMethod(listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_OOB, BluetoothPairingMethod.BLUETOOTH_PAIRING_PASSKEY_ENTRY)))
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_PASSKEY_ENTRY, BluetoothChannel.pairingMethod(listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_UNAVAILABLE, BluetoothPairingMethod.BLUETOOTH_PAIRING_PASSKEY_ENTRY)))
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON, BluetoothChannel.pairingMethod(listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_UNAVAILABLE)))
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON, BluetoothChannel.pairingMethod(emptyList()))
    }

    @Test
    fun pairingRequestCarriesTheChosenMethod() {
        connect(6, listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN, BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON))
        val req = hu.pairingRequests.poll(3, TimeUnit.SECONDS)!!
        assertEquals(PHONE_ADDRESS, req.phoneAddress)
        assertEquals(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON, req.pairingMethod)
    }

    @Test
    fun noAuthenticationResultBeforeTheCarSentItsData() {
        connect(6, listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON))
        assertFalse(bluetooth!!.sendAuthenticationResult(MessageStatus.STATUS_SUCCESS))
        assertNull(hu.authResults.poll(1, TimeUnit.SECONDS))
    }

    @Test
    fun authenticationDataReachesThePhoneAndTheResultGoesBack() {
        connect(5, listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON))
        hu.sendAuthenticationData("012345", BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON)
        assertEquals("012345", authData.poll(3, TimeUnit.SECONDS))
        assertTrue(bluetooth!!.sendAuthenticationResult(MessageStatus.STATUS_BLUETOOTH_AUTH_DATA_MISMATCH))
        assertEquals(MessageStatus.STATUS_BLUETOOTH_AUTH_DATA_MISMATCH, hu.authResults.poll(3, TimeUnit.SECONDS)!!.status)
        // One answer per authentication data.
        assertFalse(bluetooth!!.sendAuthenticationResult(MessageStatus.STATUS_SUCCESS))
    }

    /** The authentication result is sent only to head units on protocol 1.5 or later. */
    @Test
    fun noAuthenticationResultBeforeProtocolOneFive() {
        connect(4, listOf(BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN))
        hu.sendAuthenticationData("1234", BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN)
        assertEquals("1234", authData.poll(3, TimeUnit.SECONDS))
        assertFalse(bluetooth!!.sendAuthenticationResult(MessageStatus.STATUS_SUCCESS))
        assertNull(hu.authResults.poll(1, TimeUnit.SECONDS))
    }

    private companion object {
        const val PHONE_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}

package net.prototux.aare.transport.wireless

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telling a VPN's refusal to let AARE use the car's network from other socket failures. */
class WirelessConnectorTest {
    // What Network's socket factory throws when the bind is refused (the message of its ErrnoException cause).
    private val refused = "Binding socket to network 129 failed: EPERM (Operation not permitted)"

    @Test
    fun aRefusedBindIsTheVpn() {
        assertTrue(WirelessConnector.isNetworkBindRefused(SocketException(refused)))
    }

    @Test
    fun aRefusedBindIsFoundUnderAWrapper() {
        val wrapped = WirelessException("wireless quick first try failed", SocketException(refused), quickTry = true)
        assertTrue(WirelessConnector.isNetworkBindRefused(wrapped))
    }

    @Test
    fun otherBindFailuresAreNot() {
        // The network went away meanwhile: not the VPN.
        assertFalse(WirelessConnector.isNetworkBindRefused(SocketException("Binding socket to network 129 failed: ENONET (Machine is not on the network)")))
    }

    @Test
    fun connectFailuresAreNot() {
        assertFalse(WirelessConnector.isNetworkBindRefused(SocketTimeoutException("failed to connect to /192.168.1.101 (port 35907) from /192.168.1.102 (port 42932) after 10000ms")))
        assertFalse(WirelessConnector.isNetworkBindRefused(IOException("connect failed: EPERM (Operation not permitted)")))
        assertFalse(WirelessConnector.isNetworkBindRefused(IOException()))
    }
}

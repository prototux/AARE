package net.prototux.aare.aap.wireless

import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.PinStore
import net.prototux.aare.aap.PinningTrustManager
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS for WPP over TCP, set up on the socket opened to the head unit's
 * WPP endpoint: TLS 1.2 with the phone as the TLS server, presenting the same certificate as for
 * AAP, and a client certificate required from the head unit.
 *
 * Only head unit certificates that chain to Google's automotive CA are trusted; like the AAP
 * session ([net.prototux.aare.aap.Cryptor]), AARE pins the head unit's certificate in [pins] instead.
 */
object WppTls {

    /**
     * Runs the handshake on the connected [socket] (closed with the returned one) and returns the
     * TLS socket the WPP frames go through. [handshakeTimeoutMs] bounds the handshake only; reads
     * afterwards have no timeout, like the RFCOMM link's. Throws an IOException when the handshake
     * fails, e.g. because the head unit's certificate is not trusted.
     */
    fun serve(
        socket: Socket,
        identity: PhoneIdentity,
        /** The head unit certificates the user trusts; null accepts any (tests). */
        pins: PinStore?,
        /** Identifies the head unit in [pins]; required with [pins]. */
        peerKey: String?,
        trustOnFirstUse: Boolean,
        handshakeTimeoutMs: Int = 10_000,
    ): SSLSocket {
        val pinning = pins?.let { PinningTrustManager(requireNotNull(peerKey) { "peerKey is required with pins" }, it, trustOnFirstUse) }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setKeyEntry("wpp", identity.privateKey, CharArray(0), (listOf(identity.certificate) + identity.chain).toTypedArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf<TrustManager>(pinning ?: AcceptAll), SecureRandom())
        val host = socket.inetAddress?.hostAddress
        val tls = ctx.socketFactory.createSocket(socket, host, socket.port, true) as SSLSocket
        try {
            tls.useClientMode = false
            tls.needClientAuth = true
            // A TLS 1.2 context is asked for.
            val wanted = tls.supportedProtocols.filter { it == "TLSv1.2" }
            if (wanted.isNotEmpty()) tls.enabledProtocols = wanted.toTypedArray()
            tls.soTimeout = handshakeTimeoutMs
            tls.startHandshake()
            tls.soTimeout = 0
            // A first-use pin is only written once the head unit proved it holds the key.
            pinning?.commit()
            return tls
        } catch (e: Exception) {
            runCatching { tls.close() }
            throw e
        }
    }

    private object AcceptAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

package net.prototux.aare.transport

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import net.prototux.aare.DiagLog
import net.prototux.aare.aap.SocketTransport
import net.prototux.aare.aap.Transport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * The developer "head unit server": the phone listens and the head unit connects, the way
 * Android Auto's developer mode works with Google's DHU (here through
 * `adb forward tcp:5277 localabstract:aare_hu`), openauto or headunit-revived.
 *
 * The adb path is a unix socket in the abstract namespace, not a TCP port: other apps on the phone
 * can't reach a loopback port that way, and only adb (uid shell or root) is accepted. With [lan] set
 * the server also listens on TCP [PORT] on all interfaces, where the peer can't be identified (peers
 * with an address of this phone are refused, so local apps can't use it on any interface):
 * anyone else on the network who can connect gets the car screen and microphone.
 *
 * With [localhost] set the server listens on TCP [PORT] on loopback and *accepts* local peers, so an
 * Android-Auto-in-the-browser bridge running on this phone (webaa, paath — for cars like Tesla that
 * have no Android Auto, #131) can connect. Any app on the phone can then reach it, so it is opt-in.
 */
class TcpServer(
    private val lan: Boolean,
    private val localhost: Boolean = false,
    private val port: Int = PORT,
    private val onConnection: (Transport) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var local: LocalServerSocket? = null
    @Volatile private var localThread: Thread? = null
    @Volatile private var stopped = false

    fun start() {
        // Bind both before starting any thread, so a failure leaves nothing listening.
        val l = LocalServerSocket(LOCAL_NAME)
        // LAN binds all interfaces; localhost-only binds loopback. Both on → all interfaces (loopback included).
        val s = if (lan || localhost) runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(if (lan) "0.0.0.0" else "127.0.0.1"), port))
            }
        }.onFailure { runCatching { l.close() } }.getOrThrow() else null
        local = l
        server = s
        Thread({
            Log.i(TAG, "listening on @$LOCAL_NAME")
            while (!stopped) {
                try {
                    val socket = l.accept()
                    if (stopped) { runCatching { socket.close() }; break }
                    if (!allowed(socket)) continue
                    Log.i(TAG, "head unit connected through adb")
                    onConnection(LocalSocketTransport(socket))
                } catch (e: Exception) {
                    if (stopped) break
                    Log.w(TAG, "accept failed", e)
                    if (e is java.io.IOException) Thread.sleep(500) // e.g. out of fds: don't spin, don't die
                }
            }
        }, "aare-hu-local-server").apply { isDaemon = true; localThread = this; start() }
        if (s != null) Thread({
            Log.i(TAG, "listening on ${s.localSocketAddress}")
            while (!s.isClosed) {
                try {
                    val socket = s.accept()
                    if (stopped) { runCatching { socket.close() }; break }
                    // The wildcard bind includes loopback and this phone's own addresses, but adb now uses @aare_hu:
                    // a peer with one of those addresses is an app on this phone. Refused unless the user turned
                    // on localhost mode for an AA-in-the-browser bridge (webaa, paath).
                    if (!localhost && isLocalPeer(socket)) {
                        DiagLog.add("head unit server: refused a TCP connection from this phone")
                        runCatching { socket.close() }
                        continue
                    }
                    Log.i(TAG, "head unit connected from ${socket.remoteSocketAddress}")
                    onConnection(SocketTransport(socket))
                } catch (e: SocketException) {
                    break // closed
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed", e)
                }
            }
        }, "aare-tcp-server").apply { isDaemon = true; start() }
    }

    /** True for loopback, wildcard, this connection's own address or any local interface address; a failed lookup counts as local. */
    private fun isLocalPeer(socket: Socket): Boolean = try {
        val peer = socket.inetAddress
        peer.isLoopbackAddress || peer.isAnyLocalAddress || peer == socket.localAddress ||
            NetworkInterface.getByInetAddress(peer) != null
    } catch (e: Exception) {
        true
    }

    /** Only adb (uid shell, or root on userdebug builds and emulators) may use the unix socket. */
    private fun allowed(socket: LocalSocket): Boolean {
        val uid = runCatching { socket.peerCredentials.uid }.getOrDefault(-1)
        if (uid == SHELL_UID || uid == ROOT_UID) return true
        DiagLog.add("head unit server: refused a local connection from uid $uid")
        runCatching { socket.close() }
        return false
    }

    fun stop() {
        stopped = true
        runCatching { server?.close() }
        val l = local
        runCatching { l?.close() }
        // close() doesn't always wake a blocked accept(): connect once so the thread sees [stopped] and frees the name.
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(LOCAL_NAME)) } }
        // Wait for the accept thread, so a restart can bind the abstract name again.
        localThread?.join(1000)
        localThread = null
        server = null
        local = null
    }

    companion object {
        const val PORT = 5277
        /** Abstract unix socket name: `adb forward tcp:5277 localabstract:aare_hu`. */
        const val LOCAL_NAME = "aare_hu"
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
        private const val TAG = "AareTcpServer"
    }
}

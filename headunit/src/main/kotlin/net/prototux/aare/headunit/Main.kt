package net.prototux.aare.headunit

import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.SocketTransport
import net.prototux.aare.aap.cert.SelfSignedCertificate
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSlider
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val USAGE = """AARE desktop head unit: a car screen on your desktop for a phone running AARE.

  aare-headunit [options]

  --host H --port P      phone to connect to (default 127.0.0.1:5277; use `adb forward tcp:5277 localabstract:aare_hu`)
  --adb-target T         what the adb forward points at on the phone (default localabstract:aare_hu; use tcp:5277
                         for the official Android Auto app's head unit server)
  --res WxH              800x480, 1280x720 (default), 1920x1080
  --portrait             portrait screen, like a tall center display: offers VIDEO_720x1280 (default), or
                         VIDEO_1080x1920 with --res 1080x1920 (a landscape --res is turned upright), in a tall window
  --dpi N                screen density sent to the phone (default 160, or 240 at 1080p and 1080x1920)
  --fps60                ask for 60 fps
  --rhd                  right-hand drive
  --cluster              also offer an 800x480 instrument-cluster display (shown in a second window)
  --no-touch             present as a rotary/D-pad-only unit with no touchscreen (like a Mazda commander)
  --route NAME|FILE      simulated drive (default paris-marseille); "none" to disable
  --speed KMH            cruising speed on open road (default 130)
  --time-scale X         drive X times faster than real time (default 1)
  --serial S             adb serial of an emulator, to move its GPS along the route too
  --hu-cert PEM --hu-key PEM   head-unit certificate chain + PKCS#8 key to present (default: one made on first run, kept in ~/.config/aare/headunit)
  --headless DIR         no window: save a PNG of the screen to DIR every few seconds (for tests)
  --script "EV@S ..."    scripted input, seconds after connecting: tap:X,Y  key:CODE  rotate:N
                         dpad:up|down|left|right|center  night  day  park  drive

Keys: Esc/Backspace back · H home · V voice · arrows/wheel rotary · numpad 8/4/6/2 + 5 D-pad
      Enter select · K play/pause · , . prev/next
      N night/day · P parked/driving · Space pause the drive"""

fun main(args: Array<String>) {
    if ("--help" in args || "-h" in args) { println(USAGE); return }
    val opts = parseArgs(args)
    if (opts.containsKey("hu-cert") != opts.containsKey("hu-key")) {
        System.err.println("--hu-cert and --hu-key go together")
        exitProcess(2)
    }
    // Finite too: NaN passes a `<= 0` test, and Infinity would make the speed slider unbounded.
    if (opts["speed"]?.let { s -> s.toDoubleOrNull()?.let { it.isFinite() && it > 0 } != true } == true) {
        System.err.println("--speed needs a positive number of km/h")
        exitProcess(2)
    }
    if (opts.containsKey("gps-only")) return gpsOnly(opts)
    if (!VideoDecoder.available()) {
        System.err.println("ffmpeg not found: install it (it decodes the car screen).")
        return
    }
    if (opts.containsKey("headless")) System.setProperty("java.awt.headless", "true")
    else runCatching { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()) }
    SwingUtilities.invokeLater { DesktopHeadUnit(opts).show() }
}

/**
 * No window: only moves an emulator's GPS along the route (used with Google's DHU, where the
 * official app reads the phone's location).
 */
private fun gpsOnly(opts: Map<String, String>) {
    val route = RouteDrive.load(opts["route"] ?: "paris-marseille")
    val gps = EmulatorGps(opts["serial"])
    if (!gps.enabled) { System.err.println("--gps-only needs --serial emulator-NNNN"); return }
    val cruise = (opts["speed"]?.toDouble() ?: 130.0) / 3.6
    val scale = opts["time-scale"]?.toDouble() ?: 1.0
    var travelled = 0.0
    while (travelled < route.totalMeters) {
        val speed = route.speedAt(travelled, cruise)
        val fix = route.at(travelled, speed * scale)
        gps.fix(fix)
        print("\r%.0f / %.0f km   ".format(fix.travelledMeters / 1000, fix.totalMeters / 1000))
        Thread.sleep(1000)
        travelled += speed * scale
    }
    println("\narrived in Marseille")
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val next = args.getOrNull(i + 1)
            if (next != null && !next.startsWith("--")) { m[a.removePrefix("--")] = next; i += 2 } else { m[a.removePrefix("--")] = "true"; i++ }
        } else i++
    }
    return m
}

/**
 * The DER of the first PKCS#8 key in [pem]. Only the lines between its BEGIN and END markers count,
 * so a bundle with the certificate in it, or openssl's "Bag Attributes" headers, still works.
 */
internal fun pkcs8KeyDer(pem: String): ByteArray {
    val body = pem.lineSequence().map { it.trim() }
        .dropWhile { it != "-----BEGIN PRIVATE KEY-----" }.drop(1)
        .takeWhile { it != "-----END PRIVATE KEY-----" }.joinToString("")
    require(body.isNotEmpty()) { "no PKCS#8 key (-----BEGIN PRIVATE KEY-----) found" }
    return Base64.getMimeDecoder().decode(body)
}

/** The window, the connection loop and the simulated drive. */
private class DesktopHeadUnit(val opts: Map<String, String>) {
    val portrait = opts.containsKey("portrait")
    val res = (opts["res"] ?: if (portrait) "720x1280" else "1280x720").split("x").map { it.toInt() }
        .let { if (portrait && it[0] > it[1]) it.reversed() else it }
    val width = res[0]
    val height = res[1]
    val config = HeadUnit.Options(
        width = width, height = height,
        dpi = opts["dpi"]?.toInt() ?: if (minOf(width, height) >= 1080) 240 else 160,
        fps60 = opts.containsKey("fps60"),
        rightHandDrive = opts.containsKey("rhd"),
        cluster = opts.containsKey("cluster"),
        noTouch = opts.containsKey("no-touch"),
    )
    val identity: PhoneIdentity = loadIdentity()
    val route: RouteDrive? = opts["route"].let { r -> if (r == "none") null else runCatching { RouteDrive.load(r ?: "paris-marseille") }.getOrNull() }
    val gps = EmulatorGps(opts["serial"])

    val frame by lazy { JFrame("AARE head unit") }
    val clusterFrame by lazy { JFrame("AARE head unit: instrument cluster") }
    val screen by lazy { ScreenPanel() }
    val status = label("Waiting for the phone…", 15f, bold = true)
    val phone = label("", 13f)
    val nav = label("—", 14f)
    val playing = label("—", 13f)
    val phoneState = label("—", 13f)
    val drivePos = label("", 12f)
    val progress = JProgressBar(0, 1000)
    val audio = AudioOutputs()
    @Volatile var hu: HeadUnit? = null
    @Volatile var decoder: VideoDecoder? = null
    /** The last SPS/PPS from the phone, replayed to each new decoder. */
    @Volatile var codecConfig: ByteArray? = null
    /** The size the phone encodes at (a standard size, not necessarily the window's). */
    @Volatile var videoSize = width to height

    /** The instrument cluster's picture (--cluster): its own window and decoder. */
    @Volatile var clusterDecoder: VideoDecoder? = null
    @Volatile var clusterConfig: ByteArray? = null
    val clusterPanel by lazy { ClusterPanel() }

    class ClusterPanel : JPanel() {
        @Volatile var image: java.awt.image.BufferedImage? = null

        init {
            background = Color.BLACK
            preferredSize = Dimension(800, 480)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            image?.let { g.drawImage(it, 0, 0, width, height, null) }
        }
    }

    fun startClusterDecoder() {
        clusterDecoder?.close()
        clusterDecoder = VideoDecoder(800, 480) { img ->
            clusterPanel.image = img; clusterPanel.repaint()
            headlessDir?.let { dir -> if (clusterDecoder?.frames?.rem(90) == 1) javax.imageio.ImageIO.write(img, "png", File(dir, "cluster-latest.png")) }
        }.also { d -> clusterConfig?.let { d.feed(it, 0, it.size) } }
    }

    /** A fresh ffmpeg, primed with the stream's SPS/PPS so it can decode from the next frame. */
    fun startDecoder(width: Int, height: Int) {
        videoSize = width to height
        decoder?.close()
        decoder = VideoDecoder(width, height) { img -> screen.image = img; screen.repaint(); snapshot(img) }
            .also { d -> codecConfig?.let { d.feed(it, 0, it.size) } }
    }
    var mic: Microphone? = null

    // drive state
    var travelled = 0.0
    var cruiseKmh = opts["speed"]?.toDouble() ?: 130.0
    var timeScale = opts["time-scale"]?.toDouble() ?: 1.0
    var paused = false
    var night = false
    var driving = true

    val headlessDir: File? = opts["headless"]?.let { File(it).apply { mkdirs() } }
    var lastSnapshot = 0L
    var snapshots = 0

    fun show() {
        if (headlessDir != null) {
            println("headless: snapshots in $headlessDir")
            // Not a daemon: with no window, nothing else keeps the JVM running.
            thread(name = "connect") { connectLoop() }
            startDrive()
            return
        }
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { hu?.byeBye(); Thread.sleep(300); System.exit(0) }
        })
        frame.contentPane.background = BG
        frame.layout = BorderLayout()
        frame.add(screen, BorderLayout.CENTER)
        frame.add(sidePanel(), BorderLayout.EAST)
        screen.preferredSize = if (height > width) {
            // portrait: fit a laptop screen's height
            Dimension(height.coerceAtMost(960) * width / height, height.coerceAtMost(960))
        } else Dimension(width.coerceAtMost(1280), (width.coerceAtMost(1280) * height / width))
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        installKeys()
        thread(isDaemon = true, name = "connect") { connectLoop() }
        startDrive()
    }

    // ------------------------------------------------------------------ connection

    /** `adb forward tcp:[port] <--adb-target>` (default localabstract:aare_hu) to the phone (quietly: the phone may not be there yet). */
    private fun adbForward(port: Int) {
        val cmd = listOfNotNull("adb", opts["serial"]?.let { "-s" }, opts["serial"], "forward", "tcp:$port", opts["adb-target"] ?: "localabstract:aare_hu")
        runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    fun connectLoop() {
        val host = opts["host"] ?: "127.0.0.1"
        val port = opts["port"]?.toInt() ?: 5277
        while (true) {
            // adb drops its port forward when the phone is unplugged: set it up again before each try.
            if (opts["host"] == null) adbForward(port)
            val socket = runCatching { Socket().apply { connect(InetSocketAddress(host, port), 3000) } }.getOrNull()
            if (socket == null) {
                ui { status.text = "Waiting for the phone on $host:$port…" }
                Thread.sleep(2000)
                continue
            }
            val done = java.util.concurrent.CountDownLatch(1)
            val unit = HeadUnit(SocketTransport(socket), identity, config, object : HeadUnit.Listener {
                override fun status(text: String) {
                    println("status: $text")
                    ui { status.text = text; phone.text = hu?.phoneName ?: "" }
                }
                override fun videoStarted(width: Int, height: Int) {
                    println("video started ${width}x$height")
                    startDecoder(width, height)
                }
                override fun clusterStarted(width: Int, height: Int) {
                    println("cluster video started ${width}x$height")
                    startClusterDecoder()
                    if (headlessDir == null) ui {
                        if (clusterFrame.contentPane.componentCount == 0) { clusterFrame.add(clusterPanel); clusterFrame.pack() }
                        clusterFrame.isVisible = true
                    }
                }
                override fun clusterVideo(data: ByteArray, offset: Int, length: Int, config: Boolean) {
                    if (config) clusterConfig = data.copyOfRange(offset, offset + length)
                    val d = clusterDecoder
                    if (d == null || !d.alive) startClusterDecoder()
                    runCatching { clusterDecoder?.feed(data, offset, length) }
                }
                override fun videoCodecConfig(data: ByteArray, offset: Int, length: Int) {
                    codecConfig = data.copyOfRange(offset, offset + length)
                    video(data, offset, length)
                }
                override fun video(data: ByteArray, offset: Int, length: Int) {
                    val d = decoder
                    if (d == null || !d.alive) {
                        if (d != null) println("video decoder (ffmpeg) exited after ${d.frames} frames, restarting it")
                        startDecoder(videoSize.first, videoSize.second)
                    }
                    // Never let a decoder problem take the connection down.
                    runCatching { decoder?.feed(data, offset, length) }.onFailure { println("video: ${it.message}") }
                }
                override fun audioStart(stream: AudioStreamType, config: AudioConfiguration) = audio.start(stream, config)
                override fun audio(stream: AudioStreamType, data: ByteArray, offset: Int, length: Int) = audio.write(stream, data, offset, length)
                override fun audioStop(stream: AudioStreamType) = audio.stop(stream)
                override fun micOpened() {
                    mic = Microphone { b, n -> hu?.micData(b, n) }.also { it.open() }
                    ui { status.text = "🎤 Listening (your microphone is the car's)" }
                }
                override fun micClosed() { mic?.close(); mic = null; ui { status.text = "Connected to ${hu?.phoneName}" } }
                override fun navigation(text: String) = ui { nav.text = html(text) }
                override fun nowPlaying(text: String) = ui { playing.text = html(text) }
                override fun phone(text: String) { println("phone: $text"); ui { phoneState.text = html(text) } }
                override fun closed(reason: String) {
                    println("closed: $reason")
                    ui { status.text = "Disconnected: $reason"; screen.image = null; screen.repaint() }
                    decoder?.close(); decoder = null; codecConfig = null
                    clusterDecoder?.close(); clusterDecoder = null; clusterConfig = null
                    audio.close(); mic?.close()
                    done.countDown()
                }
            })
            hu = unit
            scheduleScript(unit)
            unit.setNight(night)
            unit.setDriving(driving)
            unit.start()
            done.await()
            hu = null
            Thread.sleep(2000)
        }
    }

    /** Headless mode: keep a PNG of the screen every 3 s (and the latest one as latest.png). */
    fun snapshot(img: BufferedImage) {
        val dir = headlessDir ?: return
        val now = System.currentTimeMillis()
        if (now - lastSnapshot < 3000) return
        lastSnapshot = now
        val name = "screen-%03d.png".format(snapshots++)
        javax.imageio.ImageIO.write(img, "png", File(dir, name))
        javax.imageio.ImageIO.write(img, "png", File(dir, "latest.png"))
        println("snapshot $name  status=${status.text}  nav=${nav.text}  playing=${playing.text}")
    }

    /** --script "tap:640,360@10 key:4@15": input relative to the connection. */
    fun scheduleScript(unit: HeadUnit) {
        val script = opts["script"] ?: return
        for (ev in script.split(' ').filter { it.contains('@') }) {
            val (what, at) = ev.split('@')
            thread(isDaemon = true) {
                Thread.sleep((at.toDouble() * 1000).toLong())
                if (hu !== unit) return@thread
                println("script: $what")
                when {
                    what.startsWith("tap:") -> what.removePrefix("tap:").split(',').let { (x, y) ->
                        unit.touch(PointerAction.ACTION_DOWN, x.toInt(), y.toInt()); Thread.sleep(60)
                        unit.touch(PointerAction.ACTION_UP, x.toInt(), y.toInt())
                    }
                    what.startsWith("key:") -> unit.pressKey(what.removePrefix("key:").toInt())
                    what.startsWith("rotate:") -> unit.rotate(what.removePrefix("rotate:").toInt())
                    what.startsWith("dpad:") -> unit.pressKey(when (what.removePrefix("dpad:")) {
                        "up" -> 19; "down" -> 20; "left" -> 21; "right" -> 22; "center", "centre", "ok" -> 23
                        else -> return@thread
                    })
                    what == "night" -> unit.setNight(true).also { night = true }
                    what == "day" -> unit.setNight(false).also { night = false }
                    what == "park" -> unit.setDriving(false).also { driving = false }
                    what == "drive" -> unit.setDriving(true).also { driving = true }
                }
            }
        }
    }

    fun loadIdentity(): PhoneIdentity {
        val certPath = opts["hu-cert"]
        val keyPath = opts["hu-key"]
        if (certPath != null && keyPath != null) {
            // the user's own certificate: a bad one is an error, not something to replace quietly
            return try {
                readIdentity(File(certPath), File(keyPath))
            } catch (e: Exception) {
                val text = runCatching { File(keyPath).readText() }.getOrDefault("")
                val hint = when {
                    "BEGIN RSA PRIVATE KEY" in text -> " (PKCS#1 key: convert it with `openssl pkcs8 -topk8 -nocrypt`)"
                    "BEGIN EC PRIVATE KEY" in text -> " (an EC key: the head unit needs an RSA key)"
                    else -> ""
                }
                System.err.println("can't use --hu-cert $certPath / --hu-key $keyPath: ${e.message}$hint")
                exitProcess(1)
            }
        }
        // no certificate given: keep one per user, so the phone's Trust sticks across runs
        val dir = File(configHome(), "aare/headunit")
        val certFile = File(dir, "cert.pem")
        val keyFile = File(dir, "key.pem")
        if (certFile.isFile && keyFile.isFile) {
            try {
                val saved = readIdentity(certFile, keyFile)
                // a crash between saving the two files can leave a certificate and a key that don't belong together
                check((saved.certificate.publicKey as java.security.interfaces.RSAPublicKey).modulus == (saved.privateKey as java.security.interfaces.RSAPrivateCrtKey).modulus) { "the key doesn't match the certificate" }
                return saved.also { println("head unit certificate: ${certFile.path}") }
            } catch (e: Exception) {
                System.err.println("can't use ${certFile.path} / ${keyFile.path} (${e.message}); making a new certificate")
            }
        }
        val g = SelfSignedCertificate.generate("AARE desktop head unit")
        try {
            if (posix()) java.nio.file.Files.createDirectories(dir.toPath(), ownerOnlyAttr("rwx------")) else dir.mkdirs()
            writePem(certFile, "CERTIFICATE", g.certificate.encoded)
            writePem(keyFile, "PRIVATE KEY", g.keyPair.private.encoded, ownerOnly = true)
            println("head unit certificate: new, saved to ${certFile.path}")
        } catch (e: Exception) {
            System.err.println("can't save the head unit certificate to ${dir.path} (${e.message}); it is only good for this run")
        }
        return PhoneIdentity(g.certificate, g.keyPair.private)
    }

    fun readIdentity(certFile: File, keyFile: File): PhoneIdentity {
        val certs = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }.map { it as X509Certificate }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8KeyDer(keyFile.readText())))
        return PhoneIdentity(certs.firstOrNull() ?: error("no certificate in ${certFile.path}"), key, certs.drop(1))
    }

    /** $XDG_CONFIG_HOME or ~/.config; %APPDATA% on Windows. */
    fun configHome(): File {
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        if (System.getProperty("os.name").startsWith("Windows")) System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return File(System.getProperty("user.home"), ".config")
    }

    fun posix() = "posix" in java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()

    fun ownerOnlyAttr(perms: String) =
        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString(perms))

    fun writePem(file: File, type: String, der: ByteArray, ownerOnly: Boolean = false) {
        val tmp = File(file.path + ".tmp")
        tmp.delete()
        if (ownerOnly && posix()) {
            // created owner-only, so no one else can open it, even before the key goes in
            java.nio.file.Files.createFile(tmp.toPath(), ownerOnlyAttr("rw-------"))
        } else {
            // Windows: the file system can't take read access away, and %APPDATA% is per user anyway
            tmp.createNewFile()
        }
        tmp.writeText("-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n")
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    // ------------------------------------------------------------------ simulated drive

    fun startDrive() {
        val r = route ?: run { drivePos.text = "No route"; return }
        var last = System.nanoTime()
        var lastGps = 0L
        val tick = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "drive").apply { isDaemon = true } }
        tick.scheduleAtFixedRate({
            val now = System.nanoTime()
            val dt = (now - last) / 1e9
            last = now
            val speed = if (paused || !driving) 0.0 else r.speedAt(travelled, cruiseKmh / 3.6)
            travelled = (travelled + speed * dt * timeScale).coerceAtMost(r.totalMeters)
            val fix = r.at(travelled, speed * timeScale)
            hu?.location(fix)
            // the emulator's GPS at 1 Hz is plenty, and adb calls aren't free
            if (now - lastGps > 1_000_000_000L) { lastGps = now; thread(isDaemon = true) { gps.fix(fix) } }
            val text = html("%.4f, %.4f<br>%.0f / %.0f km · %.0f km/h%s".format(fix.lat, fix.lon, fix.travelledMeters / 1000,
                fix.totalMeters / 1000, speed * 3.6, if (timeScale != 1.0) " ×%.0f".format(timeScale) else ""))
            ui { progress.value = (fix.progress * 1000).toInt(); drivePos.text = text }
        }, 0, 250, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------------ UI

    fun sidePanel(): JComponent {
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
        p.background = PANEL
        p.border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        p.preferredSize = Dimension(300, 0)
        fun section(title: String) { p.add(gap(14)); p.add(label(title.uppercase(), 11f, color = MUTED)); p.add(gap(4)) }

        p.add(label("AARE head unit", 18f, bold = true, color = ACCENT))
        p.add(gap(6)); p.add(status); p.add(phone)
        section("Navigation"); p.add(nav)
        section("Now playing"); p.add(playing)
        section("Phone"); p.add(phoneState)
        section("Drive: ${route?.let { "Paris → Marseille" } ?: "off"}")
        progress.foreground = ACCENT; progress.background = BG; progress.border = null
        p.add(progress); p.add(gap(4)); p.add(drivePos)
        p.add(gap(6))
        p.add(row(
            toggle("Pause", false) { paused = it },
            JComboBox(arrayOf("×1", "×10", "×60", "×300")).apply {
                selectedItem = "×${timeScale.toInt()}".takeIf { s -> (0 until itemCount).any { getItemAt(it) == s } } ?: "×1"
                addActionListener { timeScale = (selectedItem as String).drop(1).toDouble() }
                isFocusable = false
            },
        ))
        p.add(gap(6))
        p.add(label("Cruise speed", 11f, color = MUTED))
        // widened to take a --speed outside the usual range: JSlider throws on a value outside min..max
        val kmh = cruiseKmh.toInt()
        p.add(JSlider(minOf(50, kmh), maxOf(160, kmh), kmh).apply {
            background = PANEL; isFocusable = false
            addChangeListener { cruiseKmh = value.toDouble() }
        })
        section("Car")
        p.add(row(toggle("Night", night) { night = it; hu?.setNight(it) }, toggle("Parked", !driving) { driving = !it; hu?.setDriving(driving) }))
        p.add(gap(6))
        p.add(row(button("Back") { hu?.pressKey(4) }, button("Home") { hu?.pressKey(3) }, button("🎤 Voice") { hu?.pressKey(84) }))
        p.add(gap(6))
        p.add(row(button("⏮") { hu?.pressKey(88) }, button("⏯") { hu?.pressKey(85) }, button("⏭") { hu?.pressKey(87) }))

        // Rotary knob and D-pad, for testing focus navigation on units with no touchscreen (#42).
        section("Rotary / D-pad" + if (config.noTouch) " (no touchscreen)" else "")
        p.add(row(button("⟲ rotate") { hu?.rotate(-1) }, button("rotate ⟳") { hu?.rotate(1) }))
        p.add(gap(6))
        p.add(dpad())

        p.add(gap(14))
        p.add(label(html("Mouse = touch · Esc back · H home · V voice · wheel/arrows rotary · numpad D-pad · N night · P parked · Space pause drive"), 11f, color = MUTED))
        return p
    }

    /** A 3×3 grid of D-pad buttons sending the real D-pad keycodes (19-22) and centre (23). */
    private fun dpad(): JComponent {
        fun key(text: String, code: Int) = button(text) { hu?.pressKey(code) }
        return JPanel(GridLayout(3, 3, 6, 6)).apply {
            isOpaque = false; alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, 120)
            add(JLabel()); add(key("▲", 19)); add(JLabel())
            add(key("◀", 21)); add(key("OK", 23)); add(key("▶", 22))
            add(JLabel()); add(key("▼", 20)); add(JLabel())
        }
    }

    fun installKeys() {
        val keys = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val h = hu
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> h?.pressKey(4)
                    KeyEvent.VK_H -> h?.pressKey(3)
                    KeyEvent.VK_V -> h?.pressKey(84)
                    KeyEvent.VK_K -> h?.pressKey(85)
                    KeyEvent.VK_COMMA -> h?.pressKey(88)
                    KeyEvent.VK_PERIOD -> h?.pressKey(87)
                    KeyEvent.VK_ENTER -> h?.pressKey(23)
                    KeyEvent.VK_UP, KeyEvent.VK_LEFT -> h?.rotate(-1)
                    KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT -> h?.rotate(1)
                    // Numpad: a real D-pad (keycodes 19-22 + centre 23), distinct from the rotary.
                    KeyEvent.VK_NUMPAD8 -> h?.pressKey(19)
                    KeyEvent.VK_NUMPAD2 -> h?.pressKey(20)
                    KeyEvent.VK_NUMPAD4 -> h?.pressKey(21)
                    KeyEvent.VK_NUMPAD6 -> h?.pressKey(22)
                    KeyEvent.VK_NUMPAD5 -> h?.pressKey(23)
                    KeyEvent.VK_N -> { night = !night; h?.setNight(night) }
                    KeyEvent.VK_P -> { driving = !driving; h?.setDriving(driving) }
                    KeyEvent.VK_SPACE -> paused = !paused
                }
            }
        }
        frame.addKeyListener(keys)
        screen.addKeyListener(keys)
        screen.isFocusable = true
        screen.requestFocusInWindow()
    }

    /** Draws the projection letterboxed and turns mouse input into touches in video coordinates. */
    inner class ScreenPanel : JPanel() {
        @Volatile var image: BufferedImage? = null
        private var pressed = false

        init {
            background = Color.BLACK
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { requestFocusInWindow(); toVideo(e)?.let { pressed = true; hu?.touch(PointerAction.ACTION_DOWN, it.first, it.second) } }
                override fun mouseDragged(e: MouseEvent) { if (pressed) toVideo(e, clamp = true)?.let { hu?.touch(PointerAction.ACTION_MOVED, it.first, it.second) } }
                override fun mouseReleased(e: MouseEvent) { if (pressed) { pressed = false; toVideo(e, clamp = true)?.let { hu?.touch(PointerAction.ACTION_UP, it.first, it.second) } } }
                override fun mouseWheelMoved(e: MouseWheelEvent) { hu?.rotate(e.wheelRotation) }
            }
            addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse)
        }

        private fun viewport(): IntArray {
            val s = minOf(getWidth().toDouble() / this@DesktopHeadUnit.width, getHeight().toDouble() / this@DesktopHeadUnit.height)
            val w = (this@DesktopHeadUnit.width * s).toInt(); val h = (this@DesktopHeadUnit.height * s).toInt()
            return intArrayOf((getWidth() - w) / 2, (getHeight() - h) / 2, w, h)
        }

        private fun toVideo(e: MouseEvent, clamp: Boolean = false): Pair<Int, Int>? {
            val (x0, y0, w, h) = viewport().let { listOf(it[0], it[1], it[2], it[3]) }
            var x = (e.x - x0) * this@DesktopHeadUnit.width / w
            var y = (e.y - y0) * this@DesktopHeadUnit.height / h
            if (clamp) { x = x.coerceIn(0, this@DesktopHeadUnit.width - 1); y = y.coerceIn(0, this@DesktopHeadUnit.height - 1) }
            return if (x in 0 until this@DesktopHeadUnit.width && y in 0 until this@DesktopHeadUnit.height) x to y else null
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            val img = image
            val v = viewport()
            if (img != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.drawImage(img, v[0], v[1], v[2], v[3], null)
            } else {
                g2.color = MUTED
                g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
                val msg = "Connect a phone running AARE (head unit server on), or the official app's head unit server"
                val fm = g2.fontMetrics
                g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2)
            }
        }
    }

    fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)
}

// ---------------------------------------------------------------- small Swing helpers

private val BG = Color(0x0B, 0x0F, 0x0F)
private val PANEL = Color(0x16, 0x1D, 0x1C)
private val TEXT = Color(0xE3, 0xE9, 0xE7)
private val MUTED = Color(0x9A, 0xA8, 0xA5)
private val ACCENT = Color(0x3D, 0xDB, 0xC6)

private fun html(s: String) = "<html>${s.replace("\n", "<br>")}</html>"

private fun label(text: String, size: Float, bold: Boolean = false, color: Color = TEXT) = JLabel(text).apply {
    font = font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
    foreground = color
    alignmentX = 0f
}

private fun gap(h: Int) = javax.swing.Box.createVerticalStrut(h)

private fun row(vararg c: JComponent) = JPanel(GridLayout(1, c.size, 6, 0)).apply {
    isOpaque = false; alignmentX = 0f
    maximumSize = Dimension(Int.MAX_VALUE, 34)
    c.forEach { add(it) }
}

private fun button(text: String, onClick: () -> Unit) = JButton(text).apply {
    isFocusable = false
    background = Color(0x26, 0x30, 0x2E); foreground = TEXT
    border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
    addActionListener { onClick() }
}

private fun toggle(text: String, on: Boolean, onChange: (Boolean) -> Unit) = JToggleButton(text, on).apply {
    isFocusable = false
    background = Color(0x26, 0x30, 0x2E); foreground = TEXT
    border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
    addActionListener { onChange(isSelected) }
}

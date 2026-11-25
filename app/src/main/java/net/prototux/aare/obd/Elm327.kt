package net.prototux.aare.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Talks to an OBD2 adapter (an ELM327 or compatible over classic Bluetooth) and turns the car's
 * answers into numbers. READ ONLY: mode 01 requests (current data), nothing here writes to any of
 * the car's modules or clears anything. Everything blocks: call it off the main thread.
 */
interface ObdLink {
    /** What to call it in the status line (the adapter's Bluetooth name, or the simulator's). */
    val name: String
    /** Sends a text command and returns the answer up to the ELM's ">" prompt, or with [NO_ANSWER] appended. */
    @Throws(IOException::class)
    fun send(command: String, waitMs: Long): String
    fun close()

    companion object {
        const val NO_ANSWER = "[no answer]"
    }
}

/** Classic Bluetooth (the adapter is paired in the phone's settings, PIN 1234 or 0000). */
@SuppressLint("MissingPermission")
class BluetoothObdLink(device: BluetoothDevice) : ObdLink {
    override val name: String = device.name ?: "OBD2"
    private val socket: BluetoothSocket
    private val stream: ElmStream

    init {
        var s = device.createRfcommSocketToServiceRecord(SPP)
        try {
            s.connect()
        } catch (e: IOException) {
            s.close()
            s = device.createInsecureRfcommSocketToServiceRecord(SPP)
            s.connect()
        }
        socket = s
        stream = ElmStream(s.inputStream, s.outputStream)
    }

    override fun send(command: String, waitMs: Long) = stream.send(command, waitMs)

    /** Closing the socket also ends [ElmStream]'s reading thread. */
    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** The Bluetooth "serial port" profile. */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * The adapter to read: the one the user picked ([chosen], a Bluetooth address), or else the
         * paired devices whose name is clearly an OBD2 adapter ([Elm327.isAdapterName]).
         */
        @SuppressLint("MissingPermission")
        fun pairedAdapters(context: Context, chosen: String?): List<BluetoothDevice> {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
            if (!adapter.isEnabled) return emptyList()
            val bonded = adapter.bondedDevices.orEmpty()
            if (chosen != null) return bonded.filter { it.address == chosen }
            return bonded.filter { d -> d.name?.let(Elm327::isAdapterName) == true }
        }

        /** The paired devices (address to name), for picking the adapter in the settings. */
        @SuppressLint("MissingPermission")
        fun pairedDevices(context: Context): List<Pair<String, String>> = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { it.address to (it.name ?: it.address) }.sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())

        fun bluetoothOn(context: Context) = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }
}

/**
 * The ELM327's text exchange over a serial stream. A thread blocks reading [input] and hands each
 * byte over, so waiting for an answer sleeps until it arrives (a Bluetooth socket's stream has no
 * read timeout, so [send]'s limit is kept here) instead of checking the stream over and over.
 */
class ElmStream(private val input: InputStream, private val output: OutputStream) {
    private val bytes = LinkedBlockingQueue<Int>()
    @Volatile private var closed = false

    init {
        Thread({
            val buffer = ByteArray(256)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    for (i in 0 until n) bytes.put(buffer[i].toInt() and 0xFF)
                }
            } catch (_: IOException) {
                // The link was closed.
            } finally {
                closed = true
                bytes.put(END)
            }
        }, "aare-obd-read").apply { isDaemon = true; start() }
    }

    /** Sends a command and returns the answer up to the ">" prompt, or with [ObdLink.NO_ANSWER] after [waitMs]. */
    @Throws(IOException::class)
    fun send(command: String, waitMs: Long): String {
        if (closed) throw IOException("closed")
        bytes.clear() // leftovers of an answer that came too late
        output.write("$command\r".toByteArray())
        output.flush()
        val answer = StringBuilder()
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val c = try { bytes.poll(left, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { throw IOException("stopped") } ?: break
            if (c == END) throw IOException("closed")
            if (c == '>'.code) return answer.toString()
            answer.append(c.toChar())
        }
        return answer.toString() + ObdLink.NO_ANSWER
    }

    private companion object {
        const val END = -1
    }
}

/** One snapshot of the car; null = the car doesn't report it (or hasn't yet). */
data class ObdReadings(
    val rpm: Float? = null,
    /** km/h */
    val speed: Float? = null,
    /** bar above the outside air pressure (negative: vacuum). */
    val boost: Float? = null,
    /** °C */
    val coolant: Float? = null,
    val intakeAir: Float? = null,
    /** V */
    val battery: Float? = null,
    /** % */
    val fuel: Float? = null,
    val ethanol: Float? = null,
    val outside: Float? = null,
    /** Calculated engine load, %. */
    val load: Float? = null,
    /** Absolute pressures, kPa: the intake manifold and the outside air (for the turbo or vacuum gauge). */
    val manifoldKpa: Float? = null,
    val barometricKpa: Float? = null,
    /** Mass air flow, g/s (cars with a MAF sensor). */
    val maf: Float? = null,
    /** Fuel rate, L/h (only some newer cars report it). */
    val fuelRate: Float? = null,
    /** Throttle and accelerator pedal position, %. */
    val throttle: Float? = null,
    val pedal: Float? = null,
    /** Engine oil, °C. */
    val oil: Float? = null,
)

/** The ELM327 language and the standard mode 01 formulas. */
object Elm327 {
    /**
     * A paired device's name that is clearly an OBD2 adapter (OBD, OBDII, OBDLink, ELM327, V-LINK,
     * Vgate, Konnwei, Veepeak): commands go only to those, never to a headset or a car.
     */
    fun isAdapterName(name: String) = ADAPTER_NAME.containsMatchIn(name)
    private val ADAPTER_NAME = Regex("(?i)obd|elm ?327|\\bv-?link|vgate|konnwei|veepeak")

    /** Reset, no echo, no extra line feeds, no spaces, no headers, automatic protocol. */
    val SETUP = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
    /** Adaptive timing and the engine only (7E0): on some cars two modules answer and the ELM waits for its timeout. */
    val ENGINE_ONLY = listOf("ATAT2", "ATSH7E0")
    const val ALL_MODULES = "ATSH7DF"

    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val MANIFOLD = 0x0B
    const val COOLANT = 0x05
    const val INTAKE_AIR = 0x0F
    const val BAROMETRIC = 0x33
    const val BATTERY = 0x42
    const val FUEL = 0x2F
    const val ETHANOL = 0x52
    const val OUTSIDE = 0x46
    const val LOAD = 0x04
    const val MAF = 0x10
    const val THROTTLE = 0x11
    const val PEDAL = 0x49
    const val OIL = 0x5C
    const val FUEL_RATE = 0x5E

    /** What we read: fast ones every round, medium and slow ones one at a time in turn (see [ObdPoller]). */
    val FAST = listOf(RPM, SPEED, MANIFOLD)
    val MEDIUM = listOf(MAF, FUEL_RATE, LOAD, PEDAL, THROTTLE)
    val READ = listOf(LOAD, COOLANT, MANIFOLD, RPM, SPEED, MAF, THROTTLE, INTAKE_AIR, FUEL, BAROMETRIC, BATTERY, OUTSIDE, PEDAL, ETHANOL, OIL, FUEL_RATE)

    /** Prepares the adapter and finds which PIDs the car has (empty: the car didn't answer, ignition off). */
    fun prepare(link: ObdLink): Set<Int> {
        for (cmd in SETUP) link.send(cmd, if (cmd == "ATZ") 4_000 else 2_000)
        val maps = mapOf(
            0x00 to link.send("0100", 10_000),
            0x20 to link.send("0120", 4_000),
            0x40 to link.send("0140", 4_000),
        )
        return supported(maps)
    }

    /** Tries talking to the engine module only (faster). Returns whether it stayed so. */
    fun engineOnly(link: ObdLink): Boolean {
        for (cmd in ENGINE_ONLY) link.send(cmd, 2_000)
        if (bytes(link.send("010C", 2_000), RPM) != null) return true
        link.send(ALL_MODULES, 2_000)
        return false
    }

    fun lines(answer: String): List<String> =
        answer.replace("SEARCHING...", "").replace(" ", "").split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Every module's answer to [pid] (engine and gearbox may both answer): one byte array each. */
    fun allAnswers(answer: String, pid: Int): List<IntArray> {
        val prefix = "41%02X".format(pid)
        return lines(answer).mapNotNull { line ->
            if (!line.startsWith(prefix)) return@mapNotNull null
            val hex = line.substring(prefix.length)
            if (hex.length < 2 || hex.length % 2 != 0) return@mapNotNull null
            runCatching { IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) } }.getOrNull()
        }
    }

    fun bytes(answer: String, pid: Int): IntArray? = allAnswers(answer, pid).firstOrNull()

    /** The standard formulas; null for a cut answer. */
    fun value(pid: Int, b: IntArray): Float? {
        if ((pid == RPM || pid == BATTERY || pid == MAF || pid == FUEL_RATE) && b.size < 2) return null
        if (b.isEmpty()) return null
        val a = b[0].toFloat()
        val ab = if (b.size >= 2) a * 256f + b[1] else a
        return when (pid) {
            RPM -> ab / 4f
            SPEED, MANIFOLD, BAROMETRIC -> a
            COOLANT, INTAKE_AIR, OUTSIDE, OIL -> a - 40f
            LOAD, FUEL, ETHANOL, THROTTLE, PEDAL -> a * 100f / 255f
            BATTERY -> ab / 1000f
            MAF -> if (b.size < 2) null else ab / 100f
            FUEL_RATE -> if (b.size < 2) null else ab / 20f
            else -> null
        }
    }

    /** The PIDs the car says it has (the answers to 0100, 0120 and 0140 are bit maps). */
    fun supported(answers: Map<Int, String>): Set<Int> {
        val pids = HashSet<Int>()
        for ((base, answer) in answers) {
            for (b in allAnswers(answer, base)) {
                for (i in 0 until 32) {
                    if (i / 8 >= b.size) break
                    if (b[i / 8] and (0x80 shr (i % 8)) != 0) pids += base + i + 1
                }
            }
        }
        return pids
    }
}

/**
 * Asks the car for its readings, one round at a time, keeping the last good value of each. The
 * first round reads everything; after that every round reads the fast ones (revs, speed, boost)
 * plus ONE other, taken in turn: a medium one (fuel use, pedal) every other round, a slow one
 * (temperatures, battery, tank) in between. Every round then takes about the same time, so the
 * gauges move evenly: reading all the slow ones in one round stalled them for half a second.
 * A silent adapter several times in a row means it dropped: [IOException].
 */
class ObdPoller(private val link: ObdLink, supported: Set<Int>) {
    private val fast = Elm327.FAST.filter { it in supported }
    private val medium = Elm327.MEDIUM.filter { it in supported && it !in fast }
    private val slow = Elm327.READ.filter { it in supported && it !in fast && it !in medium }
    private val values = HashMap<Int, Float>()
    private var round = 0
    private var nextMedium = 0
    private var nextSlow = 0
    private var silences = 0

    /** Questions sent so far, for the reading rate in the log. */
    var questions = 0L; private set

    /** What this round asks: all at first, then the fast ones and one more in turn. */
    private fun nextPids(): List<Int> {
        if (round == 0) return fast + medium + slow
        val useMedium = medium.isNotEmpty() && (slow.isEmpty() || round % 2 == 1)
        val extra = when {
            useMedium -> medium[nextMedium++ % medium.size]
            slow.isNotEmpty() -> slow[nextSlow++ % slow.size]
            else -> null
        }
        return fast + listOfNotNull(extra)
    }

    fun readRound(): ObdReadings {
        val pids = nextPids()
        for (pid in pids) {
            val answer = link.send("01%02X".format(pid), 1_500)
            questions++
            val x = Elm327.bytes(answer, pid)?.let { Elm327.value(pid, it) }
            if (x == null) {
                // An occasional NO DATA / STOPPED is normal; silence over and over is not.
                if (answer.contains(ObdLink.NO_ANSWER) && ++silences >= SILENCES_TO_RECONNECT) throw IOException("the adapter stopped answering")
                continue
            }
            silences = 0
            values[pid] = x
        }
        round++
        val manifold = values[Elm327.MANIFOLD]
        val atmosphere = values[Elm327.BAROMETRIC] ?: 100f
        return ObdReadings(
            rpm = values[Elm327.RPM],
            speed = values[Elm327.SPEED],
            boost = manifold?.let { (it - atmosphere) / 100f }, // kPa -> bar
            coolant = values[Elm327.COOLANT],
            intakeAir = values[Elm327.INTAKE_AIR],
            battery = values[Elm327.BATTERY],
            fuel = values[Elm327.FUEL],
            ethanol = values[Elm327.ETHANOL],
            outside = values[Elm327.OUTSIDE],
            load = values[Elm327.LOAD],
            manifoldKpa = manifold,
            barometricKpa = values[Elm327.BAROMETRIC],
            maf = values[Elm327.MAF],
            fuelRate = values[Elm327.FUEL_RATE],
            throttle = values[Elm327.THROTTLE],
            pedal = values[Elm327.PEDAL],
            oil = values[Elm327.OIL],
        )
    }

    companion object {
        const val SILENCES_TO_RECONNECT = 8
    }
}

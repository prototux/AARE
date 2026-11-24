package net.prototux.aare.obd

/**
 * A pretend adapter: answers like an ELM327 and "drives" a 40 s loop (speeding up, cruising,
 * braking, standing). For the tests, and for trying the Car data page without a car (debug builds).
 */
class FakeObdLink(override val name: String = "Simulator", private val clock: () -> Long = System::currentTimeMillis, private val delayMs: Long = 40) : ObdLink {
    private val start = clock()
    private val has = Elm327.READ.toSet() - Elm327.FUEL_RATE

    override fun close() = Unit

    override fun send(command: String, waitMs: Long): String {
        if (delayMs > 0) try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return ObdLink.NO_ANSWER }
        if (command.startsWith("AT")) return if (command == "ATZ") "ELM327 v2.1" else "OK"
        if (!command.startsWith("01") || command.length < 4) return "?"
        val pid = command.substring(2, 4).toInt(16)
        if (pid == 0x00 || pid == 0x20 || pid == 0x40) return bitmap(pid)
        if (pid !in has) return "NODATA"
        return "41%02X".format(pid) + data(pid)
    }

    private fun bitmap(base: Int): String {
        var bits = 0L
        for (p in has) if (p > base && p <= base + 32) bits = bits or (1L shl (32 - (p - base)))
        if (base < 0x40) bits = bits or 1L // the next block exists
        return "41%02X%08X".format(base, bits)
    }

    private fun data(pid: Int): String {
        val t = ((clock() - start) / 1000f) % 40f
        val speed: Float; val rpm: Float; val manifold: Float; val load: Float
        when {
            t < 12 -> { // through three gears
                val gear = t % 4f / 4f
                speed = t / 12f * 100f; rpm = 1800 + gear * 3700; manifold = 120 + gear * 70; load = 70 + gear * 25
            }
            t < 24 -> { speed = 100f; rpm = 2300f; manifold = 95f; load = 35f }
            t < 32 -> { speed = 100 - (t - 24) / 8f * 100f; rpm = 1500 - (t - 24) * 80; manifold = 30f; load = 10f }
            else -> { speed = 0f; rpm = 850f; manifold = 35f; load = 20f }
        }
        return when (pid) {
            Elm327.RPM -> Math.round(rpm * 4).let { "%02X%02X".format(it shr 8, it and 0xFF) }
            Elm327.SPEED -> "%02X".format(Math.round(speed))
            Elm327.MANIFOLD -> "%02X".format(Math.round(manifold))
            Elm327.BAROMETRIC -> "%02X".format(92)
            Elm327.LOAD -> pct(load)
            Elm327.COOLANT -> "%02X".format(92 + 40)
            Elm327.INTAKE_AIR -> "%02X".format(38 + 40)
            Elm327.OUTSIDE -> "%02X".format(21 + 40)
            Elm327.FUEL -> pct(62f)
            Elm327.ETHANOL -> pct(32f)
            Elm327.BATTERY -> 14_100.let { "%02X%02X".format(it shr 8, it and 0xFF) }
            Elm327.MAF -> Math.round(manifold / 100f * rpm / 60f * 100f).let { "%02X%02X".format(it shr 8, it and 0xFF) }
            Elm327.THROTTLE, Elm327.PEDAL -> pct(load)
            Elm327.OIL -> "%02X".format(98 + 40)
            else -> "00"
        }
    }

    private fun pct(p: Float) = "%02X".format(Math.round(p * 255f / 100f))
}

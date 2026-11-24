package net.prototux.aare.obdapp

import net.prototux.aare.obd.ObdReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ObdAppTest {
    private val petrol = CarSpec(displacementCc = 1400)

    @Test fun fuelRateWinsWhenTheCarReportsIt() {
        val r = ObdReadings(rpm = 2000f, speed = 90f, fuelRate = 6f, maf = 50f)
        assertEquals(6f, FuelMath.litersPerHour(r, petrol)!!, 0.001f)
        assertEquals(15f, FuelMath.kmPerLiter(r, petrol)!!, 0.01f)
    }

    @Test fun mafPetrol() {
        // 14.7 g of air per g of petrol, 745 g per litre: 10 g/s of air = 3.29 L/h.
        val r = ObdReadings(rpm = 2500f, speed = 100f, maf = 10f, load = 40f)
        assertEquals(3.287f, FuelMath.litersPerHour(r, petrol)!!, 0.01f)
    }

    @Test fun ethanolNeedsMoreFuel() {
        val r = ObdReadings(rpm = 2500f, speed = 100f, maf = 10f, load = 40f)
        val e100 = FuelMath.litersPerHour(r.copy(ethanol = 100f), petrol)!!
        assertTrue("$e100", e100 > 1.5f * FuelMath.litersPerHour(r, petrol)!!)
        // Without a sensor, the setting stands in for it.
        assertEquals(e100, FuelMath.litersPerHour(r, petrol.copy(ethanolPercent = 100))!!, 0.001f)
    }

    @Test fun speedDensityWithoutMaf() {
        // 1.4 L at 2400 rpm, 60 kPa, 30 °C: 0.69 kg/m³ × 1.4 L × 0.85 × 20/s = 16.4 g/s of air.
        val r = ObdReadings(rpm = 2400f, speed = 80f, manifoldKpa = 60f, intakeAir = 30f, load = 35f)
        val lh = FuelMath.litersPerHour(r, petrol)!!
        assertEquals(16.4f / 14.7f * 3.6f / 0.745f, lh, 0.15f)
        // A bigger engine burns more at the same pressure.
        assertTrue(FuelMath.litersPerHour(r, petrol.copy(displacementCc = 2000))!! > lh)
    }

    @Test fun coastingUsesNoFuelAndDieselNeedsTheRate() {
        assertEquals(0f, FuelMath.litersPerHour(ObdReadings(rpm = 2000f, speed = 80f, load = 0f, maf = 3f), petrol)!!, 0f)
        assertEquals(FuelMath.MAX_KM_PER_L, FuelMath.kmPerLiter(ObdReadings(rpm = 2000f, speed = 80f, load = 0f, maf = 3f), petrol)!!, 0f)
        assertNull(FuelMath.litersPerHour(ObdReadings(rpm = 2000f, speed = 80f, maf = 20f), CarSpec(fuel = ObdAppSettings.Fuel.DIESEL)))
        assertNull(FuelMath.kmPerLiter(ObdReadings(rpm = 900f, speed = 0f, maf = 2f), petrol)) // standing
    }

    @Test fun learnsGearsOfAnyCar() {
        // Five gears, km/h per 1000 rpm; drive a while in each, with a bit of noise and shifts in between.
        val ratios = listOf(7.5f, 13f, 19f, 25f, 31f)
        val g = GearLearner()
        repeat(2) {
            for (ratio in ratios) {
                var rpm = 1500f
                repeat(60) { i ->
                    rpm += 25f
                    val noise = if (i % 2 == 0) 1.005f else 0.995f
                    g.feed(rpm * ratio / 1000f * noise, rpm)
                }
                g.feed(5f, 900f) // clutch: lost
            }
        }
        assertEquals(5, g.gears.size)
        assertEquals(3, g.feed(19f * 3f, 3000f))
        assertEquals(1, g.feed(7.5f * 2f, 2000f))
        assertNull(g.gearOf(16f)) // between gears: slipping or clutch down
        // Kept between drives.
        assertEquals(5, GearLearner(g.save()).gears.size)
    }

    @Test fun gearsFromTheRealDrive() {
        // What the learner kept after 5.6 km in the Polo (automatic) on 19/11: one gear in two
        // groups (22.0 and 22.6), first gear seen only 8 times, converter slip in between.
        val saved = "7.016:8;30.450:86;19.991:6;13.892:128;22.024:67;22.558:162;9.798:6;11.113:11;12.363:15;18.841:8;17.901:4"
        val g = GearLearner(saved)
        assertEquals(4, g.gears.size)
        assertEquals(1, g.gearOf(7.0f))
        assertEquals(2, g.gearOf(13.9f))
        assertEquals(3, g.gearOf(22.4f))
        assertEquals(4, g.gearOf(30.5f))
        assertNull(g.gearOf(11.1f))
    }

    @Test fun tripSumsDistanceAndFuel() {
        val t = Trip()
        // One hour at 60 km/h burning 4 L/h: 60 km, 4 L, 15 km/L.
        val r = ObdReadings(rpm = 2000f, speed = 60f, fuelRate = 4f, fuel = 50f)
        var ms = 0L
        repeat(3601) { t.feed(r, petrol, null, ms); ms += 1000 }
        assertEquals(60f, t.km, 0.1f)
        assertEquals(4f, t.liters, 0.01f)
        assertEquals(15f, t.average!!, 0.05f)
        // Half a 50 L tank at 15 km/L.
        assertEquals(375f, t.rangeKm(50f, 50, 0f)!!, 1f)
    }

    @Test fun tripIgnoresGapsAndHoldsThePeak() {
        val t = Trip()
        val r = ObdReadings(speed = 100f)
        t.feed(r, petrol, 0.5f, 0)
        t.feed(r, petrol, 1.2f, 1_000)
        t.feed(r, petrol, 0.3f, 600_000) // the adapter dropped for 10 min: counts as 5 s
        assertEquals(100f * 6f / 3600f, t.km, 0.001f)
        // The peak held 1.2, but more than 8 s passed: it follows the value again.
        assertEquals(0.3f, t.peak!!, 0f)
        t.feed(r, petrol, 0.9f, 601_000)
        assertEquals(0.9f, t.peak!!, 0f)
        t.feed(r, petrol, 0.4f, 602_000)
        assertEquals(0.9f, t.peak!!, 0f) // held
    }

    @Test fun historyKeepsRecords() {
        val f = File.createTempFile("obd", ".tsv"); f.delete()
        val h = ObdHistory(f)
        h.add(ObdHistory.Entry(1, ObdHistory.Kind.ZERO_100, 9.1f))
        h.add(ObdHistory.Entry(2, ObdHistory.Kind.ZERO_100, 8.7f))
        h.add(ObdHistory.Entry(3, ObdHistory.Kind.DYNO, 120f, 5500f))
        h.add(ObdHistory.Entry(4, ObdHistory.Kind.DYNO, 131f, 5800f))
        h.add(ObdHistory.Entry(5, ObdHistory.Kind.TRIP, 12f, 14f))
        val read = h.read()
        assertEquals(5, read.size)
        val best = ObdHistory.records(read)
        assertEquals(8.7f, best[ObdHistory.Kind.ZERO_100]!!.value, 0f)
        assertEquals(131f, best[ObdHistory.Kind.DYNO]!!.value, 0f)
        assertNull(best[ObdHistory.Kind.TRIP])
        h.clear()
        assertEquals(0, h.read().size)
        assertNotNull(h)
    }
}

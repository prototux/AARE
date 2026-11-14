package net.prototux.aare.car

import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import net.prototux.aare.host.CarHardwareResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarDataTest {

    @Test
    fun neededIsTheUnionOfWhatEachClientNeeds() {
        val a = Any()
        val b = Any()
        CarData.need(a, setOf(SensorType.SENSOR_SPEED, SensorType.SENSOR_GEAR))
        CarData.need(b, setOf(SensorType.SENSOR_SPEED, SensorType.SENSOR_NIGHT_MODE))
        assertEquals(setOf(SensorType.SENSOR_SPEED, SensorType.SENSOR_GEAR, SensorType.SENSOR_NIGHT_MODE), CarData.needed.value)

        // A sensor stays needed while one client still needs it.
        CarData.release(a)
        assertEquals(setOf(SensorType.SENSOR_SPEED, SensorType.SENSOR_NIGHT_MODE), CarData.needed.value)
        // A new set replaces the client's old one.
        CarData.need(b, setOf(SensorType.SENSOR_FUEL))
        assertEquals(setOf(SensorType.SENSOR_FUEL), CarData.needed.value)
        CarData.release(b)
        assertTrue(CarData.needed.value.isEmpty())
    }

    @Test
    fun hardwareSubscriptionsMapToTheirSensors() {
        assertEquals(
            setOf(SensorType.SENSOR_SPEED, SensorType.SENSOR_COMPASS, SensorType.SENSOR_LOCATION),
            CarHardwareResults.sensorsFor(listOf(CarHardwareResults.SPEED, CarHardwareResults.COMPASS, CarHardwareResults.CAR_LOCATION)),
        )
        // Model and energy profile come from service discovery: no sensor to ask for.
        assertTrue(CarHardwareResults.sensorsFor(listOf(CarHardwareResults.MODEL, CarHardwareResults.ENERGY_PROFILE)).isEmpty())
    }

    @Test
    fun energyModelChargeIsStoredOverCapacity() {
        assertEquals(50f, CarData.EnergyModel(30_000, 60_000, null, null, null).batteryPercent!!, 0.01f)
        assertNull(CarData.EnergyModel(30_000, null, null, null, null).batteryPercent)
        assertNull(CarData.EnergyModel(30_000, 0, null, null, null).batteryPercent)
    }
}

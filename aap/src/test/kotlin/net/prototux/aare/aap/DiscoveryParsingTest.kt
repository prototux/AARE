package net.prototux.aare.aap

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.navigationstatus.NavigationStatusServiceOuterClass.NavigationStatusService
import aap_protobuf.service.radio.RadioServiceOuterClass.RadioService
import aap_protobuf.service.radio.message.RadioPropertiesOuterClass.RadioProperties
import aap_protobuf.service.sensorsource.SensorSourceServiceOuterClass.SensorSourceService
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorOuterClass.Sensor
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import net.prototux.aare.aap.channels.NavigationStatusChannel
import net.prototux.aare.aap.channels.SensorChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Service discovery from head units newer than our protos must still parse. */
class DiscoveryParsingTest {

    @Test
    fun unknownEnumValuesDoNotFailServiceDiscovery() {
        // From raw bytes: the builders can't hold enum values this build doesn't know.
        val futureSensor = Sensor.parseFrom(byteArrayOf(0x08, 99)) // sensor_type 99
        val futureCluster = NavigationStatusService.parseFrom(byteArrayOf(0x08, 100, 0x10, 9)) // 100 ms, type 9
        val futureRadio = RadioProperties.parseFrom(byteArrayOf(0x08, 1, 0x10, 42, 0x28, 1)) // id 1, type 42, spacing 1
        val wire = ServiceDiscoveryResponse.newBuilder()
            .addChannels(Service.newBuilder().setId(7).setSensorSourceService(SensorSourceService.newBuilder()
                .addSensors(Sensor.newBuilder().setSensorType(SensorType.SENSOR_DRIVING_STATUS_DATA))
                .addSensors(futureSensor)))
            .addChannels(Service.newBuilder().setId(8).setNavigationStatusService(futureCluster))
            .addChannels(Service.newBuilder().setId(10).setRadioService(RadioService.newBuilder().addRadioProperties(futureRadio)))
            .build().toByteArray()

        val resp = ServiceDiscoveryResponse.parseFrom(wire)

        // The unknown sensor is skipped, not read back as the default (LOCATION).
        val sensors = SensorChannel(resp.getChannels(0), object : SensorChannel.Listener {
            override fun onSensors(batch: SensorBatch) {}
        })
        assertEquals(listOf(SensorType.SENSOR_DRIVING_STATUS_DATA), sensors.available)
        // An unknown cluster type is not taken for the default (IMAGE).
        val nav = NavigationStatusChannel(resp.getChannels(1))
        assertFalse(nav.wantsImages)
        assertEquals(100, nav.minIntervalMs)
    }

    @Test
    fun evAndTrailerSensorTypesAreKnown() {
        assertEquals(SensorType.SENSOR_VEHICLE_ENERGY_MODEL_DATA, Sensor.parseFrom(byteArrayOf(0x08, 23)).sensorType)
        assertEquals(SensorType.SENSOR_RAW_EV_TRIP_SETTINGS, Sensor.parseFrom(byteArrayOf(0x08, 26)).sensorType)
    }
}

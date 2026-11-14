package net.prototux.aare.aap

import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest
import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiElement
import aap_protobuf.service.media.sink.MediaMessageIdOuterClass.MediaMessageId
import aap_protobuf.service.navigationstatus.NavigationStatusMessageIdOuterClass.NavigationStatusMessageId
import aap_protobuf.service.radio.RadioServiceOuterClass.RadioBand
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.VehicleEnergyModelDataOuterClass.VehicleEnergyModelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field numbers taken from the protocol's schemas: hand-encoded messages as a head unit would write or
 * read them must land in the right fields.
 */
class Aa18SchemaTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun carControlServiceInDiscovery() {
        // id 5; field 15 { car_properties { property_id 42, area_ids { seats [1] (packed) } } }
        val s = Service.parseFrom(bytes(0x08, 5, 0x7a, 9, 0x0a, 7, 0x08, 42, 0x22, 3, 0x12, 1, 1))
        assertTrue(s.hasCarControlService())
        val property = s.carControlService.getCarProperties(0)
        assertEquals(42, property.propertyId)
        assertEquals(listOf(1), property.getAreaIds(0).seatsList)
    }

    @Test
    fun radioConfigurationInDiscovery() {
        // id 6; field 7 { field 2 { band_configs { bands AM, FM; hide_empty_group } } }
        val s = Service.parseFrom(bytes(0x08, 6, 0x3a, 10, 0x12, 8, 0x0a, 6, 0x08, 1, 0x08, 2, 0x10, 1))
        val group = s.radioService.radioConfiguration.getBandConfigs(0)
        assertEquals(listOf(RadioBand.RADIO_BAND_AM, RadioBand.RADIO_BAND_FM), group.bandsList)
        assertTrue(group.hideEmptyGroup)
    }

    @Test
    fun uiConfigElementsAndBlendedUi() {
        // ui_elements CLOCK and an unknown 99; blended_ui_config { window_corner_radii { top_left 16 } }
        val ui = UiConfig.parseFrom(bytes(0x28, 1, 0x28, 99, 0x42, 4, 0x0a, 2, 0x08, 16))
        assertEquals(listOf(UiElement.UI_ELEMENT_CLOCK), ui.uiElementsList)
        assertEquals(16, ui.blendedUiConfig.windowCornerRadii.topLeft)
    }

    @Test
    fun startAckModeAndMediaOptions() {
        // session 1, config 0, field 3 = 2, media_options { enable_sync }
        val start = Start.parseFrom(bytes(0x08, 1, 0x10, 0, 0x18, 2, 0x22, 2, 0x10, 1))
        assertEquals(2, start.ackMode)
        assertTrue(start.mediaOptions.enableSync)
    }

    @Test
    fun sensorBatchEnergyModel() {
        // field 23 { vehicle_info { battery_level_wh { 100 }, hvac_status ON } }
        val batch = SensorBatch.parseFrom(bytes(0xba, 0x01, 8, 0x0a, 6, 0x1a, 2, 0x08, 100, 0x28, 2))
        val info = batch.getVehicleEnergyModelData(0).vehicleInfo
        assertEquals(100, info.batteryLevelWh.value)
        assertEquals(VehicleEnergyModelData.HvacStatus.ON_HVAC_STATUS, info.hvacStatus)
    }

    @Test
    fun inputReportIgnoresOldField2() {
        val report = InputReport.parseFrom(bytes(0x08, 1, 0x10, 5))
        assertEquals(1L, report.timestamp)
    }

    @Test
    fun wifiChannelsParseUnpackedAndPacked() {
        assertEquals(listOf(1, 6), WifiVersionRequest.parseFrom(bytes(0x20, 1, 0x20, 6)).supportedWifiChannelsList)
        assertEquals(listOf(1, 6), WifiVersionRequest.parseFrom(bytes(0x22, 2, 1, 6)).supportedWifiChannelsList)
    }

    @Test
    fun newMessageIds() {
        assertEquals(0x800C, MediaMessageId.MEDIA_MESSAGE_ACTION_TAKEN_NOTIFICATION.number)
        assertEquals(0x8014, MediaMessageId.MEDIA_MESSAGE_MEDIA_OPTIONS.number)
        assertEquals(0x8015, MediaMessageId.MEDIA_MESSAGE_CRITICAL_UI_NOTIFICATION.number)
        assertEquals(0x8009, NavigationStatusMessageId.INSTRUMENT_CLUSTER_NAVIGATION_RAW_ROUTE_INFO.number)
    }
}

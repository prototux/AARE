package net.prototux.aare.car

import aap_protobuf.service.control.message.HeadUnitInfoOuterClass.HeadUnitInfo
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchesInVisiblePixelsTest {
    private fun response(make: String = "", headUnitMake: String = "", deprecatedMake: String = "") =
        ServiceDiscoveryResponse.newBuilder()
            .setHeadunitInfo(HeadUnitInfo.newBuilder().setMake(make).setHeadUnitMake(headUnitMake))
            .setMake(deprecatedMake)
            .buildPartial()

    @Test
    fun fordIsRecognisedInAnyMakeField() {
        // The Mach-E's own discovery response (#74): make "Ford", head unit "Ford SYNC 4.0".
        assertTrue(CarSession.touchesInVisiblePixels(response(make = "Ford", headUnitMake = "Ford")))
        assertTrue(CarSession.touchesInVisiblePixels(response(headUnitMake = " ford ")))
        assertTrue(CarSession.touchesInVisiblePixels(response(deprecatedMake = "FORD")))
    }

    @Test
    fun otherMakesKeepTheirDeclaredPanel() {
        assertFalse(CarSession.touchesInVisiblePixels(response()))
        assertFalse(CarSession.touchesInVisiblePixels(response(make = "Hyundai", headUnitMake = "Hyundai")))
    }
}

package net.prototux.aare.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UsbAccessoryMatchTest {
    @Test fun matchesTheModelOnlyAndExactly() {
        assertTrue(UsbAccessoryTransport.isAndroidAutoModel("Android Auto"))
        assertTrue(UsbAccessoryTransport.isAndroidAutoModel("Android Open Automotive Protocol"))
        // ignored by default
        assertFalse(UsbAccessoryTransport.isAndroidAutoModel("Android"))
        assertFalse(UsbAccessoryTransport.isAndroidAutoModel("android auto"))
        assertFalse(UsbAccessoryTransport.isAndroidAutoModel("Android Auto 2"))
        assertFalse(UsbAccessoryTransport.isAndroidAutoModel(null))
    }

    /** Android delivers USB_ACCESSORY_ATTACHED by the manifest filter: it must accept the same accessories. */
    @Test fun manifestFilterListsTheSameModelsWithoutManufacturer() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/accessory_filter.xml"))
        val entries = doc.getElementsByTagName("usb-accessory")
        val models = (0 until entries.length).map { entries.item(it).attributes }.map { attrs ->
            assertEquals("filter by model only", null, attrs.getNamedItem("manufacturer"))
            attrs.getNamedItem("model").nodeValue
        }
        assertEquals(UsbAccessoryTransport.MODELS, models.toSet())
    }
}

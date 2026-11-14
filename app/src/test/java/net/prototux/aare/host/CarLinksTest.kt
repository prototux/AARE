package net.prototux.aare.host

import net.prototux.aare.ui.car.CarAppKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLinksTest {
    private val claude = "com.anthropic.claude"

    private fun parse(path: String, query: Map<String, String> = emptyMap(), host: String? = CarLinks.HOST, caller: String? = claude) =
        CarLinks.parse(host, path, query, caller, claude)

    @Test
    fun aLinkNamesTheToolAndItsArguments() {
        val args = mapOf("destination" to "Berlin")
        assertEquals("car_navigate" to args, parse("/go/car_navigate", args))
        assertEquals("car_media" to mapOf("action" to "next"), parse("/go/car_media/", mapOf("action" to "next")))
    }

    @Test
    fun everyCarToolIsALink() {
        assertEquals("car_reply" to mapOf("conversation" to "Max", "message" to "hi"), parse("/go/car_reply", mapOf("conversation" to "Max", "message" to "hi")))
        assertEquals("car_messages" to emptyMap<String, String>(), parse("/go/car_messages"))
        assertNull(parse("/go/status"))
        assertNull(parse("/go/car_x/car_media"))
    }

    @Test
    fun aLinkCantOpenThePhoneScreen() {
        assertFalse(CarTools.openable(CarAppKind.PHONE_SCREEN, link = true))
        assertTrue(CarTools.openable(CarAppKind.PHONE_SCREEN, link = false))
        assertTrue(CarTools.openable(CarAppKind.MEDIA, link = true))
        assertFalse(CarTools.openable(CarAppKind.EXIT, link = false))
    }

    @Test
    fun onlyTheAssistantMaySendOne() {
        assertNull(parse("/go/car_media", mapOf("action" to "next"), caller = "com.android.chrome"))
        assertNull(parse("/go/car_media", mapOf("action" to "next"), caller = null))
        assertNull(CarLinks.parse(CarLinks.HOST, "/go/car_media", emptyMap(), null, null))
    }

    @Test
    fun otherAddressesAreNotLinks() {
        assertNull(parse("/AARE/"))
        assertNull(parse("/go/"))
        assertNull(parse("/other/go/car_media", mapOf("action" to "next")))
        assertNull(parse("/go/car_media", mapOf("action" to "next"), host = "example.com"))
        assertNull(parse("/go/x/../car_media", mapOf("action" to "next")))
    }
}

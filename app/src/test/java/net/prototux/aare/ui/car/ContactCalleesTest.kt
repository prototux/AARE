package net.prototux.aare.ui.car

import net.prototux.aare.car.VoipApp
import net.prototux.aare.car.VoipCalls
import net.prototux.aare.car.VoipTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactCalleesTest {
    private val phone = "vnd.android.cursor.item/phone_v2"
    private val whatsAppCall = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    private val whatsApp = VoipApp("com.whatsapp", "WhatsApp", null)
    private val apps = mapOf("com.whatsapp" to whatsApp)

    private val rows = listOf(
        ContactRow("Alice", phone, dataId = 1, rawContactId = 10, data1 = "06 12 34 56 78", typeLabel = "Mobile"),
        // the calling app's own copy of Alice: its phone number and its call row
        ContactRow("Alice", phone, dataId = 2, rawContactId = 20, data1 = "+33 6 12 34 56 78", typeLabel = "Mobile"),
        ContactRow("Alice", whatsAppCall, dataId = 3, rawContactId = 20, data1 = "33612345678@s.whatsapp.net"),
        ContactRow("Bob", phone, dataId = 4, rawContactId = 30, data1 = "0700000000", typeLabel = "Home"),
    )

    @Test
    fun phoneNumbersAsBefore() {
        val list = contactCallees(rows, emptyMap())
        assertEquals(listOf("Mobile · 06 12 34 56 78", "Mobile · +33 6 12 34 56 78", "Home · 0700000000"), list.map { it.detail })
        assertNull(list.firstNotNullOfOrNull { it.voip })
    }

    @Test
    fun aCallThroughTheAppShowsTheNumberOfItsCopyOfTheContact() {
        val voip = contactCallees(rows, apps).single { it.voip != null }
        assertEquals("Alice", voip.name)
        assertEquals("WhatsApp · +33 6 12 34 56 78", voip.detail)
        assertEquals(VoipTarget(3, whatsAppCall, whatsApp), voip.voip)
    }

    @Test
    fun withoutAPhoneRowTheAppsIdBeforeTheAt() {
        val alone = listOf(ContactRow("Carol", whatsAppCall, dataId = 5, rawContactId = 40, data1 = "447700900000@s.whatsapp.net"))
        assertEquals("WhatsApp · 447700900000", contactCallees(alone, apps).single().detail)
    }

    @Test
    fun appsThatCantTakeCallsAreLeftOut() {
        val other = rows + ContactRow("Dan", "vnd.android.cursor.item/vnd.example.call", dataId = 6, rawContactId = 50, data1 = "dan")
        assertEquals(3, contactCallees(other, emptyMap()).size)
        assertEquals(4, contactCallees(other, apps).size)
    }

    @Test
    fun duplicatesGoAndTheListStops() {
        val twice = rows + rows
        assertEquals(4, contactCallees(twice, apps).size)
        assertEquals(2, contactCallees(rows, apps, limit = 2).size)
    }

    @Test
    fun onlyListedAppsWithAPhoneAccount() {
        assertEquals(setOf("com.whatsapp"), VoipCalls.callingApps(listOf("com.whatsapp", "com.example.voip")))
        assertEquals(emptySet<String>(), VoipCalls.callingApps(listOf("com.example.voip")))
    }
}

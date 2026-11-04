package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Test

class CarToolsTest {
    private fun match(name: String, vararg titles: String) = CarTools.matches(titles.toList(), name) { it }

    @Test
    fun exactMatchWinsOverPartial() {
        assertEquals(listOf("Mom"), match("mom", "Mom ❤️", "Mom"))
        assertEquals(listOf("Mom"), match(" Mom ", "Mom", "Tom"))
    }

    @Test
    fun partialMatchOnlyWhenTheTitleContainsTheName() {
        assertEquals(listOf("Mom ❤️"), match("Mom", "Mom ❤️", "Dad"))
        // A short title inside a longer name is not the conversation the driver named.
        assertEquals(emptyList<String>(), match("Alice", "Al", "Mo"))
    }

    @Test
    fun blankNamesAndTitlesNeverMatch() {
        assertEquals(emptyList<String>(), match("", "Mom", ""))
        assertEquals(emptyList<String>(), match("   ", "Mom"))
        assertEquals(emptyList<String>(), match("Mom ❤️", "", " "))
    }

    @Test
    fun theAppTellsSameNamedConversationsApart() {
        val all = listOf("Mom" to "WhatsApp", "Mom" to "Messages", "Dad" to "WhatsApp")
        val moms = CarTools.matches(all, "Mom") { it.first }
        assertEquals(listOf("Mom" to "WhatsApp"), CarTools.inApp(moms, "whatsapp") { it.second })
        // No app named: every match stays, and the ambiguity is refused.
        assertEquals(moms, CarTools.inApp(moms, " ") { it.second })
    }

    @Test
    fun everyCandidateIsReturnedSoAmbiguityIsRefused() {
        assertEquals(listOf("Mom", "mom"), match("Mom", "Mom", "mom", "Mom ❤️"))
        assertEquals(listOf("Family", "Family trip"), match("fam", "Family", "Family trip", "Work"))
    }
}

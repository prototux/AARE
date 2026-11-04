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
    fun everyCandidateIsReturnedSoAmbiguityIsRefused() {
        assertEquals(listOf("Mom", "mom"), match("Mom", "Mom", "mom", "Mom ❤️"))
        assertEquals(listOf("Family", "Family trip"), match("fam", "Family", "Family trip", "Work"))
    }
}

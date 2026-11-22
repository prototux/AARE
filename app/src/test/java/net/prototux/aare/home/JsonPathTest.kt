package net.prototux.aare.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonPathTest {
    private val state = """{"entity_id":"sensor.garage","state":"21.0","attributes":{"temperature":21.50,"list":[3,{"a.b":true}],"none":null}}"""

    @Test
    fun picksNestedValues() {
        assertEquals("21.0", JsonPath.extract(state, "$.state"))
        assertEquals("21.5", JsonPath.extract(state, "$.attributes.temperature"))
        assertEquals("3", JsonPath.extract(state, "$.attributes.list[0]"))
        assertEquals("true", JsonPath.extract(state, "$.attributes.list[1]['a.b']"))
        assertEquals("true", JsonPath.extract(state, """$["attributes"]["list"][1]["a.b"]"""))
    }

    @Test
    fun theDollarIsOptional() {
        assertEquals("21.0", JsonPath.extract(state, "state"))
        assertEquals("21.5", JsonPath.extract(state, "attributes.temperature"))
    }

    @Test
    fun wholeNumbersLoseTheirDecimals() {
        assertEquals("21", JsonPath.extract("""{"t":21.0}""", "t"))
        assertEquals("-3", JsonPath.extract("""{"t":-3}""", "t"))
        assertEquals("0.25", JsonPath.extract("""{"t":0.250}""", "t"))
    }

    @Test
    fun aBlankPathShowsThePayloadItself() {
        assertEquals("ON", JsonPath.extract("  ON\n", ""))
        assertEquals("""{"a":1}""", JsonPath.extract("""{"a":1}""", " "))
    }

    @Test
    fun missingValuesAndBadPathsGiveNothing() {
        assertNull(JsonPath.extract(state, "$.nope"))
        assertNull(JsonPath.extract(state, "$.attributes.list[5]"))
        assertNull(JsonPath.extract(state, "$.attributes.none"))
        assertNull(JsonPath.extract(state, "$.state.deeper"))
        assertNull(JsonPath.extract(state, "$..state"))
        assertNull(JsonPath.extract(state, "$.attributes[x]"))
        assertNull(JsonPath.extract("not json {", "$.a"))
    }

    @Test
    fun objectsShowAsJsonAndLongTextIsCut() {
        assertEquals("""{"x":1}""", JsonPath.extract("""{"o":{"x":1}}""", "o"))
        assertEquals(JsonPath.MAX_TEXT, JsonPath.extract("""{"s":"${"a".repeat(500)}"}""", "s")!!.length)
    }
}

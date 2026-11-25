package net.prototux.aare.faces

import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FaceSpecTest {
    private val utc = TimeZone.getTimeZone("UTC")

    /** Wednesday 25 November 2026. */
    private fun at(hour: Int, minute: Int, second: Int = 0) =
        Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, 25, hour, minute, second) }.timeInMillis

    private fun data(hour: Int = 15, minute: Int = 30, second: Int = 45, hours24: Boolean = true) =
        FaceData(at(hour, minute, second), utc, Locale.ENGLISH, hours24)

    private fun card(layers: String, more: String = "") = """{"aareCard":1,"name":"Test","size":[400,240],$more"layers":[$layers]}"""

    private fun error(json: String): String = try {
        FaceSpec.parse(json)
        fail("parsed: $json")
        ""
    } catch (e: FaceException) {
        e.message!!
    }

    private fun text(template: String, d: FaceData = data(), zone: String? = null) = FaceTemplate.parse(template, "text").text(d, zone)

    @Test
    fun everyBundledDesignParsesWithoutWarnings() {
        val dir = File("src/main/assets/faces")
        assertEquals(FaceStore.BUNDLED.sorted(), dir.list()!!.map { it.removeSuffix(".json") }.sorted())
        for (id in FaceStore.BUNDLED) {
            val face = Face(id, File(dir, "$id.json").readText())
            assertNull("$id: ${face.error}", face.error)
            assertEquals(id, emptyList<String>(), face.spec!!.warnings)
        }
    }

    @Test
    fun errorsSayWhatAndWhere() {
        assertTrue(error("{").startsWith("not valid JSON"))
        assertTrue("aareCard" in error("""{"name":"x"}"""))
        assertTrue("newer" in error("""{"aareCard":2}"""))
        assertTrue(error(card("""{"type":"hand","length":10}""")).startsWith("layers[0].value is missing"))
        assertTrue(error(card("""{"type":"circle","r":1},{"type":"blob"}""")).startsWith("layers[1].type: unknown \"blob\""))
        assertTrue(error(card("""{"type":"circle","r":"a"}""")).startsWith("layers[0].r: expected a number"))
        assertTrue(error(card("""{"type":"circle","r":-1}""")).startsWith("layers[0].r"))
        assertTrue(error(card("""{"type":"circle","r":1,"color":"pink"}""")).startsWith("layers[0].color: unknown colour"))
        assertTrue(error(card("""{"type":"text","text":"{nope}"}""")).contains("unknown {nope}"))
        assertTrue(error(card("""{"type":"text","text":"{time"}""")).contains("without its }"))
        assertTrue(error(card("""{"type":"text","text":"{time:bb}"}""")).contains("not a date format"))
        assertTrue(error(card("""{"type":"text","text":"x","timeZone":"Mars/Base"}""")).contains("timeZone"))
        assertTrue(error(card("""{"type":"text","text":"x","show":"rain"}""")).contains("show: unknown"))
        assertTrue(error(card("""{"type":"circle","r":1,"rotate":{"value":"speed","min":5,"max":5}}""")).contains("the same"))
    }

    @Test
    fun limitsAreEnforced() {
        assertTrue("layers" in error(card(List(FaceSpec.MAX_LAYERS + 1) { """{"type":"circle","r":1}""" }.joinToString(","))))
        assertTrue("larger" in error(" ".repeat(FaceSpec.MAX_BYTES + 1)))
        // Infinite and huge numbers would break the drawing.
        assertTrue("out of range" in error(card("""{"type":"circle","r":1,"x":1e30}""")))
    }

    @Test
    fun unknownKeysAreWarningsAndCommentsAreNot() {
        val spec = FaceSpec.parse(card("""{"type":"circle","r":1,"colour":"primary","_note":"hi"}""", "\"\$schema\":\"x\","))
        assertEquals(listOf("layers[0].colour: not a key of this, ignored"), spec.warnings)
    }

    @Test
    fun aCardOnlyNeedsWhatItShows() {
        assertEquals(emptySet<FaceNeed>(), FaceSpec.parse(card("""{"type":"text","text":"{time} {date:d MMM}"}""")).needs)
        assertEquals(setOf(FaceNeed.SECONDS), FaceSpec.parse(card("""{"type":"text","text":"{time:HH:mm:ss}"}""")).needs)
        assertEquals(emptySet<FaceNeed>(), FaceSpec.parse(card("""{"type":"text","text":"{time:HH 'hours'}"}""")).needs)
        assertEquals(setOf(FaceNeed.WEATHER, FaceNeed.CAR),
            FaceSpec.parse(card("""{"type":"icon","icon":"weather"},{"type":"arc","r":5,"value":"speed"}""")).needs)
        assertEquals(setOf(FaceNeed.CALENDAR), FaceSpec.parse(card("""{"type":"text","text":"x","show":"!event"}""")).needs)
    }

    @Test
    fun theShapeOfTheCardPicksTheLayout() {
        val variant = """{"size":[10,10],"layers":[]}"""
        val spec = FaceSpec.parse(card("", "\"strip\":$variant,\"tall\":$variant,"))
        assertSame(spec.base, spec.layoutFor(400f, 240f))
        assertSame(spec.strip, spec.layoutFor(500f, 112f))
        assertSame(spec.tall, spec.layoutFor(300f, 420f))
        val plain = FaceSpec.parse(card(""))
        assertSame(plain.base, plain.layoutFor(500f, 100f))
    }

    @Test
    fun valuesMapOntoAngles() {
        fun angle(json: String, d: FaceData) = (FaceSpec.parse(card("""{"type":"hand","length":10,"value":$json}""")).base.layers[0] as FaceLayer.Hand).value.at(d)
        assertEquals(105f, angle("\"hour\"", data(15, 30, 0))!!, 0.01f)
        assertEquals(180f, angle("\"minute\"", data(15, 30, 0))!!, 0.01f)
        assertEquals(270f, angle("\"second\"", data())!!, 0.01f)
        val gauge = """{"value":"speed","max":200,"from":-120,"to":120}"""
        assertEquals(0f, angle(gauge, data().copy(car = FaceData.Car(kmh = 100f)))!!, 0.01f)
        assertEquals(120f, angle(gauge, data().copy(car = FaceData.Car(kmh = 260f)))!!, 0.01f)
        assertNull(angle(gauge, data()))
    }

    @Test
    fun textsFillIn() {
        assertEquals("15:30", text("{time}"))
        assertEquals("3:30 PM", text("{time} {ampm}", data(hours24 = false)))
        assertEquals("15|30|45", text("{hour}|{minute}|{second}"))
        assertEquals("Wednesday 25 November", text("{date}"))
        assertEquals("25/11/26", text("{date:dd/MM/yy}"))
        assertEquals("16:30", text("{time}", zone = "Europe/Madrid"))
        assertEquals("–", text("{temp}"))
        assertEquals("21° {x}", text("{temp} {{x}}", data().copy(temperature = 21)))
        assertEquals("50", text("{speedMph}", data().copy(car = FaceData.Car(kmh = 80f))))
    }

    @Test
    fun conditionsHideLayers() {
        fun shows(show: String, d: FaceData) = d.shows(FaceSpec.parse(card("""{"type":"circle","r":1,"show":$show}""")).base.layers[0])
        assertTrue(shows("\"!weather\"", data()))
        assertFalse(shows("\"weather\"", data()))
        assertTrue(shows("[\"pm\",\"night\"]", data()))
        assertFalse(shows("[\"am\",\"night\"]", data()))
    }

    @Test
    fun colours() {
        fun colour(c: String) = FaceSpec.parse(card("""{"type":"circle","r":1,"color":$c}""")).base.layers[0].common.paint
        assertEquals(FaceColor.Fixed(0xffaabbcc.toInt()), colour("\"#abc\""))
        assertEquals(FaceColor.Fixed(0x80102030.toInt()), colour("\"#80102030\""))
        assertEquals(FaceColor.Theme("primary", 0.4f), colour("\"primary/40\""))
        assertEquals(FaceColor.DayNight(FaceColor.Fixed(-1), FaceColor.Theme("onSurface", 1f)), colour("""{"day":"#fff","night":"onSurface"}"""))
        assertEquals(2, (colour("""{"gradient":["#fff","#000"]}""") as FaceGradient).colors.size)
    }

    @Test
    fun base64IsReadByHand() {
        assertEquals("Hello", String(decodeBase64("SGVs\nbG8=")!!))
        assertNull(decodeBase64("SGV*"))
    }
}

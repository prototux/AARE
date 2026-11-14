package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching a head unit against a vehicles.json rule. */
class VehicleOverridesTest {
    private fun rule(make: String? = null, model: String? = null, name: String? = null) =
        VehicleRule(make, model, name, VehicleOverride())

    @Test fun matchesByMakeSubstringCaseInsensitive() =
        assertTrue(rule(make = "ford").matches("Ford Motor Company", "Focus", "SYNC 3"))

    @Test fun everySetFieldMustMatch() {
        assertTrue(rule(make = "Ford", name = "SYNC").matches("Ford", "Focus", "SYNC 3"))
        assertFalse(rule(make = "Ford", name = "MMI").matches("Ford", "Focus", "SYNC 3"))
    }

    @Test fun unsetFieldsAreIgnored() =
        assertTrue(rule(model = "Mach-E").matches("Ford", "Mustang Mach-E", "SYNC 4"))

    @Test fun anEmptyRuleNeverMatches() =
        assertFalse(rule().matches("Ford", "Focus", "SYNC"))

    @Test fun aMismatchFails() =
        assertFalse(rule(make = "Audi").matches("Ford", "Focus", "SYNC"))

    @Test fun servicesAreNamedAsTheLogWritesThem() {
        val o = VehicleOverride(skipServices = setOf("vendor", "audio:AUDIO_STREAM_GUIDANCE"), openServices = setOf("WIFI"))
        assertTrue(o.skips("vendor"))
        assertTrue(o.skips("audio:AUDIO_STREAM_GUIDANCE"))
        assertFalse(o.skips("audio:AUDIO_STREAM_MEDIA"))
        assertTrue(o.opens("wifi"))
        assertFalse(o.opens("radio"))
    }

    @Test fun aFamilyNameCoversEveryServiceOfIt() {
        assertTrue(VehicleOverride.matchesKind("audio", "audio:AUDIO_STREAM_MEDIA"))
        assertFalse(VehicleOverride.matchesKind("audio", "video"))
        assertFalse(VehicleOverride.matchesKind("vend", "vendor"))
    }

    @Test fun theUsersRuleWinsFieldByFieldOverTheBuiltInOne() {
        val builtIn = listOf(VehicleRule("Volkswagen", null, "MIB Regio", VehicleOverride(micSetup = false, dpi = 140)))
        val user = listOf(VehicleRule(null, null, "MIB Regio", VehicleOverride(dpi = 160)))
        val o = VehicleOverrides.resolve(user, builtIn, "Volkswagen", "VW_7_A0_HATCHBACK", "Volkswagen VW_7_A0_HATCHBACK Visteon MIB Regio")!!
        assertEquals(160, o.dpi)
        assertEquals(false, o.micSetup)
    }

    @Test fun eitherFileAloneApplies() {
        val rule = listOf(VehicleRule("Ford", null, null, VehicleOverride(maxHeight = 720)))
        assertEquals(720, VehicleOverrides.resolve(rule, emptyList(), "Ford", "Focus", "SYNC 3")!!.maxHeight)
        assertEquals(720, VehicleOverrides.resolve(emptyList(), rule, "Ford", "Focus", "SYNC 3")!!.maxHeight)
        assertEquals(null, VehicleOverrides.resolve(emptyList(), rule, "Audi", "A3", "MMI"))
    }

    @Test fun quirksAreParsed() {
        val o = VehicleOverrides.parse("""{"rules":[{"match":{"make":"VW"},"apply":{"micSetup":false,"skipServices":["vendor"],"openServices":["wifi"," "]}}]}""").single().override
        assertEquals(false, o.micSetup)
        assertEquals(setOf("vendor"), o.skipServices)
        assertEquals(setOf("wifi"), o.openServices)
        assertEquals(null, o.dpi)
    }

    @Test fun aMalformedRuleIsSkipped() =
        assertEquals(1, VehicleOverrides.parse("""{"rules":[42, {"match":{"make":"Ford"},"apply":{"dpi":140}}]}""").size)

    // ---- match keys beyond make/model/name

    private fun hu(
        make: String = "Ford", model: String = "Focus", name: String = "Ford Focus Harman SYNC", year: String = "2016",
        headUnitMake: String = "Harman", headUnitModel: String = "SYNC 3", headUnitBuild: String = "B42", protocol: Pair<Int, Int>? = 1 to 3,
    ) = HeadUnitIdentity(make, model, name, year, headUnitMake, headUnitModel, headUnitBuild, protocol)

    private fun matching(json: String, id: HeadUnitIdentity) =
        VehicleOverrides.parse("""{"rules":[{"match":$json,"apply":{"dpi":1}}]}""").single().matches(id)

    @Test fun yearAndHeadUnitFieldsAreWholeValuesCaseInsensitive() {
        assertTrue(matching("""{"year":"2016","headUnitMake":"HARMAN","headUnitModel":"sync 3","headUnitBuild":"b42"}""", hu()))
        assertFalse(matching("""{"headUnitMake":"Harm"}""", hu()))
        assertFalse(matching("""{"year":"2017"}""", hu()))
        assertFalse(matching("""{"headUnitBuild":"B43"}""", hu()))
    }

    @Test fun aListIsAlternatives() {
        assertTrue(matching("""{"headUnitModel":["MGU21","SYNC 3"]}""", hu()))
        assertTrue(matching("""{"make":["Audi","Ford"]}""", hu()))
        assertFalse(matching("""{"make":["Audi","BMW"]}""", hu()))
        // Blank alternatives mean "any", like a blank string.
        assertFalse(matching("""{"make":[""," "]}""", hu()))
    }

    @Test fun exactMakesMakeAndModelWholeValues() {
        assertTrue(matching("""{"make":"For"}""", hu()))
        assertFalse(matching("""{"make":"For","exact":true}""", hu()))
        assertTrue(matching("""{"make":"FORD","exact":true}""", hu()))
    }

    @Test fun protocolExactlyOrWithinBounds() {
        assertTrue(matching("""{"protocol":"1.3"}""", hu()))
        assertFalse(matching("""{"protocol":"1.4"}""", hu()))
        assertTrue(matching("""{"protocol":["1.0","1.3"]}""", hu()))
        assertTrue(matching("""{"minProtocol":"1.2","maxProtocol":"1.3"}""", hu()))
        assertFalse(matching("""{"maxProtocol":"1.1"}""", hu()))
        assertTrue(matching("""{"maxProtocol":"1.1"}""", hu(protocol = 1 to 1)))
        assertTrue(matching("""{"minProtocol":"1.4"}""", hu(protocol = 6 to 1)))
        // Minor versions compare as numbers: 1.10 is after 1.9.
        assertTrue(matching("""{"minProtocol":"1.9"}""", hu(protocol = 1 to 10)))
    }

    @Test fun aProtocolRuleNeverMatchesAnUnknownVersion() =
        assertFalse(matching("""{"make":"Ford","minProtocol":"1.0"}""", hu(protocol = null)))

    @Test fun aMalformedProtocolSkipsTheRule() =
        assertEquals(1, VehicleOverrides.parse("""{"rules":[{"match":{"make":"Ford","protocol":"1"},"apply":{"dpi":1}},
            {"match":{"make":"Ford"},"apply":{"dpi":2}}]}""").size)

    /** A file written for the first vehicles.json format keeps its meaning. */
    @Test fun anOldFileStillWorks() {
        val old = """{"rules":[
            {"match":{"make":"ford","model":"","name":"SYNC"},"apply":{"dpi":140,"maxHeight":720}},
            {"match":{"make":"Ford"},"apply":{"dpi":160,"widthMargin":10}},
            {"match":{"make":"","model":"","name":""},"apply":{"dpi":120}}]}"""
        val rules = VehicleOverrides.parse(old)
        assertEquals(3, rules.size)
        // First match only, in the user's file: the second Ford rule doesn't add its margin.
        val o = VehicleOverrides.resolve(rules, emptyList(), "Ford Motor Company", "Focus", "Ford Focus SYNC 3")!!
        assertEquals(140, o.dpi)
        assertEquals(720, o.maxHeight)
        assertEquals(null, o.widthMargin)
        // A rule with only blank match fields still never matches.
        assertFalse(rules[2].matches("Audi", "A3", "MMI"))
        // Substrings, case-insensitive, whatever the new keys of the head unit say.
        assertTrue(rules[0].matches(hu(make = "Ford Motor Company", name = "Ford Focus SYNC 3", protocol = null)))
    }

    @Test fun everyMatchingBuiltInRuleApplies() {
        val builtIn = listOf(
            VehicleRule(VehicleMatch(headUnitMake = listOf("Harman")), VehicleOverride(micSetup = false, dpi = 140)),
            VehicleRule(VehicleMatch(make = listOf("Ford")), VehicleOverride(dpi = 120, maxHeight = 720)),
            VehicleRule(VehicleMatch(make = listOf("Audi")), VehicleOverride(prefer60fps = true)),
        )
        val o = VehicleOverrides.resolve(emptyList(), builtIn, hu())!!
        assertEquals(140, o.dpi) // the earlier rule wins
        assertEquals(false, o.micSetup)
        assertEquals(720, o.maxHeight)
        assertEquals(null, o.prefer60fps)
        val mine = VehicleOverrides.resolve(listOf(VehicleRule("Ford", null, null, VehicleOverride(maxHeight = 1080))), builtIn, hu())!!
        assertEquals(1080, mine.maxHeight)
        assertEquals(140, mine.dpi)
    }

    @Test fun theNewKeysAreParsed() {
        val o = VehicleOverrides.parse("""{"rules":[{"match":{"make":"X"},"apply":{"videoResolutions":["800x480"],"pixelAspect":1.1515,
            "singleChannelCapture":true,"forceElectric":true,"wifiMediaStartSilence":8,"wifiGuidanceStartSilence":4,
            "mediaOnGainTransient":true,"pauseOnGuidanceOnly":false,"wireless":false,"exitToCar":false,"exitLabel":"make"}}]}""").single().override
        assertEquals(setOf("800x480"), o.videoResolutions)
        assertEquals(1.1515f, o.pixelAspect!!, 1e-6f)
        assertEquals(true, o.singleChannelCapture)
        assertEquals(true, o.forceElectric)
        assertEquals(8, o.wifiMediaStartSilence)
        assertEquals(4, o.wifiGuidanceStartSilence)
        assertEquals(true, o.mediaOnGainTransient)
        assertEquals(false, o.pauseOnGuidanceOnly)
        assertEquals(false, o.wireless)
        assertEquals(false, o.exitToCar)
        assertEquals("make", o.exitLabel)
        // over() keeps every new field.
        assertEquals(o, VehicleOverride().over(o))
    }

    /** The rules AARE ships: valid, and the VW Play's matches it as #166 reports it. */
    @Test fun theBuiltInRulesParse() {
        val rules = VehicleOverrides.parse(java.io.File("src/main/assets/vehicles.json").readText())
        val vwPlay = rules.single { it.matches("Volkswagen", "VW_7_A0_HATCHBACK", "Volkswagen VW_7_A0_HATCHBACK Visteon MIB Regio") }
        assertEquals(false, vwPlay.override.micSetup)
    }
}

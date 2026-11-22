package net.prototux.aare.car

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val o = VehicleOverrides.parse("""{"rules":[{"match":{"make":"VW"},"apply":{"micSetup":false,"dropOverflow":true,"sensorChannelFirst":true,"skipServices":["vendor"],"openServices":["wifi"," "]}}]}""").single().override
        assertEquals(false, o.micSetup)
        assertEquals(true, o.dropOverflow)
        assertEquals(true, o.sensorChannelFirst)
        assertEquals(setOf("vendor"), o.skipServices)
        assertEquals(setOf("wifi"), o.openServices)
        assertEquals(null, o.dpi)
        assertEquals(null, o.pairAgainWhenRunning)
        val again = VehicleOverrides.parse("""{"rules":[{"match":{"make":"VW"},"apply":{"pairAgainWhenRunning":true}}]}""").single().override
        assertEquals(true, again.pairAgainWhenRunning)
        assertEquals(null, VehicleOverrides.parse("""{"rules":[{"match":{"make":"VW"},"apply":{}}]}""").single().override.sensorChannelFirst)
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
            "reportedPixelAspect":true,"singleChannelCapture":true,"forceElectric":true,"wifiMediaStartSilence":8,"wifiGuidanceStartSilence":4,
            "mediaOnGainTransient":true,"pauseOnGuidanceOnly":false,"wireless":false,"exitToCar":false,"exitLabel":"make"}}]}""").single().override
        assertEquals(setOf("800x480"), o.videoResolutions)
        assertEquals(1.1515f, o.pixelAspect!!, 1e-6f)
        assertEquals(true, o.reportedPixelAspect)
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
        assertEquals(true, vwPlay.override.pairAgainWhenRunning)
    }

    private val builtIn by lazy { VehicleOverrides.parse(java.io.File("src/main/assets/vehicles.json").readText()) }
    /** With the "Built-in car fixes" setting on. */
    private fun shipped(id: HeadUnitIdentity) = VehicleOverrides.resolve(emptyList(), builtIn, id, builtInFixes = true)

    /** Built-in lists, as the file carries them. */
    @Test fun theBuiltInRulesCarryAndroidAutosLists() {
        // Pixel aspect only before protocol 1.2.
        val outlander = hu(make = "MITSUBISHI MOTORS", model = "PHEV4B00EU", year = "2016", headUnitMake = "", headUnitModel = "", protocol = 1 to 1)
        assertEquals(1.15f, shipped(outlander)!!.pixelAspect!!, 1e-6f)
        assertEquals(null, shipped(outlander.copy(protocol = 1 to 2))?.pixelAspect)
        assertEquals(null, shipped(outlander.copy(year = "2015"))?.pixelAspect)
        assertEquals(1.1515f, shipped(hu(make = "Pioneer", model = "Car Navigation - High", year = "2016", protocol = 1 to 0))!!.pixelAspect!!, 1e-6f)
        // 800x480 for Subaru's Harman units.
        assertEquals(setOf("800x480"), shipped(hu(make = "Subaru", headUnitMake = "HARMAN"))!!.videoResolutions)
        // Mixed navigation voice, by protocol version.
        assertEquals(true, shipped(hu(make = "KIA", headUnitModel = "avn_40", protocol = 1 to 0))!!.singleChannelCapture)
        assertEquals(null, shipped(hu(make = "KIA", headUnitModel = "avn_40", protocol = 1 to 1))?.singleChannelCapture)
        // EV fuel type.
        assertEquals(true, shipped(hu(make = "Hyundai", model = "NEev"))!!.forceElectric)
        // Start silence.
        assertEquals(8, shipped(hu(make = "BMW", headUnitMake = "BMW", headUnitModel = "MGU21"))!!.wifiMediaStartSilence)
        assertEquals(8, shipped(hu(headUnitMake = "HMG", headUnitModel = "connect_s"))!!.wifiGuidanceStartSilence)
        // Focus.
        assertEquals(true, shipped(hu(headUnitMake = "ZJ"))!!.mediaOnGainTransient)
        assertEquals(false, shipped(hu(make = "SYNC"))!!.pauseOnGuidanceOnly)
        // Wireless, and the SYNC 1.0 head unit gets both its rules.
        assertEquals(false, shipped(hu(headUnitMake = "ALPINE", headUnitModel = "iLX-705D"))!!.wireless)
        assertEquals(false, shipped(hu(year = "2021", headUnitMake = "Pioneer", headUnitModel = "9inch Display Audio"))!!.wireless)
        assertEquals(null, shipped(hu(year = "2019", headUnitMake = "Pioneer", headUnitModel = "9inch Display Audio"))?.wireless)
        shipped(hu(make = "SYNC", protocol = 1 to 0))!!.let {
            assertEquals(true, it.singleChannelCapture)
            assertEquals(false, it.pauseOnGuidanceOnly)
        }
        // Exit to car.
        assertEquals(false, shipped(hu(make = "Xiaomi"))!!.exitToCar)
        assertEquals("default", shipped(hu(make = "Chevrolet"))!!.exitLabel)
        assertEquals("make", shipped(hu(make = "Land Rover"))!!.exitLabel)
        // An ordinary car gets nothing but the mark that the fixes are on (for the Exit label).
        assertEquals(VehicleOverride(builtInFixes = true), shipped(hu()))
    }

    /** Off by default: a car gets only what it got before the car lists were shipped, and the audio defaults' exceptions. */
    @Test fun theBuiltInCarFixesAreOptIn() {
        fun default(id: HeadUnitIdentity) = VehicleOverrides.resolve(emptyList(), builtIn, id)
        assertEquals(null, default(hu(headUnitMake = "ALPINE", headUnitModel = "iLX-705D")))
        assertEquals(null, default(hu(make = "Xiaomi")))
        assertEquals(null, default(hu(make = "Subaru", headUnitMake = "HARMAN")))
        assertEquals(null, default(hu(make = "Hyundai", model = "NEev")))
        assertEquals(null, default(hu(make = "KIA", headUnitModel = "avn_40", protocol = 1 to 0)))
        assertEquals(null, default(hu(make = "MITSUBISHI MOTORS", model = "PHEV4B00EU", year = "2016", headUnitMake = "", headUnitModel = "", protocol = 1 to 1)))
        assertEquals(null, default(hu()))
        assertEquals(false, default(hu(make = "SYNC"))!!.pauseOnGuidanceOnly)
        assertEquals(8, default(hu(make = "BMW", headUnitMake = "BMW", headUnitModel = "MGU21"))!!.wifiMediaStartSilence)
        assertEquals(false, VehicleOverrides.resolve(emptyList(), builtIn, "Volkswagen", "VW_7_A0_HATCHBACK",
            "Volkswagen VW_7_A0_HATCHBACK Visteon MIB Regio")!!.micSetup)
    }

    /** A user rule turns the built-in car fixes on, or off, for its car, whatever the setting. */
    @Test fun aUserRuleDecidesTheCarFixesForItsCar() {
        val alpine = hu(headUnitMake = "ALPINE", headUnitModel = "iLX-705D")
        val on = VehicleOverrides.parse("""{"rules":[{"match":{"headUnitMake":"ALPINE"},"apply":{"builtInFixes":true}}]}""")
        val off = VehicleOverrides.parse("""{"rules":[{"match":{"headUnitMake":"ALPINE"},"apply":{"builtInFixes":false}}]}""")
        assertEquals(false, VehicleOverrides.resolve(on, builtIn, alpine)!!.wireless)
        assertEquals(null, VehicleOverrides.resolve(off, builtIn, alpine, builtInFixes = true)?.wireless)
        assertEquals(null, VehicleOverrides.resolve(off, builtIn, alpine, builtInFixes = true)?.builtInFixes)
        // Or sets the quirk itself.
        val mine = VehicleOverrides.parse("""{"rules":[{"match":{"headUnitMake":"ALPINE"},"apply":{"wireless":false}}]}""")
        assertEquals(false, VehicleOverrides.resolve(mine, builtIn, alpine)!!.wireless)
    }

    /** Only optIn rules wait for the setting; a user file's optIn means nothing. */
    @Test fun optInIsParsed() {
        val rules = VehicleOverrides.parse("""{"rules":[{"optIn":true,"match":{"make":"Ford"},"apply":{"dpi":1}},{"match":{"make":"Ford"},"apply":{"dpi":2}}]}""")
        assertEquals(listOf(true, false), rules.map { it.optIn })
        assertEquals(2, VehicleOverrides.resolve(emptyList(), rules, hu())!!.dpi)
        assertEquals(1, VehicleOverrides.resolve(emptyList(), rules, hu(), builtInFixes = true)!!.dpi)
        assertEquals(1, VehicleOverrides.resolve(rules, emptyList(), hu())!!.dpi)
    }

    /** A skipped rule's reason goes to the connection log: it never quotes the rule. */
    @Test fun aBadVersionIsntQuoted() {
        val e = runCatching { VehicleMatch.version("Ford 1") }.exceptionOrNull()
        assertTrue(e is RuleProblem)
        assertFalse(e!!.message!!.contains("Ford"))
    }

    // ---- the "Car quirks" settings page, over the per-car vehicles.json resolution

    @Test fun automaticKeepsTheRuleIncludingNull() {
        val rule = VehicleOverride(micSetup = false, dpi = 140, pixelAspect = 1.15f)
        assertEquals(rule, VehicleOverrides.effective(rule, QuirkSettings()))
        assertEquals(null, VehicleOverrides.effective(null, QuirkSettings()))
    }

    @Test fun onAndOffForceABooleanQuirkEitherWay() {
        // No rule at all: On/Off still force it, Automatic stays null (unset, AARE's own default decides).
        assertEquals(true, VehicleOverrides.effective(null, QuirkSettings(micSetup = QuirkChoice.ON))?.micSetup)
        assertEquals(false, VehicleOverrides.effective(null, QuirkSettings(micSetup = QuirkChoice.OFF))?.micSetup)
        assertEquals(null, VehicleOverrides.effective(null, QuirkSettings(micSetup = QuirkChoice.AUTOMATIC))?.micSetup)
        // A rule disagreeing with the setting: On/Off win.
        val rule = VehicleOverride(micSetup = false, mediaOnGainTransient = false)
        assertEquals(true, VehicleOverrides.effective(rule, QuirkSettings(micSetup = QuirkChoice.ON))?.micSetup)
        assertEquals(true, VehicleOverrides.effective(rule, QuirkSettings(mediaOnGainTransient = QuirkChoice.ON))?.mediaOnGainTransient)
        // Automatic keeps the rule's own value.
        assertEquals(false, VehicleOverrides.effective(rule, QuirkSettings())?.micSetup)
    }

    @Test fun offForcesTheNeutralValueForAMagnitudeQuirk() {
        // No single "on" value for a count or a list: Off disables it (no silence, no resolution
        // limit); On behaves like Automatic, passing the rule (or nothing) through.
        val rule = VehicleOverride(wifiMediaStartSilence = 8, videoResolutions = setOf("800x480"))
        assertEquals(0, VehicleOverrides.effective(rule, QuirkSettings(wifiMediaStartSilence = QuirkChoice.OFF))?.wifiMediaStartSilence)
        assertEquals(8, VehicleOverrides.effective(rule, QuirkSettings(wifiMediaStartSilence = QuirkChoice.ON))?.wifiMediaStartSilence)
        assertEquals(null, VehicleOverrides.effective(rule, QuirkSettings(videoResolutions = QuirkChoice.OFF))?.videoResolutions)
        assertEquals(setOf("800x480"), VehicleOverrides.effective(rule, QuirkSettings(videoResolutions = QuirkChoice.ON))?.videoResolutions)
        // No rule either: Off still forces the neutral value, not null.
        assertEquals(0, VehicleOverrides.effective(null, QuirkSettings(wifiMediaStartSilence = QuirkChoice.OFF))?.wifiMediaStartSilence)
    }

    @Test fun pixelAspectOnAsksForTheHeadUnitsReportedRatio() {
        // On turns reportedPixelAspect on; an explicit pixelAspect rule (the built-in file's older,
        // can't-report-it head units) still wins over it either way, so the field itself is unchanged.
        val rule = VehicleOverride(pixelAspect = 1.15f)
        assertEquals(1.15f, VehicleOverrides.effective(rule, QuirkSettings(pixelAspect = QuirkChoice.ON))?.pixelAspect)
        assertEquals(true, VehicleOverrides.effective(rule, QuirkSettings(pixelAspect = QuirkChoice.ON))?.reportedPixelAspect)
        assertEquals(null, VehicleOverrides.effective(rule, QuirkSettings())?.reportedPixelAspect)
        // No rule either: On still turns reportedPixelAspect on, so a head unit without a vehicles.json
        // match gets its reported ratio honoured.
        assertEquals(true, VehicleOverrides.effective(null, QuirkSettings(pixelAspect = QuirkChoice.ON))?.reportedPixelAspect)
        // Off forces no factor at all: square pixels, and reportedPixelAspect off, whatever the rule said.
        val reporting = VehicleOverride(reportedPixelAspect = true)
        assertEquals(1f, VehicleOverrides.effective(reporting, QuirkSettings(pixelAspect = QuirkChoice.OFF))?.pixelAspect)
        assertEquals(false, VehicleOverrides.effective(reporting, QuirkSettings(pixelAspect = QuirkChoice.OFF))?.reportedPixelAspect)
    }

    @Test fun wirelessAllowBlocksTheDenylist() {
        val denylisted = VehicleOverride(wireless = false)
        assertEquals(false, VehicleOverrides.effective(denylisted, QuirkSettings())?.wireless)
        assertEquals(true, VehicleOverrides.effective(denylisted, QuirkSettings(wireless = QuirkChoice.ON))?.wireless)
        assertEquals(false, VehicleOverrides.effective(null, QuirkSettings(wireless = QuirkChoice.OFF))?.wireless)
    }

    @Test fun unknownKeysAreUntouched() {
        val rule = VehicleOverride(dpi = 140, widthMargin = 10, heightMargin = 5, maxHeight = 720, prefer60fps = true,
            skipServices = setOf("vendor"), openServices = setOf("wifi"), exitLabel = "make")
        val forced = QuirkSettings(
            pixelAspect = QuirkChoice.OFF, videoResolutions = QuirkChoice.OFF, singleChannelCapture = QuirkChoice.ON,
            wifiMediaStartSilence = QuirkChoice.OFF, wifiGuidanceStartSilence = QuirkChoice.OFF, mediaOnGainTransient = QuirkChoice.ON,
            pauseOnGuidanceOnly = QuirkChoice.OFF, wireless = QuirkChoice.ON, forceElectric = QuirkChoice.ON,
            exitToCar = QuirkChoice.OFF, micSetup = QuirkChoice.OFF,
        )
        val o = VehicleOverrides.effective(rule, forced)!!
        assertEquals(140, o.dpi)
        assertEquals(10, o.widthMargin)
        assertEquals(5, o.heightMargin)
        assertEquals(720, o.maxHeight)
        assertEquals(true, o.prefer60fps)
        assertEquals(setOf("vendor"), o.skipServices)
        assertEquals(setOf("wifi"), o.openServices)
        assertEquals("make", o.exitLabel)
    }

    @Test fun nothingForcedAndNoRuleStaysNull() =
        // On for a quirk with no single "on" value, no vehicles.json match: still no override, as today.
        assertEquals(null, VehicleOverrides.effective(null, QuirkSettings(videoResolutions = QuirkChoice.ON)))

    /** A car's page choices are stored by name: every one comes back, and a new page starts all Automatic. */
    @Test fun quirkChoicesRoundTripByName() {
        val q = QuirkSettings(pixelAspect = QuirkChoice.ON, wireless = QuirkChoice.OFF, micSetup = QuirkChoice.OFF)
        val stored = q.byName().mapValues { it.value.pref }
        assertEquals(11, stored.size)
        assertEquals(q, QuirkSettings.of { QuirkChoice.of(stored[it]) })
        assertEquals(QuirkSettings(), QuirkSettings.of { QuirkChoice.of(null) })
    }

    /** Android's org.json quotes the whole input after the position: the problem keeps neither. */
    @Test fun anAndroidSyntaxErrorGivesLineAndColumnButNotTheFile() {
        val text = "{\"rules\":[\n{\"match\":"
        val end = VehicleOverrides.problem(JSONException("Unterminated object at character 23 of {\"rules\":[\n{\"match\":"), text)
        assertEquals(JsonProblem(2, 10, "Unterminated object"), end)
        assertFalse(end.toString().contains("match"))
        assertEquals(JsonProblem(2, 3, "Expected literal value"), VehicleOverrides.problem(JSONException("Expected literal value at character 13 of $text"), text))
    }

    @Test fun aReasonThatQuotesTheFileIsCut() {
        val p = VehicleOverrides.problem(JSONException("Expected ':' after Secret Car at character 15 of {\"Secret Car\" 1}"), "{\"Secret Car\" 1}")
        assertEquals(JsonProblem(1, 16, "Expected ':'"), p)
        assertFalse(p.toString().contains("Secret"))
    }

    /** The JVM's org.json (unit tests) writes the line and column itself. */
    @Test fun aJvmSyntaxErrorGivesLineAndColumnButNotTheFile() {
        val p = VehicleOverrides.problem("{\"rules\": [\n  {\"match\": {\"make\" \"Secret Car\"}}\n]}")!!
        assertEquals(JsonProblem(2, 21, "Expected a ':' after a key"), p)
        assertFalse(p.toString().contains("Secret"))
        assertFalse(p.toString().contains("match"))
    }

    @Test fun aTopLevelArrayIsNotAJsonObject() {
        val p = VehicleOverrides.problem(
            JSONException("Value [{\"make\":\"Secret Car\"}] of type org.json.JSONArray cannot be converted to JSONObject"),
            "[{\"make\":\"Secret Car\"}]",
        )
        assertEquals(JsonProblem(null, null, "not a JSON object"), p)
        assertFalse(p.toString().contains("Secret"))
    }

    @Test fun anUnknownMessageIsNotQuoted() {
        val p = VehicleOverrides.problem(JSONException("Something odd about Secret Car"), "Secret Car")
        assertEquals(JsonProblem(null, null, "not valid JSON"), p)
        assertFalse(p.toString().contains("Secret"))
    }

    @Test fun aValidFileHasNoProblem() =
        assertNull(VehicleOverrides.problem(VehicleOverrides.template()))
}

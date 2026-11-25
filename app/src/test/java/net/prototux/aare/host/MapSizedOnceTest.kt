package net.prototux.aare.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apps that size their map once per session get a new session on a new size (#413). */
class MapSizedOnceTest {
    @Test
    fun onlyTheListedAppsAreRestarted() {
        assertTrue(TemplateQuirks.mapSizedOnce("net.osmand.plus"))
        assertTrue(TemplateQuirks.mapSizedOnce("net.osmand"))
        assertFalse(TemplateQuirks.mapSizedOnce("app.comaps"))
        assertFalse(TemplateQuirks.mapSizedOnce("app.organicmaps"))
    }

    @Test
    fun aNewSizeOrDensityNeedsANewSession() {
        val card = SurfaceSize(715, 696, 160)
        assertFalse(mapNeedsNewSession(card, card.copy()))
        assertTrue(mapNeedsNewSession(card, SurfaceSize(1184, 720, 160)))
        assertTrue(mapNeedsNewSession(card, card.copy(dpi = 240)))
    }
}

package net.prototux.aare.host

import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.navigation.model.MapTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION") // MapTemplate
class MapHeaderRefreshTest {
    private fun header(title: String) = Header.Builder().setTitle(title).build()
    private fun map(title: String) = MapTemplate.Builder().setHeader(header(title)).setItemList(ItemList.Builder().build()).build()

    @Test
    fun sameTitleIsTheSameHeader() {
        assertTrue(sameHeaderTitle(header("Recent"), header("Recent")))
        assertTrue(sameHeaderTitle(null, null))
    }

    @Test
    fun aNewTitleOrAHeaderComingOrGoingIsNot() {
        assertFalse(sameHeaderTitle(header("Recent"), header("Favourites")))
        assertFalse(sameHeaderTitle(header("Recent"), null))
        assertFalse(sameHeaderTitle(null, header("Recent")))
    }

    @Test
    fun onlyMapTemplatesAreCompared() {
        assertTrue(keepsMapHeader(map("Recent"), map("Recent")))
        assertFalse(keepsMapHeader(map("Recent"), map("Favourites")))
        assertTrue(keepsMapHeader(null, map("Recent")))
        assertTrue(keepsMapHeader(map("Recent"), null))
    }

    @Test
    fun wazeFromItsListedVersionAndTheSamples() {
        assertFalse(TemplateQuirks.mapHeaderStartsScreen("com.waze", 1030422))
        assertTrue(TemplateQuirks.mapHeaderStartsScreen("com.waze", 1030423))
        assertFalse(TemplateQuirks.mapHeaderStartsScreen("com.waze", null))
        assertTrue(TemplateQuirks.mapHeaderStartsScreen("androidx.car.app.sample.showcase", 1))
        assertFalse(TemplateQuirks.mapHeaderStartsScreen("com.example.app", 1))
    }
}

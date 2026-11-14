package net.prototux.aare.aap.channels

import aap_protobuf.service.control.message.UpdateUiConfigRequestOuterClass.UpdateUiConfigRequest
import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.shared.message.UiThemeOuterClass.UiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UiConfigUpdateTest {
    private fun insets(top: Int, bottom: Int = 0) = Insets.newBuilder().setTop(top).setBottom(bottom).setLeft(0).setRight(0).build()
    private val current = UiConfig.newBuilder().setMargins(insets(10)).setContentInsets(insets(20)).build()
    private fun request(ui: UiConfig) = UpdateUiConfigRequest.newBuilder().setUiConfig(ui).build()

    @Test
    fun olderHeadUnitsSendOnlyWhatChanges() {
        val r = UiConfigUpdate.apply(current, request(UiConfig.newBuilder().setContentInsets(insets(30)).build()), headUnitAtLeast43 = false)
        assertEquals("margins kept", 10, r.margins.top)
        assertEquals("insets updated", 30, r.contentInsets.top)
    }

    @Test
    fun olderHeadUnitsMergeIntoNothingAtFirst() {
        val r = UiConfigUpdate.apply(null, request(UiConfig.newBuilder().setContentInsets(insets(30)).build()), headUnitAtLeast43 = false)
        assertEquals(30, r.contentInsets.top)
        assertFalse(r.hasMargins())
    }

    @Test
    fun newerHeadUnitsSendTheWholeConfig() {
        val r = UiConfigUpdate.apply(current, request(UiConfig.newBuilder().setContentInsets(insets(30)).build()), headUnitAtLeast43 = true)
        assertFalse("replaced, not merged", r.hasMargins())
        assertEquals(30, r.contentInsets.top)
    }

    @Test
    fun aRequestWithoutConfigChangesNothing() {
        assertEquals(current, UiConfigUpdate.apply(current, UpdateUiConfigRequest.getDefaultInstance(), headUnitAtLeast43 = true))
        assertEquals(current, UiConfigUpdate.apply(current, UpdateUiConfigRequest.getDefaultInstance(), headUnitAtLeast43 = false))
        assertEquals(UiConfig.getDefaultInstance(), UiConfigUpdate.apply(null, UpdateUiConfigRequest.getDefaultInstance(), headUnitAtLeast43 = true))
    }

    @Test
    fun theThemeIsLeftAsItIsAndTheRestApplied() {
        val dark = current.toBuilder().setUiTheme(UiTheme.UI_THEME_DARK).build()
        val light = UiConfig.newBuilder().setContentInsets(insets(30)).setUiTheme(UiTheme.UI_THEME_LIGHT).build()
        for (newer in listOf(false, true)) {
            val r = UiConfigUpdate.apply(dark, request(light), newer)
            assertEquals("newer=$newer", UiTheme.UI_THEME_DARK, r.uiTheme)
            assertEquals("newer=$newer", 30, r.contentInsets.top)
            // No theme in use: the request's isn't taken either.
            assertFalse("newer=$newer", UiConfigUpdate.apply(current, request(light), newer).hasUiTheme())
        }
    }
}

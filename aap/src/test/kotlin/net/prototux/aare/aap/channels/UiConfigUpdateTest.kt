package net.prototux.aare.aap.channels

import aap_protobuf.service.control.message.UpdateUiConfigRequestOuterClass.UpdateUiConfigRequest
import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.shared.message.UiThemeOuterClass.UiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiConfigUpdateTest {
    private fun insets(top: Int, bottom: Int = 0) = Insets.newBuilder().setTop(top).setBottom(bottom).setLeft(0).setRight(0).build()
    private val current = UiConfig.newBuilder().setMargins(insets(10)).setContentInsets(insets(20)).build()
    private fun request(ui: UiConfig) = UpdateUiConfigRequest.newBuilder().setUiConfig(ui).build()
    private fun accepted(r: UiConfigUpdate.Result) = (r as UiConfigUpdate.Accepted).config

    @Test
    fun olderHeadUnitsSendOnlyWhatChanges() {
        val r = UiConfigUpdate.apply(current, request(UiConfig.newBuilder().setContentInsets(insets(30)).build()), headUnitAtLeast43 = false)
        assertEquals("margins kept", 10, accepted(r).margins.top)
        assertEquals("insets updated", 30, accepted(r).contentInsets.top)
    }

    @Test
    fun olderHeadUnitsMergeIntoNothingAtFirst() {
        val r = UiConfigUpdate.apply(null, request(UiConfig.newBuilder().setContentInsets(insets(30)).build()), headUnitAtLeast43 = false)
        assertEquals(30, accepted(r).contentInsets.top)
        assertTrue(!accepted(r).hasMargins())
    }

    @Test
    fun newerHeadUnitsSendTheWholeConfig() {
        val r = UiConfigUpdate.apply(current, request(UiConfig.newBuilder().setContentInsets(insets(30)).build()), headUnitAtLeast43 = true)
        assertTrue("replaced, not merged", !accepted(r).hasMargins())
        assertEquals(30, accepted(r).contentInsets.top)
    }

    @Test
    fun aRequestWithoutConfigIsRefused() {
        assertTrue(UiConfigUpdate.apply(current, UpdateUiConfigRequest.getDefaultInstance(), headUnitAtLeast43 = true) is UiConfigUpdate.Refused)
        assertTrue(UiConfigUpdate.apply(current, UpdateUiConfigRequest.getDefaultInstance(), headUnitAtLeast43 = false) is UiConfigUpdate.Refused)
    }

    @Test
    fun olderHeadUnitsMayNotNameTheTheme() {
        // Not even the one in use.
        val r = UiConfigUpdate.apply(current, request(UiConfig.newBuilder().setUiTheme(UiTheme.UI_THEME_AUTOMATIC).build()), headUnitAtLeast43 = false)
        assertTrue(r is UiConfigUpdate.Refused)
    }

    @Test
    fun newerHeadUnitsMayNotChangeTheTheme() {
        val dark = current.toBuilder().setUiTheme(UiTheme.UI_THEME_DARK).build()
        // Same theme: fine.
        assertTrue(UiConfigUpdate.apply(dark, request(dark), headUnitAtLeast43 = true) is UiConfigUpdate.Accepted)
        // Another theme, or none (automatic) against dark: refused.
        assertTrue(UiConfigUpdate.apply(dark, request(current.toBuilder().setUiTheme(UiTheme.UI_THEME_LIGHT).build()), true) is UiConfigUpdate.Refused)
        assertTrue(UiConfigUpdate.apply(dark, request(current), true) is UiConfigUpdate.Refused)
        // No theme on either side.
        assertTrue(UiConfigUpdate.apply(current, request(current), true) is UiConfigUpdate.Accepted)
    }
}

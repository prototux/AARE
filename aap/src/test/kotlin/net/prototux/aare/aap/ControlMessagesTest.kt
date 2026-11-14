package net.prototux.aare.aap

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.ByeByeReasonOuterClass.ByeByeReason
import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import net.prototux.aare.aap.channels.InputChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Control and channel-control messages. */
class ControlMessagesTest {

    @Test
    fun onlyAKnownRefusalKeepsAChannelClosed() {
        fun response(status: MessageStatus) = ChannelOpenResponse.newBuilder().setStatus(status).build().toByteArray()
        assertTrue(AapSession.openAccepted(response(MessageStatus.STATUS_SUCCESS)))
        assertFalse(AapSession.openAccepted(response(MessageStatus.STATUS_INVALID_CHANNEL)))
        assertFalse(AapSession.openAccepted(response(MessageStatus.STATUS_UNSOLICITED_MESSAGE)))
        // A status this build doesn't know opens the channel, and is flagged for the log.
        assertTrue(AapSession.openAccepted(byteArrayOf(0x08, 0x63)))
        assertTrue(AapSession.unknownOpenStatus(byteArrayOf(0x08, 0x63)))
        // No status at all: what a proto3 head unit sends for SUCCESS (#74).
        assertTrue(AapSession.openAccepted(ByteArray(0)))
        assertFalse(AapSession.unknownOpenStatus(ByteArray(0)))
        assertFalse(AapSession.unknownOpenStatus(response(MessageStatus.STATUS_INVALID_CHANNEL)))
    }

    @Test
    fun wirelessTurnedOffReasonFollowsTheHeadUnitVersion() {
        assertEquals(ByeByeReason.USER_SELECTION, AapSession.wirelessDisabledReason(null))
        assertEquals(ByeByeReason.USER_SELECTION, AapSession.wirelessDisabledReason(1 to 7))
        assertEquals(ByeByeReason.USER_SELECTION, AapSession.wirelessDisabledReason(4 to 4))
        assertEquals(ByeByeReason.WIRELESS_PROJECTION_DISABLED, AapSession.wirelessDisabledReason(4 to 5))
        assertEquals(ByeByeReason.WIRELESS_PROJECTION_DISABLED, AapSession.wirelessDisabledReason(5 to 0))
        assertEquals(ByeByeReason.WIRELESS_PROJECTION_DISABLED, AapSession.wirelessDisabledReason(6 to 1))
    }

    @Test
    fun sessionConfigurationBits() {
        val chrome = UiChrome(1 or 4)
        assertTrue(chrome.hideClock)
        assertFalse(chrome.hidePhoneSignal)
        assertTrue(chrome.hideBatteryLevel)
        assertFalse(chrome.nativeMediaDuringVoice)
        assertTrue(UiChrome(8).nativeMediaDuringVoice)
        assertEquals("none", UiChrome(0).toString())
    }

    @Test
    fun serviceUpdateChangesOnlyTheTouchpadSensitivity() {
        fun input(pad: InputSourceService.TouchPad) = Service.newBuilder().setId(6)
            .setInputSourceService(InputSourceService.newBuilder().addTouchpad(pad)).build()
        val channel = InputChannel(input(InputSourceService.TouchPad.newBuilder().setWidth(800).setHeight(400).setSensitivity(2).build()),
            object : InputChannel.Listener { override fun onInput(report: aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport) {} })
        channel.onServiceUpdate(input(InputSourceService.TouchPad.newBuilder().setWidth(10).setHeight(10).setSensitivity(5).build()))
        assertEquals(5, channel.touchPad!!.sensitivity)
        assertEquals(800, channel.touchPad!!.width)
        // An update without a sensitivity leaves it alone.
        channel.onServiceUpdate(input(InputSourceService.TouchPad.newBuilder().setWidth(10).build()))
        assertEquals(5, channel.touchPad!!.sensitivity)
    }
}

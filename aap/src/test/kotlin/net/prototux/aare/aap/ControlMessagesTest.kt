package net.prototux.aare.aap

import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Control and channel-control messages. */
class ControlMessagesTest {

    @Test
    fun onlySuccessOpensAChannel() {
        fun response(status: MessageStatus) = ChannelOpenResponse.newBuilder().setStatus(status).build().toByteArray()
        assertTrue(AapSession.openAccepted(response(MessageStatus.STATUS_SUCCESS)))
        assertFalse(AapSession.openAccepted(response(MessageStatus.STATUS_INVALID_CHANNEL)))
        assertFalse(AapSession.openAccepted(response(MessageStatus.STATUS_UNSOLICITED_MESSAGE)))
        // A status this build doesn't know: kept as an unknown field, it must not read as SUCCESS.
        assertFalse(AapSession.openAccepted(byteArrayOf(0x08, 0x63)))
        // No status at all: what a proto3 head unit sends for SUCCESS (#74).
        assertTrue(AapSession.openAccepted(ByteArray(0)))
    }
}

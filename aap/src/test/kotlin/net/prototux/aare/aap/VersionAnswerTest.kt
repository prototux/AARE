package net.prototux.aare.aap

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionAnswerTest {
    @Test
    fun answersLikeTheOfficialPhoneByDefault() {
        assertEquals(1 to 7, AapSession.versionAnswer(1, 1, echo = false))
        assertEquals(1 to 7, AapSession.versionAnswer(1, 7, echo = false))
        assertEquals(6 to 1, AapSession.versionAnswer(1, 8, echo = false))
        assertEquals(6 to 1, AapSession.versionAnswer(7, 2, echo = false))
    }

    @Test
    fun echoesTheHeadUnitsOwnVersion() {
        assertEquals(1 to 1, AapSession.versionAnswer(1, 1, echo = true))
        assertEquals(1 to 8, AapSession.versionAnswer(1, 8, echo = true))
        assertEquals(7 to 2, AapSession.versionAnswer(7, 2, echo = true))
    }
}

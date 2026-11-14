package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantLinksTest {
    @Test
    fun navigateTakesTheQueryParameter() {
        assertEquals(AssistantLink.Navigate("132 rue de Rivoli"), AssistantLinks.parse("aare://navigate?q=132%20rue%20de%20Rivoli"))
        assertEquals(AssistantLink.Navigate("the airport"), AssistantLinks.parse("aare://navigate?q=the+airport"))
    }

    @Test
    fun navigateWithoutOrWithABlankDestinationIsRejected() {
        assertNull(AssistantLinks.parse("aare://navigate"))
        assertNull(AssistantLinks.parse("aare://navigate?q="))
        assertNull(AssistantLinks.parse("aare://navigate?q=%20"))
    }

    @Test
    fun playDefaultsToPlayAndAcceptsTheFourTransportActions() {
        assertEquals(AssistantLink.Play("play"), AssistantLinks.parse("aare://play"))
        assertEquals(AssistantLink.Play("pause"), AssistantLinks.parse("aare://play?action=pause"))
        assertEquals(AssistantLink.Play("next"), AssistantLinks.parse("aare://play?action=NEXT"))
        assertEquals(AssistantLink.Play("previous"), AssistantLinks.parse("aare://play?q=previous"))
    }

    @Test
    fun playRejectsAnythingNotOnTheTransportAllowlist() {
        assertNull(AssistantLinks.parse("aare://play?action=shuffle"))
        assertNull(AssistantLinks.parse("aare://play?action=rm%20-rf"))
    }

    @Test
    fun openTakesTheAppOrQParameter() {
        assertEquals(AssistantLink.Open("CoMaps"), AssistantLinks.parse("aare://open?app=CoMaps"))
        assertEquals(AssistantLink.Open("Spotify"), AssistantLinks.parse("aare://open?q=Spotify"))
        assertNull(AssistantLinks.parse("aare://open"))
    }

    @Test
    fun callKeepsOnlyDigitsAndALeadingPlus() {
        assertEquals(AssistantLink.Call("+15551234567"), AssistantLinks.parse("aare://call?n=%2B1%20(555)%20123-4567"))
        assertEquals(AssistantLink.Call("112"), AssistantLinks.parse("aare://call?n=112"))
    }

    @Test
    fun callWithNoDigitsOrACodeIsRejected() {
        assertNull(AssistantLinks.parse("aare://call?n=%23"))
        assertNull(AssistantLinks.parse("aare://call?n="))
        assertNull(AssistantLinks.parse("aare://call"))
    }

    @Test
    fun onlyTheFourKnownHostsAreAllowed() {
        assertNull(AssistantLinks.parse("aare://settings"))
        assertNull(AssistantLinks.parse("aare://run?cmd=rm"))
        assertNull(AssistantLinks.parse("aare://"))
    }

    @Test
    fun anythingNotTheAareSchemeIsRejected() {
        assertNull(AssistantLinks.parse("https://navigate?q=home"))
        assertNull(AssistantLinks.parse("not a uri at all"))
        assertNull(AssistantLinks.parse("javascript:alert(1)"))
    }
}

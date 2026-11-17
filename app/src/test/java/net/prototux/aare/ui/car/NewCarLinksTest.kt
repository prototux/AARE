package net.prototux.aare.ui.car

import net.prototux.aare.host.CarLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewCarLinksTest {
    private val nav = "url\thttps://aare.internal/go/car_navigate?destination=Berlin"
    private val pause = "url\thttps://aare.internal/go/car_media?action=pause"

    @Test
    fun aNewReplysLinkIsNew() {
        val (_, seen) = newCarLinks(emptyMap(), listOf(nav))
        assertEquals(listOf(pause), newCarLinks(seen, listOf(nav, pause)).first)
    }

    @Test
    fun theSameLinkAgainInALaterReplyIsNew() {
        val (_, seen) = newCarLinks(emptyMap(), listOf(pause))
        assertEquals(listOf(pause), newCarLinks(seen, listOf(pause, pause)).first)
    }

    @Test
    fun scrollingAnEmojiOutAndBackRunsNothing() {
        val compass = "tap\t🧭\t100.0,200.0"
        val (_, seen) = newCarLinks(emptyMap(), listOf(compass))
        val (gone, after) = newCarLinks(seen, emptyList())
        assertTrue(gone.isEmpty())
        assertTrue(newCarLinks(after, listOf(compass)).first.isEmpty())
    }

    @Test
    fun anAddressShownAgainAfterItWentRunsAgain() {
        // The prompt shows the address, goes once the link ran, and shows it again for the same place later.
        val (first, shown) = newCarLinks(emptyMap(), listOf(nav))
        assertEquals(listOf(nav), first)
        val (_, gone) = newCarLinks(shown, emptyList())
        assertEquals(listOf(nav), newCarLinks(gone, listOf(nav)).first)
    }

    @Test
    fun aTappedEmojiCountsByItsTextNotItsPlace() {
        val (_, seen) = newCarLinks(emptyMap(), listOf("tap\t🧭\t100.0,200.0"))
        assertTrue(newCarLinks(seen, listOf("tap\t🧭\t100.0,90.0")).first.isEmpty())
        assertEquals(listOf("tap\t🧭\t100.0,500.0"),
            newCarLinks(seen, listOf("tap\t🧭\t100.0,90.0", "tap\t🧭\t100.0,500.0")).first)
    }

    @Test
    fun theEmojisMatchWithAndWithoutTheirVariantSelector() {
        val tap = Regex(CAR_LINK_TAP)
        assertTrue(tap.matches("🧭"))
        assertTrue(tap.matches("▶️"))
        assertTrue(!tap.matches("Berlin 🧭"))
    }

    @Test
    fun ofTheEmojisOnlyTheLowestRuns() {
        // Scrolled back: a second compass came into view above the one already seen.
        val old = "tap\t🧭\t100.0,90.0"
        val seenOne = "tap\t🧭\t100.0,500.0"
        assertTrue(carLinksToRun(listOf(seenOne), listOf(old, seenOne)) == listOf(seenOne))
        assertTrue(carLinksToRun(listOf(old), listOf(old, seenOne)).isEmpty())
        assertEquals(listOf(nav), carLinksToRun(listOf(nav), listOf(nav, seenOne)))
    }

    @Test
    fun everyEmojiInTheInstructionsIsOneTheCarScreenTaps() {
        val emojis = Regex("""\[([^\]]+)]\(https://aare\.internal/go/car_""").findAll(CarLinks.INSTRUCTIONS).map { it.groupValues[1] }.toList()
        assertEquals(14, emojis.size)
        emojis.forEach { assertTrue(it, Regex(CAR_LINK_TAP).matches(it)) }
    }

    @Test
    fun aProjectNameIsBestWithoutSpaces() {
        assertTrue(CarLinks.goodProjectName("Car001"))
        assertTrue(!CarLinks.goodProjectName("Car 001"))
        assertTrue(!CarLinks.goodProjectName(""))
    }

    @Test
    fun onlyAPromptRightAfterAaresTapIsItsOwn() {
        assertTrue(ownPrompt(tappedAt = 1_000, now = 4_000))
        assertTrue(!ownPrompt(tappedAt = 0, now = 4_000))
        assertTrue(!ownPrompt(tappedAt = 1_000, now = 30_000))
    }
}

package net.prototux.aare.audio

import net.prototux.aare.audio.CaptureMethod.AUDIO_POLICY
import net.prototux.aare.audio.CaptureMethod.PLAYBACK_CAPTURE
import net.prototux.aare.audio.CaptureMethod.ROOT_POLICY
import net.prototux.aare.audio.CaptureMethod.SHIZUKU
import net.prototux.aare.audio.CaptureMethod.SHIZUKU_POLICY
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The capture fallback order, and what each car channel does with the method it gets. */
class CaptureOrderTest {
    private fun env(privileged: Boolean = false, shizuku: Boolean = false, root: Boolean = false, playback: Boolean = false) =
        CaptureOrder.Env(privileged, shizuku, root, playback)

    /** Every method [env] goes through, each one failing in turn. */
    private fun ladder(env: CaptureOrder.Env): List<CaptureMethod> {
        val failed = mutableSetOf<CaptureMethod>()
        while (true) failed += CaptureOrder.best(env, failed) ?: return failed.toList()
    }

    @Test fun thePrivilegedInstallComesFirstThenRootThenShizuku() {
        assertEquals(listOf(AUDIO_POLICY, ROOT_POLICY, SHIZUKU_POLICY, SHIZUKU, PLAYBACK_CAPTURE),
            ladder(env(privileged = true, shizuku = true, root = true, playback = true)))
    }

    @Test fun shizukuAloneSplitsThenMixes() {
        assertEquals(listOf(SHIZUKU_POLICY, SHIZUKU, PLAYBACK_CAPTURE), ladder(env(shizuku = true, playback = true)))
    }

    @Test fun rootAloneSplitsThenMixes() {
        assertEquals(listOf(ROOT_POLICY, SHIZUKU), ladder(env(root = true)))
    }

    @Test fun screenCaptureOnlyWithAConsent() {
        assertEquals(listOf(PLAYBACK_CAPTURE), ladder(env(playback = true)))
        assertNull(CaptureOrder.best(env()))
    }

    @Test fun aForcedMethodIsTheOnlyOneTried() {
        val all = env(privileged = true, shizuku = true, root = true, playback = true)
        assertEquals(SHIZUKU, CaptureOrder.best(all, forced = SHIZUKU))
        // Failed, or not offered: no fallback.
        assertNull(CaptureOrder.best(all, exclude = setOf(SHIZUKU), forced = SHIZUKU))
        assertNull(CaptureOrder.best(env(shizuku = true, playback = true), forced = ROOT_POLICY))
    }

    @Test fun theGuidanceChannelOnlyTakesTheSplitMethods() {
        for (m in listOf(AUDIO_POLICY, SHIZUKU_POLICY, ROOT_POLICY)) {
            assertEquals(CaptureOrder.Plan.Capture(m), CaptureOrder.plan(m, guidance = true, singleChannel = false))
        }
        for (m in listOf(SHIZUKU, PLAYBACK_CAPTURE)) {
            assertEquals(CaptureOrder.Plan.Skip, CaptureOrder.plan(m, guidance = true, singleChannel = false))
            assertEquals(CaptureOrder.Plan.Capture(m), CaptureOrder.plan(m, guidance = false, singleChannel = false))
        }
    }

    @Test fun aSingleChannelCarLeavesItsGuidanceChannelUnused() {
        assertEquals(CaptureOrder.Plan.Skip, CaptureOrder.plan(AUDIO_POLICY, guidance = true, singleChannel = true))
        assertEquals(CaptureOrder.Plan.Capture(AUDIO_POLICY), CaptureOrder.plan(AUDIO_POLICY, guidance = false, singleChannel = true))
    }

    @Test fun withNoMethodOnlyTheMediaChannelSaysSo() {
        assertEquals(CaptureOrder.Plan.NoMethod, CaptureOrder.plan(null, guidance = false, singleChannel = false))
        assertEquals(CaptureOrder.Plan.Skip, CaptureOrder.plan(null, guidance = true, singleChannel = false))
    }

    @Test fun navigationVoiceGoesWithTheMusicWithoutAGuidanceChannel() {
        val both = CaptureMethods.MEDIA_USAGES + CaptureMethods.GUIDANCE_USAGES
        assertArrayEquals(CaptureMethods.GUIDANCE_USAGES, CaptureOrder.policyUsages(guidance = true, singleChannel = false, carHasGuidance = true))
        assertArrayEquals(CaptureMethods.MEDIA_USAGES, CaptureOrder.policyUsages(guidance = false, singleChannel = false, carHasGuidance = true))
        assertArrayEquals(both, CaptureOrder.policyUsages(guidance = false, singleChannel = false, carHasGuidance = false))
        assertArrayEquals(both, CaptureOrder.policyUsages(guidance = false, singleChannel = true, carHasGuidance = true))
    }
}

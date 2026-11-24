package net.prototux.aare.audio

import net.prototux.aare.audio.CaptureMethod.AUDIO_POLICY
import net.prototux.aare.audio.CaptureMethod.PLAYBACK_CAPTURE
import net.prototux.aare.audio.CaptureMethod.ROOT_POLICY
import net.prototux.aare.audio.CaptureMethod.SHIZUKU
import net.prototux.aare.audio.CaptureMethod.SHIZUKU_POLICY
import net.prototux.aare.audio.CaptureStatus.Reason
import net.prototux.aare.audio.CaptureStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the car audio screen says about each method, from the phone's setup, the session and past refusals. */
class CaptureStatusTest {
    private fun setup(
        sdk: Int = 36, rootFound: Boolean = false, rootAllowed: Boolean = false,
        shizukuInstalled: Boolean = false, shizukuRunning: Boolean = false, shizukuAllowed: Boolean = false, consent: Boolean = false,
    ) = CaptureStatus.Setup(sdk, false, rootFound, rootAllowed, shizukuInstalled, shizukuRunning, shizukuAllowed, consent)

    private fun CaptureStatus.Report.of(m: CaptureMethod) = methods.single { it.method == m }

    @Test fun aStockPhoneOnlyNeedsTheScreenCaptureAuthorization() {
        val r = CaptureStatus.report(setup(), live = null, refused = emptyMap(), forced = null)
        assertNull(r.active)
        assertFalse(r.connected)
        assertEquals(State.NOT_AVAILABLE, r.of(AUDIO_POLICY).state)
        assertEquals(Reason.NOT_ROOTED, r.of(ROOT_POLICY).reason)
        assertEquals(Reason.SHIZUKU_NOT_INSTALLED, r.of(SHIZUKU_POLICY).reason)
        assertEquals(State.NEEDS_SETUP, r.of(PLAYBACK_CAPTURE).state)
        assertEquals(Reason.NO_CONSENT, r.of(PLAYBACK_CAPTURE).reason)
    }

    @Test fun aRootedPhoneExpectsRootThenKeepsTheMixedCaptureAsBackup() {
        val r = CaptureStatus.report(setup(rootFound = true, rootAllowed = true), null, emptyMap(), null)
        assertEquals(ROOT_POLICY, r.active)
        assertEquals(State.IN_USE, r.of(ROOT_POLICY).state)
        assertEquals(State.BACKUP, r.of(SHIZUKU).state)
        assertEquals(State.NOT_AVAILABLE, r.of(SHIZUKU_POLICY).state)
    }

    @Test fun rootFoundButNotAllowedIsSomethingToSetUp() {
        val r = CaptureStatus.report(setup(rootFound = true), null, emptyMap(), null)
        assertEquals(State.NEEDS_SETUP, r.of(ROOT_POLICY).state)
        assertEquals(Reason.ROOT_NOT_ALLOWED, r.of(ROOT_POLICY).reason)
        // The mixed capture says the same, rather than "Shizuku isn't installed".
        assertEquals(Reason.ROOT_NOT_ALLOWED, r.of(SHIZUKU).reason)
    }

    @Test fun shizukuInstalledButStoppedIsSomethingToSetUp() {
        val r = CaptureStatus.report(setup(shizukuInstalled = true), null, emptyMap(), null)
        assertEquals(Reason.SHIZUKU_NOT_RUNNING, r.of(SHIZUKU_POLICY).reason)
        assertEquals(Reason.SHIZUKU_NOT_RUNNING, r.of(SHIZUKU).reason)
        assertEquals(State.NEEDS_SETUP, r.of(SHIZUKU).state)
    }

    @Test fun beforeADriveARefusalMovesWhatIsExpected() {
        val refused = mapOf(ROOT_POLICY to CaptureStatus.Refusal("SecurityException: denied", 1_000L))
        val r = CaptureStatus.report(setup(rootFound = true, rootAllowed = true), null, refused, null)
        assertEquals(SHIZUKU, r.active)
        val root = r.of(ROOT_POLICY)
        assertEquals(State.REFUSED_LAST_DRIVE, root.state)
        assertEquals("SecurityException: denied", root.detail)
        assertEquals(1_000L, root.atMs)
    }

    @Test fun whileConnectedTheSessionSaysWhatRunsAndWhatFailed() {
        val live = CaptureStatus.Live(SHIZUKU, mapOf(ROOT_POLICY to "refused"))
        val r = CaptureStatus.report(setup(rootFound = true, rootAllowed = true), live, emptyMap(), null)
        assertTrue(r.connected)
        assertEquals(State.FAILED, r.of(ROOT_POLICY).state)
        assertEquals("refused", r.of(ROOT_POLICY).detail)
        assertEquals(State.IN_USE, r.of(SHIZUKU).state)
    }

    @Test fun aForcedMethodLeavesTheOthersUntried() {
        val r = CaptureStatus.report(setup(shizukuInstalled = true), null, emptyMap(), forced = SHIZUKU_POLICY)
        assertNull(r.active)
        assertEquals(State.NEEDS_SETUP, r.of(SHIZUKU_POLICY).state)
        assertTrue(r.methods.filter { it.method != SHIZUKU_POLICY }.all { it.state == State.NOT_TRIED })
    }

    @Test fun beforeAndroid12OnlyScreenCaptureIsThere() {
        val r = CaptureStatus.report(setup(sdk = 30, rootFound = true, rootAllowed = true, consent = true), null, emptyMap(), null)
        assertEquals(PLAYBACK_CAPTURE, r.active)
        assertEquals(Reason.ANDROID_TOO_OLD, r.of(ROOT_POLICY).reason)
        assertEquals(Reason.ANDROID_TOO_OLD, r.of(SHIZUKU).reason)
    }

    @Test fun refusalsSurviveTheirSettingsText() {
        val refused = mapOf(
            ROOT_POLICY to CaptureStatus.Refusal("a\treason\nover lines", 42L),
            SHIZUKU to CaptureStatus.Refusal(null, 7L),
        )
        val back = CaptureStatus.decode(CaptureStatus.encode(refused))
        assertEquals(CaptureStatus.Refusal("a reason over lines", 42L), back[ROOT_POLICY])
        assertEquals(CaptureStatus.Refusal(null, 7L), back[SHIZUKU])
        assertTrue(CaptureStatus.decode("").isEmpty())
        assertTrue(CaptureStatus.decode("NOT_A_METHOD\t1\tx").isEmpty())
    }
}

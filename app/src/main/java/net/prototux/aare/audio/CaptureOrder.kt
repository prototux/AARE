package net.prototux.aare.audio

/**
 * The one copy of the capture fallback order, and what each car channel does with the method it
 * gets: for the session (CarSession.startAudio) and the car audio screen alike. Pure, so the order
 * is tested; [CaptureMethods.env] reads what the phone offers.
 */
object CaptureOrder {
    /** What the phone offers right now. */
    data class Env(
        /** AARE holds MODIFY_AUDIO_ROUTING itself (the privileged install). */
        val privileged: Boolean,
        /** Shizuku runs and AARE may use it, on Android 12+ ([CaptureMethods.shizukuAvailable]). */
        val shizuku: Boolean,
        /** The user allowed root, on Android 12+ ([CaptureMethods.rootAvailable]). */
        val root: Boolean,
        /** A screen-capture consent is held, on Android 10+ (capturing other apps' sound came then). */
        val playbackCapture: Boolean,
    )

    /** What one car channel does. */
    sealed interface Plan {
        /** Capture with [method]. */
        data class Capture(val method: CaptureMethod) : Plan
        /** Leave the channel unused: the media channel carries its sound, or there is none to give it. */
        data object Skip : Plan
        /** No method at all: the media channel tells the driver to set one up. */
        data object NoMethod : Plan
    }

    /** The audio-policy methods: music and navigation voice apart, each channel with its usages. */
    val POLICY = setOf(CaptureMethod.AUDIO_POLICY, CaptureMethod.SHIZUKU_POLICY, CaptureMethod.ROOT_POLICY)

    /**
     * The methods whose failure also moves the other channel to the next method: the next one may
     * be a mixed stream, which needs the media channel alone, or navigation voice would play twice.
     */
    val FAILURE_MOVES_BOTH = setOf(CaptureMethod.SHIZUKU_POLICY, CaptureMethod.ROOT_POLICY)

    /** The best method [env] offers, skipping [exclude] (methods that already failed). */
    fun best(env: Env, exclude: Set<CaptureMethod> = emptySet()): CaptureMethod? = when {
        CaptureMethod.AUDIO_POLICY !in exclude && env.privileged -> CaptureMethod.AUDIO_POLICY
        CaptureMethod.SHIZUKU_POLICY !in exclude && env.shizuku -> CaptureMethod.SHIZUKU_POLICY
        // Not while Shizuku runs: its methods come first, and its fallback stays as it was.
        CaptureMethod.ROOT_POLICY !in exclude && !env.shizuku && env.root -> CaptureMethod.ROOT_POLICY
        CaptureMethod.SHIZUKU !in exclude && (env.shizuku || env.root) -> CaptureMethod.SHIZUKU
        CaptureMethod.PLAYBACK_CAPTURE !in exclude && env.playbackCapture -> CaptureMethod.PLAYBACK_CAPTURE
        else -> null
    }

    /**
     * What the media or the guidance channel does with [method]. [singleChannel]: the head unit
     * wants navigation voice mixed into the music (vehicles.json), so its guidance channel stays
     * unused. Only the audio-policy methods keep navigation voice apart; the others have one mixed
     * stream (or media only), which goes to the media channel.
     */
    fun plan(method: CaptureMethod?, guidance: Boolean, singleChannel: Boolean): Plan = when {
        guidance && singleChannel -> Plan.Skip
        method == null -> if (guidance) Plan.Skip else Plan.NoMethod
        guidance && method !in POLICY -> Plan.Skip
        else -> Plan.Capture(method)
    }

    /**
     * The usages an audio-policy capture takes for one channel. Without a guidance channel in the
     * car ([carHasGuidance] false, or [singleChannel]), navigation voice goes with the music.
     */
    fun policyUsages(guidance: Boolean, singleChannel: Boolean, carHasGuidance: Boolean): IntArray = when {
        guidance -> CaptureMethods.GUIDANCE_USAGES
        carHasGuidance && !singleChannel -> CaptureMethods.MEDIA_USAGES
        else -> CaptureMethods.MEDIA_USAGES + CaptureMethods.GUIDANCE_USAGES
    }
}

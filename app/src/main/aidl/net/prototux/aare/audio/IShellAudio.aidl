package net.prototux.aare.audio;

import net.prototux.aare.audio.IShellMicListener;

/** The audio capture helper Shizuku runs with adb-level rights (ShellAudioService). */
interface IShellAudio {
    /**
     * Starts capturing everything the phone plays (which then stops playing on the phone).
     * Returns the read end of a pipe of 16-bit little-endian PCM, or null if capture failed.
     */
    ParcelFileDescriptor start(int sampleRate, int channels) = 1;

    void stop() = 2;

    /**
     * Android Auto's way, as the shell (which holds MODIFY_AUDIO_ROUTING): an audio policy that
     * takes only [usages] off the phone's speaker. One stream per [key], so music and navigation
     * voice can go to their own car channels. Returns a PCM pipe like start(), or null.
     */
    ParcelFileDescriptor startPolicy(int key, int sampleRate, int channels, in int[] usages) = 3;

    void stopPolicy(int key) = 4;

    /**
     * The car's microphone for the phone's apps, as the shell: an audio policy that feeds [pcm]
     * (16-bit PCM, [sampleRate] Hz, [channels]) to other apps' voice recordings instead of the
     * phone's mic, once setMicInjecting(true). [listener] hears when such a recording (not the
     * caller's) starts or stops. False if the system refused the policy.
     */
    boolean startMicInjection(in ParcelFileDescriptor pcm, int sampleRate, int channels, IShellMicListener listener) = 5;

    /** On: the PCM reaches the recordings (they move to it once it flows). Off: back to the phone's mic. */
    void setMicInjecting(boolean on) = 6;

    void stopMicInjection() = 7;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}

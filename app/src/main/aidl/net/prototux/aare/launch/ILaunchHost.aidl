package net.prototux.aare.launch;

import android.view.Surface;

/**
 * The privileged helper that puts an ordinary phone app on the car screen: it runs with adb-level
 * (Shizuku) or root rights, creates a virtual display rendering into the car screen's [Surface],
 * launches the chosen app on it, and injects the car's touches and keys into it.
 *
 * Implemented by LaunchHostService; invoked over a Shizuku-bound binder, or in-process by the su
 * app_process helper (LaunchMain), which hands this same binder back to the app over a broadcast.
 */
interface ILaunchHost {
    /** Create the display into [surface] and launch [packageName] on it. Returns the virtual display id, or -1. */
    int start(in Surface surface, int width, int height, int dpi, String packageName) = 1;

    /** The car screen's surface changed size (new [surface]/size). */
    void resize(in Surface surface, int width, int height, int dpi) = 2;

    /** Inject a touch (MotionEvent) at car-screen coordinates into the launched app's display. */
    void touch(long downTime, long eventTime, int action, float x, float y, int pointerId) = 3;

    /** Inject a key (e.g. Back) into the launched app's display. */
    void key(long eventTime, int action, int keyCode) = 4;

    /** Tear the display down and stop feeding the app. */
    void stop() = 5;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}

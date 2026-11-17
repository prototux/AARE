package net.prototux.aare.launch;

import android.app.PendingIntent;
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
    int start(in Surface surface, int width, int height, int dpi, String packageName, boolean keepDisplayAwake) = 1;

    /** The car screen's surface changed size (new [surface]/size). */
    void resize(in Surface surface, int width, int height, int dpi) = 2;

    /** Inject a touch (MotionEvent) at car-screen coordinates into the launched app's display. */
    oneway void touch(long downTime, long eventTime, int action, float x, float y, int pointerId) = 3;

    /** Inject a key (e.g. Back) into the launched app's display. */
    void key(long eventTime, int action, int keyCode) = 4;

    /** Tear the display down and stop feeding the app. */
    void stop() = 5;

    /** Mirror the phone's own screen into [surface], like scrcpy: no screen-capture consent needed. False if it can't. */
    boolean mirror(in Surface surface, int width, int height, int dpi) = 7;

    /** Stop the mirror started by [mirror]. */
    void stopMirror() = 8;

    /** Start [intent] (an app's own activity, say its player) on the display made by [start]. False if it can't. */
    boolean openPending(in PendingIntent intent) = 6;

    /**
     * The phone's own screen while the car shows it or one of its apps: [landscape] turned to
     * landscape, [awake] kept from timing out and locking, [panelOff] its panel dark while the phone
     * still draws it. All false puts everything back, as stop() does.
     */
    void phoneScreen(boolean landscape, boolean awake, boolean panelOff) = 11;

    /** The package of the app in front on the phone's own screen, or null. */
    String topApp() = 16;

    /** Whether an app still shows on the display [start] made: Back on its first screen closes it. */
    boolean hasApp() = 19;

    /** Open [url] in [packageName] on that display, afresh (its task cleared), started by this helper (which may start activities from the background); [warm]: a tap on that display follows. */
    boolean openLink(String url, String packageName, boolean warm) = 12;

    /** Fingers on the launched app's display at once (pinch to zoom): [xy] their x, y in turn, [ids] their pointer ids, in that order. */
    oneway void touches(long downTime, long eventTime, int action, in float[] xy, in int[] ids) = 18;


    /** Start [packageName]'s app shortcut whose id contains [keyword] (its voice mode, say) on that display. False if it can't. */
    boolean openShortcut(String packageName, String keyword) = 9;

    /** Tap the button on that display whose label matches [pattern] (a regex, any case), waiting up to [timeoutMs] for it. False if none showed. */
    boolean tapLabel(String pattern, long timeoutMs) = 10;

    /**
     * What on that display looks like a car link, top to bottom, one per line: "url<TAB>address" for
     * a link whose address contains [host], or "tap<TAB>text<TAB>x,y" for a text matching [tapPattern]
     * (an emoji drawn without its address), with its middle. Null when it couldn't look.
     */
    String carLinks(String host, String tapPattern) = 13;

    /** A finger's tap at [x], [y] on that display, as [tapLabel] taps. */
    void tapAt(float x, float y) = 14;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}

# The Xposed host-shims module

`xposed/` is an optional, standalone [libxposed](https://github.com/libxposed) module for rooted
phones. It is a separate APK, built with `./gradlew :xposed:assembleRelease`, installed and enabled
in an Xposed manager (LSPosed/Vector). The AARE app does not depend on it and never loads it.

It exists so a user who already runs LSPosed can let AARE stand in for Google's Android Auto host
with hooks, instead of the Magisk privileged-app module or a per-session consent tap. It adds no new
capability: everything it does has another route in AARE already.

## The hooks

Each is a compatibility shim for AARE itself, gated on AARE's own package or uid. A real Google
Android Auto session falls through every hook unchanged (the hook calls the original unless the host
is AARE).

### In each scoped client app

Two kinds of app refuse a non-Google host, each hooked by its own gate.

- **Waze and other Car App Library apps — `HostValidator.isValidHost(HostInfo)` → true for AARE.**
  These apps check the host's package name *and* signing certificate against an allowlist; AARE's
  certificate isn't Google's, so the check fails (`SecurityException: Unrecognized host with uid …`).
  The hook accepts AARE specifically. (The app manifest's `TEMPLATE_RENDERER` permission is the
  non-root path that covers older library versions.)
- **Google Maps — `ContextImpl.checkCallingPermission(CAPTURE_VIDEO_OUTPUT)` → granted for AARE's
  uid.** Maps uses the private `ICarProjection` protocol and, inside its own process, checks that the
  binding host holds `CAPTURE_VIDEO_OUTPUT` — a `signature` permission an ordinary app can't hold —
  on every projection transaction. The hook returns *granted* only when the IPC caller is AARE's uid
  and only for that permission, so Maps accepts AARE as its projection host. Every other caller and
  permission is unaffected. This hook only clears the permission wall; AARE must also implement the
  `ICarProjection` host side (a separate piece of work in the app itself).

- **Media apps that only serve Android Auto — `MediaBrowserService.onGetRoot` + uid→package.** An
  app like Spotify or VRadio exposes a `MediaBrowserService` and, in `onGetRoot`, returns a browsable
  root only to Android Auto: by package name (VRadio checks the caller equals
  `com.google.android.projection.gearhead`), or by package name **and** signing certificate (the AOSP
  media sample's `PackageValidator`, via `allowed_media_browser_callers.xml`). The hooks make AARE's
  uid look like Android Auto to that app: `onGetRoot` is called with the package rewritten to Android
  Auto's for AARE's uid (a name-only check then passes), and `getPackagesForUid`/`getNameForUid` map
  AARE's uid to Android Auto's package (so a validator that cross-checks uid→package, then its
  signature, resolves Android Auto's real certificate). A signature-checking app (Spotify) therefore
  still needs Android Auto installed for its certificate to be present to match; a name-only app
  (VRadio) needs only the `onGetRoot` rewrite.

A client app is only hooked if the module is enabled for it in the Xposed manager. The shipped
`scope.list` lists `com.waze`, `com.google.android.apps.maps`, `com.spotify.music` and
`com.ilv.vradio`; add other apps there yourself if a specific one rejects AARE's host.

### In system_server

- **`AppOpsService.checkOperation` for `PROJECT_MEDIA` → allowed for AARE.** AARE's
  `createScreenCaptureIntent()` then returns a token without the consent dialog.
- **`PermissionManagerService…checkUidPermission` → granted for AARE's privileged permissions.**
  The set mirrors `tools/magisk/privapp-permissions-aare.xml`
  (`CAPTURE_AUDIO_OUTPUT`, `MODIFY_AUDIO_ROUTING`).

**Limit:** these relax only framework-level checks. `CAPTURE_AUDIO_OUTPUT` is also enforced natively
in `audioserver`, which a Java-layer hook does not reach, so system audio capture still needs the
native privileged grant — the Magisk module, or the `su`/Shizuku audio backends. The permission hook
is best-effort: system_server internals are renamed between Android versions, so it tries the known
class/method shapes and skips the hook if none match, rather than risking a boot loop.

## Status and risks

New, and **not yet verified on a device**. The in-app hooks depend on `androidx.car.app` internals
(the `HostInfo.mPackageName` field); the system hooks depend on AOSP internals that vary by version.
The PR is a starting point for testing, not a finished feature, and is kept out of the release build.

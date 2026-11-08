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

- **`HostValidator.isValidHost(HostInfo)` → true for AARE.** Car App Library apps check the host's
  package name *and* signing certificate against an allowlist. AARE's certificate isn't Google's, so
  the check fails; the hook accepts AARE specifically. (See `TEMPLATE_RENDERER` in the app manifest
  for the non-root path that covers older library versions.)
- **`HostInfo.getPackageName()` → Google's package when the host is AARE.** For an app that only
  compares the host package name string (not its signature), this makes AARE read as Google's host.
  Fragile: an app with its own stronger check is unaffected.

A Car App Library app is only hooked if the module is enabled for it in the Xposed manager. The
shipped `scope.list` lists a few common apps; users add their own.

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

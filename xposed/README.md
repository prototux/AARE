# AARE host shims (Xposed module)

A separate, optional APK for **rooted phones**. It is not part of the AARE app and the app never
depends on it. It uses [libxposed](https://github.com/libxposed) (LSPosed/Vector) to make AARE a
drop-in for Google's Android Auto host where an app or the system only recognises Google's.

Everything it does is already possible another way (the Magisk privileged-app module, Shizuku, or
extracting the Android Auto certificate). The module just does it with hooks instead, for people who
already run LSPosed and would rather not install the Magisk module.

## What it hooks

All four are gated on AARE's own package or uid; a real Google Android Auto session is never changed.

| Process | Hook | Effect |
| --- | --- | --- |
| each scoped client app | `HostValidator.isValidHost(HostInfo)` | returns true when the host is AARE, so a Car App Library app accepts AARE despite its non-Google certificate |
| each scoped client app | `HostInfo.getPackageName()` | reports Google's package when the host is AARE, so an app that only compares the host package name string accepts AARE |
| system_server | `AppOpsService.checkOperation` for `PROJECT_MEDIA` | AARE gets a MediaProjection token without the per-session consent dialog |
| system_server | `PermissionManagerService…checkUidPermission` | AARE's declared privileged permissions report as granted, without the Magisk privapp allowlist |

The two system hooks only relax **framework-level** checks. Permissions the platform enforces
natively (audio capture in `audioserver`) still need the native privileged grant, so the Magisk
module remains the way to get system audio; see [docs/xposed.md](../docs/xposed.md).

## Scope

`src/main/resources/META-INF/xposed/scope.list` lists `android` (for the system hooks) and a few
common client apps. **Add your own car apps** to the module's scope in the LSPosed/Vector manager —
a Car App Library app is only hooked if the module is enabled for it.

## Build

```sh
./gradlew :xposed:assembleRelease
```

Install the APK, enable it in your Xposed manager, tick the scope, and reboot (or restart the
scoped apps). The module compiles against `io.github.libxposed:api` with `compileOnly`; the
framework provides the API at runtime, so it is never bundled.

> **Status:** new and not yet verified on a device. The system_server hooks target framework
> internals whose names change between Android versions; each is best-effort and skips a target it
> can't find rather than crashing system_server, but which Android versions they work on is unknown
> until tested. Treat this as a starting point.

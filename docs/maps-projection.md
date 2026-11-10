# Hosting Google Maps: the private `ICarProjection` protocol

Google Maps (and a few other apps) do **not** draw their car screen through the public AndroidX Car
App Library. They expose a private projection service that speaks an undocumented GMS Binder
interface, `com.google.android.gms.car.ICarProjection`, and render into a `Surface` the host
provides. This is the protocol AARE must speak to show Maps' real navigation UI on the car screen.

This document describes the protocol and AARE's host implementation. **Status: implemented from the
observable wire format, compiles, and is not yet verified on a device.** It needs a rooted phone to
get past Maps' permission check (below) and on-device iteration to complete the car-screen wiring.

## The shape of it

- Maps exports `com.google.android.apps.gmm.car.GmmCarProjectionService` (and variants), with no
  manifest permission. A host binds it and talks `ICarProjection` to its returned Binder.
- The host provides a `Surface`; the app creates its own window on that surface and renders into it.
  No `MediaProjection`, screen capture or accessibility is involved — the host simply shows the
  surface (a `SurfaceView` on the car screen), and SurfaceFlinger composites the app's window.
- Startup sequence the host drives:
  1. `onSetup` (tx 1) — hand the app our `ICarProjectionCallback` and `ICar` Binders.
  2. `onProjectionStart` (tx 26) — a `DrawingSpec` carrying the surface and geometry.
  3. `onProjectionResume` (tx 4) — without this the app creates its window but never draws.
  4. Input and lifecycle flow through `onMotionEvent` (tx 7), `onKeyEvent` (tx 8),
     `onProjectionPause` (tx 5) and `onProjectionStop` (tx 9).
- Before drawing, the app reads back `CarInfo` (ICar tx 1), `CarUiInfo` (ICar tx 2) and, via an
  `ICarDisplayManager` (ICar tx 77) → `ICarDisplay` (tx 3), the display's `CarUiInfo`. The host
  returns minimal, internally consistent placeholders for all of these.

`CarInfo`, `CarUiInfo` and `DrawingSpec` are GMS **SafeParcelable** objects with no class in the
SDK, so the host writes their parcels by hand ([SafeParcel.kt](../app/src/main/java/net/prototux/aare/host/projected/SafeParcel.kt)):
a `20293` header, a back-patched size, then tagged fields (inline 4-byte values, or length-prefixed
strings/parcelables). The field layouts are in
[ProjectedProtocol.kt](../app/src/main/java/net/prototux/aare/host/projected/ProjectedProtocol.kt).

## The permission wall

The projection service runs a check on **every** transaction: the binding host must be Google-signed
or hold `android.permission.CAPTURE_VIDEO_OUTPUT`. That permission is `signature`, so an ordinary
app can't hold it, and the first `onSetup` is rejected with a `SecurityException` on a stock phone.

The protocol code here is identical whether or not that check passes. Getting past it needs root:
the AARE Xposed module grants exactly that one check to AARE's uid inside the Maps process (see
[xposed.md](xposed.md)). There is no non-root path — a shell/Shizuku process can't bind the service
at all, because ActivityManager won't bind a service for a process it doesn't track as an app.

## The code

| File | Role |
| --- | --- |
| `host/projected/SafeParcel.kt` | writes the GMS SafeParcelable wire format |
| `host/projected/ProjectedProtocol.kt` | descriptors, transaction codes, `DrawingSpec`/`CarInfo`/`CarUiInfo` builders |
| `host/projected/ProjectedHost.kt` | binds the service, serves the callback/`ICar`/display Binders, drives setup → start → resume, forwards input |
| `host/projected/ProjectedAppCatalog.kt` | the known projection apps installed on the phone |
| `host/projected/ProjectedAppScreen.kt` | a `SurfaceView` on the car screen whose surface the host draws into, with touch forwarding |

## Remaining work (needs a device)

- **Car-screen wiring.** `ProjectedAppScreen` is self-contained, but putting a projected app in the
  launcher grid and routing `CarScreen` to it (alongside the Car App Library path) is not wired yet.
- **On-device validation.** The `CarInfo`/`CarUiInfo` field layouts are minimal placeholders from the
  observable format; they may need fields filled in before Maps draws. Verify on a rooted phone with
  the Xposed module enabled for Maps.
- **Coordinate mapping** for touch when the surface isn't 1:1 with the view, and **key** input.
- **Audio and location**: route Maps' audio to the car channel and feed the car's GPS into Android's
  mock location provider so the map follows the car.

## Waze is a different path

Waze uses the **public** Car App Library (`WazeCarAppService`), so it needs none of this. It is
rejected only by its host allowlist (`HostValidator`), which the Xposed module's `isValidHost` hook
clears for AARE. Once that hook is active, Waze should appear and connect through AARE's existing
Car App Library host.

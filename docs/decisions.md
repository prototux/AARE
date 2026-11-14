# Decisions

Design decisions that aren't obvious from the code, newest last. Each says what was chosen and why.

## Projection margins: explicit UiConfig margins win (second audit)

When the head unit sends `UiConfig.margins`, they replace `width_margin`/`height_margin` instead of
adding to them, as the official phone does: head units keep sending the old fields for older
phones, so adding both would count the margins twice. Without explicit margins, `width_margin` and
`height_margin` are split evenly around the frame. See `car/ProjectionLayout.kt`. Approved by the
owner.

## Navigation focus: asked once per route (second audit)

The phone asks the car for projected navigation focus once when a route starts (a hosted app's trip
or a phone app's guidance), not at every guidance update. A driver who switches to the car's own
navigation mid-route isn't pulled back at the next turn; the next route asks again. See
`CarSession.guidanceChanged`.

## Exit to car (issue #46)

The car launcher's last entry, "Exit to car", and a long press on the car's HOME key send a
`VIDEO_FOCUS_REQUEST` for native focus (`VideoChannel.requestNativeFocus`). The phone doesn't drop
focus itself: it waits for the head unit's `VIDEO_FOCUS_NOTIFICATION`, so a head unit that refuses
leaves projection running and in sync. While the car shows its own UI the encoder is suspended but
the session stays up; the head unit's projection icon brings the screen back. The entry exists only
in a car session (not the on-phone preview), sits outside the user's app order, and the assistant's
"open app" tool doesn't match it.

## Deferred from the second audit (owner to decide or check)

- **Setup card and optional permissions (finding 46).** The "Finish setting up" card still waits for
  every runtime permission, the optional ones too (calendar, call log, contacts, phone calls). A user
  who refuses one of those for good keeps the card. Splitting required from optional permissions
  changes what counts as "set up", so it is left to the owner.
- **Translations (findings 50 and 66).** The car-screen and auto-reply texts are string resources now,
  but the new keys (`msg_autoreply_default`, `dash_menu_exit_to_car`, `media_error_*`, `tpl_*`,
  `carapp_error_*`, `common_ok`/`common_try_again`/`common_something_went_wrong`…) are English only
  in all 19 locales. Status: made translatable, translations pending. `msg_autoreply_default` matters
  most, as it is sent to contacts.
- **Rotary focus with the phone locked on Android 14+ (finding 6).** Leaving touch mode with
  `requestFocusFromTouch()` may be refused when the phone's own focused window isn't ours. To check
  on a phone: logcat for "Touch mode switch rejected" with the screen locked and a knob-only unit.
  If it is refused, the car UI needs focus targets that work in touch mode.

## Downloading Android Auto's certificate

*Advanced > Phone certificates > Download certificate* fetches `android-auto/latest/` from
github.com/iscle/android-auto-certificates (raw.githubusercontent.com), only on tap: no background
check, so AARE contacts that host only when the user asks. That repository is a third party, so the
download is accepted only when its chain holds the pinned Google Automotive Link CA, the CA signed
the leaf, and the key matches the leaf (`CertificateDownload.check`). Anything else couldn't pass a
Google-library head unit anyway; the checks keep junk or a swapped file out of the list. A
download replaces the previous one (marked by a `downloaded` file in its directory) because entries
sort by name and an older copy would be offered first. Rejected: bundling the files in the APK
(AARE doesn't ship Google's key) and a periodic background refresh (network traffic the user didn't
ask for). If Google ever replaces the CA, the pin needs an app update.

## Xposed host-shims module (optional, rooted phones)

`xposed/` is a standalone libxposed module (a separate APK, not part of the app, no app dependency
on it). It lets AARE pass for Google's Android Auto host through hooks, for users already on LSPosed,
as an alternative to the Magisk privapp module and the consent dialog. It adds no capability AARE
lacks otherwise. See [xposed.md](xposed.md).

Design choices:
- **Separate module, built on demand, not released.** Hooks touch app and AOSP internals that break
  between versions; keeping it out of the app APK and the release keeps that fragility away from
  normal installs.
- **libxposed (`io.github.libxposed:api`, `compileOnly`), not legacy XposedBridge.** The modern API
  with a declared scope; the framework provides it at runtime.
- **Every hook gated on AARE's package/uid, and falls through to the original otherwise.** A real
  Google session is never altered; no other app or user is touched.
- **System hooks only cover framework-level checks.** Natively enforced permissions (audio capture
  in audioserver) still need the native grant, so the Magisk module stays the way to get system
  audio. The permission hook is best-effort and skips unknown system_server shapes rather than
  risking a boot loop.

Status: unverified on a device; the PR is a starting point. Open question for the owner: whether to
ship it as a release artifact later, or keep it build-it-yourself.

## Touchscreen calibration

For head units whose touches still land in the wrong place after the automatic mapping
(`TouchMapper`), *Car screen > Touchscreen calibration* lets the user measure the car: set the
visible area from the phone (an L marks each corner on the car screen), touch 4 targets, then a
centre target to confirm. The 4 raw touch-downs give a least-squares affine map from raw panel
coordinates to the frame, which covers a wrong range or offset, swapped or mirrored axes and a
slight tilt.

Design choices:
- **The phone drives every step.** The car's touches can't be trusted before calibrating, so while
  the targets are up they go to the tool only, never to the car UI. Refused while driving.
- **Nothing is kept without a confirming touch.** The new map must put the centre tap on the
  centre target within 15 s; a miss, a timeout, a cancel or a video restart keeps the mapping in use
  before. A tap that doesn't fit the other 3 is asked again.
- **Saved per head unit and per video resolution.** Keyed by the head unit's name, like the known
  cars, and only applied at the resolution it was measured on: some units report touches in pixels
  of the video they are sent (Citroën's Marelli IVI2020 declares an 800x480 panel but reports in
  pixels of the visible 1920x640 area), so another resolution needs its own calibration.
- **It wins over the automatic mapping and `vehicles.json` margins.** It is what the user measured
  on that car; *Reset to the car's values* removes it.

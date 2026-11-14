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

## Car screen themes and layouts

The car screen's look is split in two settings: the **theme** (`ui/theme/CarThemeSpec`: day and
night colours, the colours Material has no slot for, type, shapes, app icon shape, sizes) and the
**layout** (`ui/car/CarLayout`: the taskbar with dashboard cards, or the dock with app pages). A
**style** picks both at once; a mix shows as "Custom". Built in: AARE, Android Auto and CarPlay.

- Themes are Kotlin values, not files: new themes come as pull requests, so each is reviewed. A
  theme is plain data, so reading themes from files stays possible later.
- `CarThemeContrastTest` holds every theme to WCAG AA in day and night: 4.5:1 for text, 3:1 for
  icons, app status colours and white on the accent (it only fills icon buttons and big labels).
  A faithful colour that fails gives way to a darker one, commented in the theme.
- Android Auto and CarPlay are reproduced from Google's and Apple's published design rules and
  system colours where they exist, else measured from their screenshots or estimated (each value
  says which). Their fonts (Google Sans, San Francisco) can't be bundled: the system font stands in.
  No code or assets were taken from other projects.
- Android Auto stays black in the day, as the real one does; the car apps still get the car's
  day/night signal, so a map turns light in the day.
- The Material You theme takes all the phone's colours, surfaces included, light in the day and
  dark at night (`CarThemeSpec.phoneColors`, applied by `forPhone` when the settings are read). It
  has no call or status colours, so it uses AARE's by day and Android Auto's by night. Its
  contrast is Material 3's to guarantee: the test checks its Material 3 baseline stand-in.
- The accent colour is its own setting (`CarAccents`): the theme's, the phone's, or a fixed colour
  with hand-picked tones for dark and light surfaces. It swaps only a theme's accent roles. On a
  single-colour theme (`singleColor`: Night, Retro) it colours everything (`tinted`): black,
  surfaces faintly lit, text and app icons in it. Calls and the colours apps use for warnings keep
  their hues there, so a warning still reads as one. The contrast test covers every theme with
  every accent.
- Retro is its own theme rather than an option of Night: a colour alone doesn't make an old
  screen. It adds the phone's monospace font, a glow around text, square corners and outlined
  cards. Its clock is on a seven-segment display: DSEG7 Classic v0.46 (SIL OFL 1.1, approved by
  the owner), bundled in res/font with its licence in assets/licenses, so it ships in the APK.
  The font has digits and a few capitals only, so it is used for the clock and nothing else.
- Android Auto (2019) has its own layout (`ClassicShell`): a bottom bar and the app grid as home.
  It reuses the taskbar's content area, so rotary focus and screen changes behave the same.
- The cluster display follows the main screen's theme and day/night colours.
- Existing users keep their look: the old CarPlay style maps to the CarPlay theme on the dock,
  everything else to AARE on the taskbar. The old `car_style` key is only read.

## Car screen mockups (Paparazzi)

The README's style pictures are mockups drawn on the JVM by Paparazzi (Cash App, Apache-2.0,
2.0.0-alpha05.1, approved by the owner), from `app/src/mockups/.../CarScreenMockups.kt`:
`CarShell` with made-up state (apps, music, an event), generated icons and album art, and a drawn
stand-in for the navigation app's map. `tools/mockups.sh` renders them and copies them to
`docs/screenshots/styles`. The README says they are mockups and that a real screen can differ.

- Opt-in: the plugin and the mockup sources are only added with `-Pmockups`, so ordinary builds,
  unit tests and CI don't carry Paparazzi. It also fails with the configuration cache in this
  project (it reads the generated phone-identity assets before they exist), so the script turns
  the cache off for that one build.
- Not a regression test: the images aren't compared in CI. Re-run the script after a UI change
  that the README pictures should show.
- 1280×720 at 160 dpi (1 dp = 1 pixel), a common head-unit size; the phone status icons show
  layoutlib's defaults (no signal, battery 0), since there is no phone.

## Configurable dashboard

The dashboard is an ordered list of cards (`DashCard`: map, music, next calendar event, latest
notification, weather), each shown or hidden and moved up or down in the settings, in the car and
on the phone. No free placement or sizes: car screens range from 800×480 to 2560×720 and
portrait, so the screen shape still decides the arrangement (`dashboardSlots`), and the order only
decides which card goes where: the first gets the big space, the others fill what is left (3 cards
at most; 2 on small screens). A card with nothing to show gives its place to the next, the map
stands in when nothing is left, and the last card can't be hidden.

- It replaces the "music first" and "next calendar event" switches; their values give the first
  list. The calendar is read only when its card is shown; the weather when it is next to the clock
  or its card is shown.
- With no event, the latest notification now fills that slot on ordinary landscape screens too.


## Setup through Shizuku

When Shizuku runs and allows AARE, the "Finish setting up" card offers "Grant AARE's permissions
with Shizuku", and the Connection page offers to stop Google's Android Auto taking the car. A small
Shizuku user service (`setup/ShellSetupService`, process suffix `setup`, removed after each task)
runs the commands as the shell. See `setup/ShizukuSetup.kt`.

- No call takes a command line: the service only builds the commands in `ShizukuSetup`, for AARE's
  own package and the caller's Android user (`uid / 100000`, the user AARE runs as). It runs
  `pm grant` only for a permission the setup asks for on that release, `cmd notification
  allow_listener` only for AARE's listener, and `pm disable-user` / `pm enable` only for Android
  Auto. Each command and its result go to the connection log.
- Android Auto: opt-in, behind a confirmation dialog, `pm disable-user` only. It is never
  uninstalled and its data is never cleared, and "Enable Android Auto again" (`pm enable`) undoes it.
  The state shown comes from PackageManager, not from the command's output.
- Mock location (an app op) and Do Not Disturb access are not granted: the setup card doesn't ask
  for them either.

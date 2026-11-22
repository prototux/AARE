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
- *Map always big* (off by default) is the one exception to the first card getting the big space:
  with it on, the map keeps the big space wherever it is in the order and the other cards fill the
  rest in order, so the music can come first with a big map, as with the old "music first" switch.
  Its side in the landscape layout follows the order too (first only when the map is first), so a
  card that comes and goes before it doesn't move it.

## Wireless bootstrap and USB: compatibility first

Rule: nothing may break a head unit that works today, so every change to the WPP exchange either
only adds a fallback or only applies after a failure.

- Version response: always SUCCESS, echoing the head unit's version (1.0 when it sends none). A
  stricter answer (refusing WPP 2, 3 and 7+, or below 4.5 head units whose Wi-Fi channels the phone
  can't use) is only logged. The channel check takes every frequency of the Wi-Fi bands the phone
  supports: apps can't read the channels it may use where it is.
- Our own WifiStartRequest is never sent on a first attempt. Only after an attempt where the head
  unit answered the version exchange and then sent no WifiStartRequest before the message timeout,
  the connector's redials to that head unit ask it to start (5 s after the version exchange, again
  every 2 s for up to 50 s while it answers "not yet" or "already started").
- WifiInfoRequest is repeated every 3 s; the usual message timeout still decides when to give up.
- SSID and passphrase lose one pair of surrounding quotes below WPP 4.5; if joining that way fails,
  the join is tried once more with them as sent.
- First message after the RFCOMM connect: 12 s, 3.7 s on the first attempt with a wireless adapter
  (Bluetooth name containing "Intercooler" or "AndroidAuto-"), so it is redialled quickly: at once,
  without the 10 s wait and without using up one of the two retries for a car that isn't ready.
- RFCOMM frames over 1024 bytes are logged, not refused.
- Bad endpoint: reported as WifiConnectionStatus(INVALID_HOST); port 0 is accepted below WPP 4.5 (it
  fails at the TCP connect), a port outside 0-65535 is refused. WifiConnectionStatus carries an
  error text only for PHONE_WIFI_DISABLED, written for the driver.
- The wireless AAP socket keeps its big buffers and gets no read timeout: the ping watchdog allows
  20 s of silence for head units that go off channel to scan. TCP keepalive is off: its first probe
  would come after hours, the watchdog notices a dead link much sooner.
- USB accessories are matched by model only ("Android Auto", "Android Open Automotive Protocol"):
  some head units send another manufacturer. A failed openAccessory is retried once after 500 ms.

## Steering-wheel key remapper

A user remap is checked once, in `CarSession.onKey`, before the big `when (code)` that gives each
key its default behaviour; a match returns early (on both the down and up event, not just up) so
a remapped key never also falls through to its old behaviour. The remap only fires on key-up, like
most of the keys it can replace.

- Keys are recorded (code to `KeyEvent.keyCodeToString`) as they arrive, regardless of whether
  they're mapped, so the phone's chooser can show every button this car has — including AARE's own
  synthetic codes for dedicated car buttons (telephone, radio, media, navigation, turn-card),
  which aren't real `KeyEvent` constants and so get a less friendly name from
  `keyCodeToString`, but are still choosable.
- The mapping and the seen-keys list share one encoding (`CarKeyMapCodec`): a `SharedPreferences`
  string set of "code\tvalue" entries, decoded defensively (a malformed entry is dropped, not a
  crash) since it's as easy for a future version to add a new field here as for a hand-edited
  preferences file to break one.

## Duck the music under AARE's own speech, don't pause it

`CarNotificationListener.speak()` (messages read aloud) tagged its TextToSpeech output
`USAGE_MEDIA`. Two consequences: on the phone itself, nothing asked for audio focus at all,
and for car audio (`CarSession`'s audio-policy/Shizuku capture), `USAGE_MEDIA` is one of
`CaptureMethods.MEDIA_USAGES`, so AARE's own speech shared the "media" channel, whose `Focus`
asks the car for plain `AUDIO_FOCUS_GAIN` (full, pausing other car sound) rather than the
"guidance" channel's `AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK` already used for phone navigation
voice.

- Retagged it `USAGE_ASSISTANT` (one of `CaptureMethods.GUIDANCE_USAGES`): when the car exposes a
  guidance channel, AARE's own speech now rides the channel that already asks to duck, not pause.
- Added an actual phone-side `AudioFocusRequest` (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) around the
  TTS calls, tracked with an utterance-progress listener so it is requested once per burst of
  speech and abandoned once every queued utterance has finished (or failed) — not just on every
  individual `speak()` call, since `QUEUE_ADD` can queue several in a row.
- `TextToSpeech.speak()` refuses an utterance (no listener callback ever follows) while the engine
  is still starting, or after it failed: messages asked for during start-up wait for the engine,
  and a refused or dropped utterance counts as done at once, so the focus (and the ducking) can't
  outlive the speech. The count and focus are behind one lock: the listener runs on TTS's thread.
- AARE has no listening (speech-to-text) of its own to apply the "exclusive transient focus while
  listening" half of the request to: `CarRuntime.openMicrophone` only relays the car's microphone
  to a connected car app (an assistant such as Lapin), which requests its own focus if it wants to.

## Video from media apps: the app itself, parked only

"Show video" on the car's media screen launches the media app on the car screen with the
standalone-app launcher (root or Shizuku), instead of a video path of AARE's own: media sessions
carry no video, and the app's own player already handles its streams and DRM. It is opt-in, under
the standalone apps' switch. The app's screen sits above AARE's controls, or fills the app area
(not the rail). When driving starts, the app stays on its display for its sound, under the cover
art. Parking again hands the display a new surface rather than relaunching the app, because a
relaunch would end the video.

## `aare://` links for assistants without a code path into AARE

`CarControlProvider` lets an assistant running on the car screen call car tools directly, but an
assistant that only answers in a notification or a chat link had no way to act on the car. Added
`AssistantLinkActivity`, an exported activity for `aare://navigate|play|open|call` links, backed
by a pure parser (`AssistantLinks.parse`, unit-tested) that doubles as the whole allowlist.

- `java.net.URI` + manual query decoding instead of `android.net.Uri`: this module's unit tests
  don't pull in Robolectric, and the Android stub jar's `Uri` returns nulls without it.
- Only the chosen assistant may open a link (review fix): no `BROWSABLE`, so no web page can
  start it (AARE's browser included), and the caller Android reports (`getCallingPackage()`, else
  the system-filled referrer, never `EXTRA_REFERRER`) must be the package `Assistants.authorized`
  returns, the same app `CarControlProvider` accepts. Rejected: trusting the allowlist alone with
  any caller allowed, which let any app or page start navigation on the car screen.
- `navigate`, `open` and `call` wait for the driver to confirm on the car screen; only `play` runs
  straight away. A link also needs an already-running car session.
- The log records the action kind, never the link (destination, phone number).
- `open`/`play` accept `app`/`action` with `q` as a fallback alias, so the three link patterns in
  the feature request (`?q=`) and the more specific parameter names both work.

## Plain-language diagnostic trouble codes

The car data viewers (phone and car) showed the car's diagnostics sensor raw, as hex bytes. Added
`DtcCodes`, decoding the two bytes per code (AAP's `DiagnosticsData.dtc` has no documented format
beyond "bytes") per the standard SAE J2012 / ISO 15031-6 bit layout into the familiar P/C/B/U +
4-digit codes, and a catalogue AARE ships (`app/src/main/assets/dtc/generic.json`, ~350 entries,
written from scratch for this PR) for the SAE-generic ones.

- Classification follows SAE J2012's ranges per system: P0, P2 and P34-P39 generic, P1 and
  P30-P33 manufacturer-specific; for C, B and U, 0 and 3 generic, 1 and 2 manufacturer-specific.
  (A plain "second character 0/2" rule got B2/C2/U2 and P34-P39 wrong.)
- The two-bytes-per-code layout is the usual OBD-II one, but AAP doesn't document it: it's a
  guess. So both viewers keep each code's raw bytes next to it ("P0128 (01 28) — ..."), and a
  zero pair is taken as padding rather than shown as "P0000".
- The catalogue is read off the main thread, once, the first time a viewer has codes to show;
  until then generic codes show without a description.
- Manufacturer-specific codes show their family ("manufacturer-specific powertrain code") and no
  description: AARE has no way to know what a given manufacturer assigned to e.g. P1300, and
  guessing would be worse than saying so.
- The catalogue is deliberately smaller than "every code ever assigned" (~350, not thousands):
  common P0 sensor/misfire/emissions/transmission ranges, the best-known P2 codes, the common
  U0 network-loss codes, and a handful of representative C0/B0 ones, covering what a driver is
  actually likely to see.
- Both viewers show one line per decoded code ("P0128 (01 28) — Coolant thermostat below
  regulating temperature") instead of the previous single hex-byte row. The descriptions AARE
  writes itself (manufacturer-specific, unknown) are translated; the catalogue's are English.

## Calls through messaging apps from the car's contacts

The car's contact list also offers a call through a messaging app (`VoipCalls.CALL_MIME_TYPES`:
WhatsApp and WhatsApp Business, by their `vnd.com.whatsapp.voip.call` and
`vnd.com.whatsapp.w4b.voip.call` contact rows) when that app has a self-managed phone
account (it hands its calls to Android's telephony; Android 11+). The entry shows the app's name and
icon with the phone number of the app's own copy of the contact; a tap opens that contact row in
the app (`ACTION_VIEW` on the data row, typed with its MIME type), which starts the call. If the app
hasn't the microphone permission, the car screen says so and a phone notification opens its
settings; the call still starts. Google-only calling apps aren't listed. Not done: VoIP calls
don't show in the car's in-call card (it follows the telephony call state). Android drops the app's
call screen without an error when the phone is locked, or (Android 10+) when AARE has no activity
in front and may not draw over other apps; `VoipCalls.place` checks for those first and reports the
call as failed, so the car says to call from the phone rather than show a call that never starts.

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
- The one-tap grant skips each permission's own dialog, so its button says beforehand what it
  grants (microphone, location, contacts, call log, calendar, phone calls, nearby devices, local
  network, notifications and notification access): nothing sensitive is granted without the user
  having read it on the button they tap.
- Android Auto: opt-in, behind a confirmation dialog, `pm disable-user` only. It is never
  uninstalled and its data is never cleared, and "Enable Android Auto again" (`pm enable`) undoes it.
  The state shown comes from PackageManager, not from the command's output.
- Mock location (an app op) and Do Not Disturb access are not granted: the setup card doesn't ask
  for them either.
## Car text size and control size

Two independent settings scale the car screen: text size (a font-scale multiplier on
`LocalDensity`) and control size (a multiplier on `CarDimens`' touch targets, rail width, gutter
and icon size; corner radii are a theme's shape choice, not a control size, so they're untouched).

- `CarTheme()` always resets `LocalCarDimens` to `theme.dimens` (themes are data, swapped whole),
  and the dark-bar rail calls `CarTheme()` a second time for night colours. Scaling `theme.dimens`
  directly inside `CarTheme()` from a new ambient `LocalCarControlScale` (set once in
  `CarRuntime.Root`) survives that nested call; overriding `LocalCarDimens` from outside `CarTheme`
  (as the dashboard-cards editor's `touchTarget` parameter does) would not.
- Text size instead overrides `LocalDensity` once at `Root`'s top: Compose's `MaterialTheme`/
  `CarTheme` never touch `LocalDensity`, so that override already survives nested calls without
  the same trick.
## Night mode from the phone's light sensor

A setting lets "Car's day/night" take its reading from the phone's own ambient light sensor
instead of the car's signal. The decision is hysteresis, not a single threshold: separate
night/day lux thresholds with a dead zone between them, and a new reading only takes effect once
it has held for a few seconds (LightNightDecider). Without this, a streetlight passing by or a
hand briefly covering the sensor would flicker the car screen's whole colour scheme.

- No sunset/time-of-day option: AARE has no sunset calculation anywhere (checked DashboardInfo's
  weather code), and adding one for this alone would be a new, unrelated feature.
- The setting only shows under "Car's day/night": a fixed Dark/Light theme has no night mode to
  source, so showing it there would imply it does something it doesn't.
- The sensor is started and stopped with the setting (and always released when the car session
  ends), not left registered when unused: a light sensor is cheap, but there's no reason to keep
  listening when nothing reads the result.
- The decider starts with no state and reports its first stable reading, so a bright start turns
  the screen light without waiting for a change. The light sensor only reports changes, so a timer
  ends the stability window when no further event comes. The sensor only runs while the colours
  follow day/night and the source is the light sensor.

## Wireless handshake for aftermarket adapters

The Bluetooth bootstrap has two answer orders (`WirelessHandshake`): AARE's own (serial and
phone_info in the version response, pings throughout), which strict built-in head units need, and
the official phone's exact order, which some aftermarket adapters need (status-only answers, the
start request acknowledged before the credentials are asked for, no phone pings). Adapters also get
the head unit's own version back in the AAP version exchange. "Wireless compatibility" picks one.

- The adapter order is opt-in: only the Adapter choice turns it on. Auto (the default) is the
  standard order, exactly the old behaviour, except for a car that last reached service discovery
  with the adapter order (remembered per Bluetooth address, 32 at most), which only happens after
  the driver chose Adapter: a driver with both kinds of car sets Adapter once, connects the adapter
  car, and goes back to Auto. Built-in forces the standard order even for such a car.
- Rejected: guessing the order from the Bluetooth name, and trying the other order after a failed
  attempt. A name is chosen by whoever sells the box (or renames it), and a built-in head unit that
  is slow to start would get an order it refuses; a wrong guess costs a connection the driver
  cannot explain. The adapter names #232 uses for its short first-message timeout are the only
  adapter-name list.
- One `WirelessConnector` serves a whole series of attempts (the "car not ready yet" retries), as
  before: it carries state from one attempt to the next (#232's redial that asks a waiting head
  unit to start, an adapter's one quick first try), and the handshake doesn't change within a series.
- No error text goes to the head unit: a failed WifiConnectionStatus carries the status only (the
  text is for the driver, and ours is a log line).
- The per-car memory moves to `KnownHeadUnits` (#233) once that is merged.

## Rail apps: a flat chosen list replaces the dynamic slots, not alongside them

When the user picks apps for the rail on the phone (`Settings.railApps`), the rail shows exactly
that list, in that order, clickable to open each app directly — it stops binding the nav/media
slots to whatever is "active" and showing recents. Mixing both (the user's picks plus the dynamic
nav/media/recent behaviour) would make the setting's effect unpredictable. An empty list (nothing
chosen, the default) keeps today's dynamic rail untouched. Resolution happens at render time from
live `CarUiState.apps` (`resolveRailApps`), so an uninstalled app's key is skipped without special
handling and nothing needs to be recomputed when the app list changes.

## Rail widgets: battery split out of the network icons, which stay unconditional

`PhoneStatusIcons` (wifi/signal + battery, together) stays for the layouts that already show it
as one unit (the dock, the classic bottom bar). The configurable rail (`Settings.railWidgets`)
only lists what the feature asked for — clock, outside temperature, phone battery, weather — so
`NetworkStatusIcons` (wifi/signal) was split out of it and kept as unconditional rail chrome next
to the chosen widgets, rather than inventing a "network" widget nobody asked to configure or
folding wifi/signal into the "battery" pick where turning battery off would silently drop them too.

## Outside temperature: the car's sensor is always Celsius, shown with an explicit unit

The fetched weather already carries the right symbol for the phone's locale (°F in the US...), but
AAP's `Environment` sensor has no locale: converting it would need a units setting nobody asked
for. The temperature widget shows the car's own reading as "<value>°C" when the car reports one,
falling back to the weather (with its own unit) only when it doesn't.

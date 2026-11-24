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

## Calendar agenda: a day-only widening of the query window, gated on the count

`DashboardInfo.nextEvents(context, limit)` only widens its ContentResolver window (12 hours to a
week) when `limit > 1`. At the default (1), the window and the single returned event are exactly
what `nextEvent` returned before, so turning the setting on is the only way to see anything new —
nothing shifts for someone who never opens the new setting. The day label itself
(`labelAgenda`, in `car/DashboardInfo.kt`) takes a clock and zone instead of reading the real one,
so it's a plain JVM-testable function; `CalendarAgendaCard` calls it with the real clock and zone
and still renders a single event exactly as `CalendarCard` always has, so the on-screen look at the
default setting is unchanged, not just the data behind it.

## Reconnecting a wireless link that dropped

A wireless session that reached the car screen and then failed without a bye-bye from either side
is reconnected by itself: 5 single attempts, after 3, 6, 9, 12 and 15 s (`WirelessReconnect`),
apart from the initial connect's "car not ready yet" retries. A Disconnect, the car's bye-bye and
the phone's Stop all say goodbye first, so they are never reconnected.

- Ignition off drops the link without a bye-bye too; it is told apart by the car's Bluetooth
  disconnecting (ACL_DISCONNECTED) up to 20 s before the drop or any time after it. Further back
  doesn't count: some adapters hang up their Bluetooth link right after the bootstrap.
- A reconnected session that drops again within a minute continues the same budget, so a link
  that keeps failing is not reconnected forever.

## One-tap wireless: companion device association

On Android 13+ the user can associate a car already used wirelessly (its Bluetooth address is a
trusted head unit's key) as a companion device (`CarCompanion`). Android then binds
`CarPresenceService` when that car's Bluetooth connects, which starts the wireless connection,
and lets AARE start its foreground service from the background (no "tap to connect" notification).

- The association is with the car's Bluetooth address. Android approves a WifiNetworkSpecifier
  request by itself only when an association matches the access point's MAC (the BSSID), which
  head units rarely share with their Bluetooth address (`WifiApproval.Way.COMPANION` handles the
  ones that do).
- Otherwise, opt-in and only for associated cars, a Shizuku helper (`ShellWifiService`) runs the
  Wi-Fi service's shell command `cmd wifi network-requests-set-user-approved <pkg> yes` right
  before the request, and `no` once it is answered. That approval is per app, not per request: while
  it is on, every network request AARE makes is approved, and it would stay on if AARE were killed
  before `no`. So `no` runs in a finally (also after a failed `yes`), and a note written before
  `yes` (`shell_wifi` preferences) makes the projection service take a leftover approval back when
  it next starts (`ShellWifi.recover`, never while a join holds one; the note stays until `no`
  works). The network request stays AARE's own (app-only, local-only, never the default network).
  If the command fails, Android asks as before.
- No automotive companion profile: it needs a permission only Android Auto's role holder gets.
## Phone assistants on the car screen (no car app)

The voice button's fallback for an assistant without a car app (Google Assistant and most others)
opened it on the phone (`Assistants.startOnPhone`). An opt-in setting
(`Settings.assistantOnCarScreen`) instead shows it on the car screen through the same virtual-
display launcher as standalone apps (`AppLauncher`), in a bar positioned like the car-app assistant
bar rather than full screen.

- Off by default (today's behaviour kept); only offered when the launcher is available (root or
  Shizuku), same gate as standalone apps.
- It opens the assistant's voice UI (`ILaunchHost.startAssistant`: `ACTION_VOICE_COMMAND`, else
  `ACTION_ASSIST`, as `Assistants.startOnPhone` does), and the app's main activity only if it has
  neither, not `getLaunchIntentForPackage`'s home screen.
- Parked only, like other launched phone apps: it is an ordinary phone app mirrored with injected
  touches, not a car template. While driving (`ui.drivingRestricted`, so passenger mode lifts it)
  the voice button opens the assistant on the phone, as with the setting off, and a bar left open
  is closed when driving starts.
- On a launch failure (no root/Shizuku binder, nothing to open) it falls back to
  `Assistants.startOnPhone`, same as when the setting is off.
- It shares `LaunchedAppSurface` with `StandaloneAppScreen`, and installs no Back handler: the
  car's Back closes the bar first.
## Car microphone for phone apps

Opt-in (`Settings.carMicForPhoneApps`, off by default), Shizuku on Android 10+. The Shizuku helper
registers, as the shell, an audio policy with an injection (recorder) mix matched to the
DEFAULT, MIC, VOICE_RECOGNITION and UNPROCESSED capture presets, and watches the phone's recordings
(`AudioManager` recording callbacks: the shell sees each app's uid). When another app records with
one of those sources, AARE opens the car's mic (shared with car apps in `MicRouter`), pipes its PCM
to the helper, and the helper plays it into the mix; 1.5 s after the last such recording stops, the
car's mic closes. See `audio/PhoneAppsMic.kt`, `CarMicForApps.kt`, `ShellMicInjector.kt`.

- Lazy, not always on: the mix's track plays only while the car's mic delivers. Android moves a
  matching recording to the mix only while its track plays, so the recording starts on the phone's
  mic and moves to the car's a moment later (the input is reopened under it; apps on AAudio may see
  a disconnect). Keeping a silent track playing for the whole session would avoid the move, but
  apps would record silence whenever the car refuses or lacks a mic, and the phone would run an
  audio path all drive long.
- Not VOICE_COMMUNICATION (calls and VoIP keep their own route), not CAMCORDER, not hotword.
  AARE's own recordings are excluded by uid where the platform's recorder mixes take a uid rule;
  where they don't, they could only ever hear the car's mic, so there is no loop.
- No voice-session message to the car for phone apps (car apps send one): it announces an assistant
  session, which head units may answer with their own UI or by lowering their sound; a voice note
  in a messenger doesn't call for that. To revisit if a car only sends its mic during one.
## Reply by voice, opt-in

The messages screen answers with the car keyboard only when parked. *Reply by voice* (off by
default) adds a button that dictates the answer, while driving too, as Android Auto does:

- Listening uses the phone's own speech recognizer (`SpeechRecognizer`, the one voice typing
  uses), so AARE handles no microphone for it: with *Car microphone for phone apps* on
  (`CarMicForApps`) the recognizer hears the car like any app, otherwise the phone's mic. No new
  permission (RECORD_AUDIO is already asked for in setup) and no new connection.
- Nothing is ever sent by itself: what was heard is read back ("…, send it to Anna?") and sent
  only on a spoken "yes" or *Send*. The answer is listened for once the read-back is over (so it
  doesn't hear itself); a "no" anywhere cancels and wins over a "yes", so "no, don't send it"
  never sends; anything else, or silence, leaves it to the buttons. The words are strings
  (`msg_voice_reply_yes_words` / `_no_words`, comma-separated) so each language gets its own,
  and the English ones always count. *Again* dictates anew, *Cancel* or back drops it. Late
  recognizer callbacks can't change a reply waiting for its answer, and a reply is sent once
  (`VoiceReplyStep`, unit-tested).
- While driving the dictated text isn't shown, like messages: it is read aloud instead.
- While listening AARE holds transient exclusive focus with the assistant usage, so the music
  pauses and the car goes back to it; the read-back ducks it like a message read aloud. A
  recognizer that neither answers nor fails is given up after 15 s, so the music never stays
  paused. Before listening AARE stops its own voice (*Again* during the read-back), so the
  recognizer can't transcribe it, least of all through the car's microphone.
- A weak signal (tunnels, driving), a server error or a busy recognizer offers *Again*; only no
  recognizer or no permission is final.
- It sends through the same reply action as the keyboard reply (`CarNotificationListener.reply`).

## Car apps' own colours

A Car App Library app can declare a car theme (`androidx.car.app.theme` meta-data on its service,
else on its application, else its application theme) with `carColorPrimary`/`carColorPrimaryDark`
and `carColorSecondary`/`carColorSecondaryDark`. CarColor.PRIMARY and SECONDARY in that app's
templates take those colours (day or night variant), for every app and API level. Everything else
on the car screen, and a pair the app doesn't set, keeps the car theme and its accent: the app's
colours only reach what the app itself asked to be coloured. The theme *style* an app can report
from Car App Library 1.9 (`getAppTheme`) only changes progress-bar end caps, which the library
version AARE builds against can't express, so it isn't asked.

## Car app action countdowns

In a row of actions (Pane, Message, SignIn and map pane buttons, a list row's actions) the default
action (`Action.FLAG_DEFAULT`) counts down 10 s, its button filling up, and then clicks itself.
In a row without one, a few listed apps (`TemplateQuirks`: Waze, Uber Driver, the library samples)
can give an action their own countdown with a title variant "TimedActionParams|duration:N"
(seconds, held to 3-60 s so an app can't make a button click itself before the driver can read it,
or wait forever); only the first such action of a row counts down, and those variants are never shown as
text, for any app. The countdown is time-based, not an animation, so phones with animations off
don't click the action at once; a template refresh with the same action keeps counting.
Alerts keep their own timeout, not their default action's countdown.

## Car app screens: refresh or new screen

A new template from a car app is a refresh of the screen on show (keeping what's typed, pan mode
and the scroll position) when the screen stack has the same depth and screens and the top
template has the same class. For the apps in `TemplateQuirks.mapHeaderStartsScreen` (Waze from
versionCode 1030423, the library samples), a MapTemplate whose header title changed (or whose
header came or went) is a new screen too. Other content checks (a pane's header, its loading
state, the rows) aren't made.

## Home actions: one shared connection, secrets apart, plain http only at home

Smart-home actions and tiles (`docs/home-actions.md`) run through one process-wide `HomeHub`, shared
by the car session and the on-phone preview: two connections with the same MQTT client id would
knock each other off the broker. Arrival triggers run only for a car session. The MQTT client is
AARE's own (MQTT 3.1.1 without QoS 2 or wills): a few hundred tested lines instead of a new
dependency. The broker password and every header value go to a private preferences file of their
own (`home_secrets`), not the main settings, so a settings export can't carry them whichever way it
is built; header values all count as secrets because that is where tokens go.

Plain `http://` (maintainer's decision): https stays the default, and http is allowed only to the
home network (localhost, `*.local`, RFC 1918, 169.254/16, loopback, `fc00::/7`), only when the HTTP
server's *Allow plain http on my home network* is on, with a warning. Public http stays refused.
Opening cleartext in the network security config was rejected: it can't be limited to address
ranges and would apply to the whole app (the browser too). Plain http goes instead through AARE's
own small HTTP/1.1 client (`PlainHttp`), to the very address that passed the check, so a name
can't pass as private and then connect elsewhere.

Arrival triggers (maintainer's decision): opt-in per action (off by default), and they never send
by themselves: the car screen always asks for one tap, whatever the action's *Ask first*, because a
position can be wrong and nobody watches the screen when it fires.

## Automatic Bluetooth pairing for calls (Shizuku, opt-in)

Builds on #229 (maintainer's decision): `BluetoothPairing` and `PairingCheck` handle the car's
authentication data (BluetoothAuthenticationData) and match it with Android's pairing request,
for everyone. This option only adds what an app can't do: when the car answers AARE's Android
Auto pairing request with "not paired" and the user turned it on (Android 12+), AARE starts the
bond itself (createBond, an app's own BLUETOOTH_CONNECT), and a Shizuku helper
(`ShellBluetoothService`) answers what `PairingCheck` accepted: setPairingConfirmation for a
matching numeric comparison (it needs BLUETOOTH_PRIVILEGED, which only the shell has here), or
setPin when AARE's own setPin was refused. The helper can do nothing else.

- Scope: only the address the head unit announced on its Bluetooth channel, checked again on every
  broadcast; nothing else is ever bonded or confirmed.
- A numeric comparison is only confirmed when `PairingCheck` found its passkey equal to the one the
  car sent, so a different device can't slip in under the car's address; a mismatch is never
  confirmed. Anything else is left to Android's dialog, which stays up (the pairing broadcast is
  not aborted) and is the fallback for every failure.
- On Android 10 and 11 the option does nothing (AARE uses Shizuku only on 12+), and the log says
  that is why.

## Android 6 and 7 supported (minSdk 23)

Maintainer's decision: lower `minSdk` from 26 (Android 8) to 23 (Android 6), so AARE installs on
older phones too. Android 8 stays the better-supported baseline; a one-time notice on the home
screen (`OldAndroidNotice`, MainActivity) tells Android 6 and 7 users what's limited there, with a
"Don't show again" choice (`Settings.oldAndroidNoticeDismissed`); OK alone shows it again next
launch. Behaviour on Android 8+ is unchanged — every fix here only adds a branch for `SDK_INT < 26`
(or rewrites something to work identically everywhere, see below), never changes what already ran
on 8+.

What the lower `minSdk` touched:
- **Notification channels (API 26) and `startForegroundService` (API 26).** `ProjectionService`
  gained `ensureChannel`/`notificationBuilder` helpers: a channel (and its own `Notification.Builder`
  overload) from Android 8, the deprecated single-argument builder with `PRIORITY_LOW` below that
  (same look, but it can't be muted separately from AARE's other notices in system settings — the
  one thing the notice calls out). `CertificateUpdates` and `VoipCalls` share the same helpers.
  Starting the service now goes through `ContextCompat.startForegroundService`, which already picks
  `startService` below Android 8.
- **`AudioFocusRequest` (API 26).** `CarNotificationListener`'s read-aloud focus now falls back to
  the deprecated `requestAudioFocus(listener, streamType, durationHint)`/`abandonAudioFocus` below
  Android 8; same `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` behaviour either way.
- **`AdaptiveIconDrawable` (API 26).** `MediaDiscovery`'s Spotify icon fix (a black backing layer)
  only runs from Android 8; below that Spotify's icon is used as shipped (no black backing, a
  cosmetic difference only).
- **`CompanionDeviceManager` (API 26).** Already gated well above the new floor (Android 13), so
  behaviour doesn't change; `CarCompanion` only needed `@RequiresApi`/`@ChecksSdkIntAtLeast`
  annotations so lint can see that its own `supported()` guard covers it.
- **`java.time` (API 26), without core library desugaring (a new dependency, and the owner's call to
  skip it).** `CalendarIcon.msUntilNextDay` and the calendar agenda (`DashboardInfo.selectAgenda`/
  `labelAgenda`, `DashboardWidgets`) were rewritten on `java.util.Calendar`/`TimeZone` instead of
  `ZonedDateTime`/`LocalDate`/`ZoneId`/`DayOfWeek`: a day is the epoch millis of its local midnight
  (comparable and orderable), `Calendar.add(DAY_OF_MONTH, 1)` keeps DST handling (a 23- or 25-hour
  day), and `AgendaDay.Later` carries a `Calendar.DAY_OF_WEEK` int instead of `java.time.DayOfWeek`.
  This runs the same way on every supported Android version, not just below 26; the existing
  timezone-edge-case unit tests were ported to the new types to check it. `NavigationUi`'s one
  `ZoneOffset` (building a fixed-offset `TimeZone` for a head-unit-supplied UTC offset) became a
  plain `SimpleTimeZone`, also unconditionally.

The Xposed host-shims module (`xposed/`) stays at `minSdk 26`: unlike the app, it hooks framework
internals that are no safer to assume down to Android 6 without testing against that framework,
and it isn't released, so there's no user-facing reason to lower it.

**Overriding Shizuku's own `minSdkVersion 24` (owner's decision).** `dev.rikka.shizuku:api`
declares `minSdkVersion 24` in its own manifest, which the manifest merger otherwise refuses
under `minSdk 23`; `AndroidManifest.xml`'s `<uses-sdk tools:overrideLibrary="rikka.shizuku.api" />`
lifts that check. This is safe here because the override only removes a build-time refusal, not a
runtime guarantee, and nothing in AARE calls into Shizuku below API 24 regardless: every call site
is either behind an explicit `SDK_INT` check at or above Shizuku's own floor (Android 12,
`CaptureMethods.shizukuAvailable`/`shizukuNeedsPermission`'s callers in `MainActivity`, the Shizuku
Wi-Fi approval and Bluetooth auto-pairing paths) or behind `Shizuku.pingBinder()` finding a live
binder — which it never can below API 24, because `ShizukuProvider` (the component that would set
that binder) is declared `android:enabled="@bool/shizuku_provider_enabled"`, false in
`values/bools.xml` and true only from `values-v24/bools.xml`, so it never starts with the app
there. The `AppLauncher`/`ShellBluetooth`/`ShellWifi`/`ShellAudio`/setup-helper `UserServiceArgs`
builders (plain object construction, not a platform call) are either behind the same checks or,
where a caller could reach them without one (`AppLauncher`, used by the root-or-Shizuku standalone-
apps launcher), built lazily so they're never constructed unless a Shizuku bind is actually
attempted.

## Background location, opt-in (#329)

A navigation app hosted by AARE keeps the position with the phone locked only if AARE's
foreground service has the location type. Since Android 14 a service started from the background
(AARE started by the car's USB or Bluetooth) only gets it when the app may use location all the
time, so AARE declares `ACCESS_BACKGROUND_LOCATION`. Declaring it grants nothing: the user turns it
on from *Location > Navigation with the phone locked*, which opens AARE's app settings (Android 11
and later only allow "Allow all the time" there). Without it nothing changes, and the connection
log says what to turn on when the location type was refused. Tested by a user on a real car with
the same one-line manifest change (#329).
## Launched apps while driving: a rule per app

Each launched phone app has a driving rule, stored by package (`launch/DrivingRules.kt`): parked
only (the default, for every app), audio only, or navigation. "Navigation" is offered only to
navigation apps (the phone screen's `KNOWN_NAVIGATION`, or an app whose category is maps): it shows
the app while driving and makes it the car's navigation app. There is no "allowed" rule for other
apps: a passenger who wants one while driving uses passenger mode (ignore driving), which lifts
every parked-only limit, these rules included, on the driver's responsibility. "Parked only" pauses
the app's media session as well as hiding its screen (before, the card said "paused" while the
sound went on), which is what tells it apart from "audio only". `StandalonePlaybackGuard` pauses
it when driving starts and again each time it starts playing until driving ends (an autoplayed
next track or video), whether or not its screen is open; not for a package that is also a hosted
media app, whose sound is allowed while driving.

## Phone navigation apps launched on the car screen

(From #244.) A launched phone app (root/Shizuku launcher) counts as the car's navigation app when
its driving rule is "navigation", which only navigation apps are offered (above). It then takes
the rail's navigation slot, receives "navigate to" as an `ACTION_VIEW` of the same `geo:` URI hosted
apps get (through the launcher's `view`, which only accepts `geo:`, as the helper starts activities
with shell or root rights), and stays on screen while driving, like the phone screen's navigation
apps. The dashboard's map card doesn't show its map: the launcher has one display per app, owned
by the full-screen view, and moving it between the card and full screen would restart the app and
its route at each switch. The card offers to open it, and shows its directions once it posts them.
No Home/Work shortcuts: AARE has none for hosted navigation apps either.
## Settings export and import

Exports and imports go through an allow-list (`SettingsBackup.ALLOWED`): each setting that may move
between phones, with the `SharedPreferences` type Settings.kt reads it with. A setting added later
stays out until someone adds it there on purpose.

- An import file is not trusted. An entry with an unknown key, a blocked key or another type than
  the allow-list's is skipped (and counted in the "imported" message), never written: an Int where
  Settings calls `getBoolean` would throw `ClassCastException` on every launch, a crash loop the
  user can only leave by clearing the app's data.
- `SettingsBackup.BLOCKED` is never exported or imported, though these are ordinary settings: the
  head unit server and its LAN, LAN-once, localhost and mirror options (network or other-app
  exposure), passenger mode (lifts the driving restrictions), root audio, standalone phone apps
  (root/Shizuku, behind a warning) and their driving rules, the other root or Shizuku options, the
  assistant's links and certificate auto-update (a third-party download). Each must
  be switched on by hand on the phone itself. A test checks the two lists never overlap.
- A file carrying a blocked key (hand-edited, or from a version that exported one) leaves it
  unchanged on import, in particular `hu_server_localhost`, `standalone_apps` and
  `cert_auto_update`; the import message names those keys in one line ("not imported, for
  safety"), apart from the unknown/invalid count, and `Settings.restore` refuses them a second time.
- Device-specific state (instance id, update and certificate caches, the phone's Bluetooth MAC,
  connection history, already-asked permissions) and anything secret (home-automation servers,
  MQTT hosts and users, tokens, passwords) are simply not allowed. Trusted head-unit certificates
  live in a separate preferences file (`PinnedHeadUnits`) and are never read here.
- A "custom" wallpaper is left out on import: the photo itself isn't in the file.
- Each value is tagged with its type rather than stored as a bare JSON value: the exported text has
  no int/long/float distinction of its own, so an untagged `180` wouldn't say which
  `SharedPreferences.Editor.put*` call it came from.
- The file is read and parsed off the main thread, and refused past 1 MB (a real export is a few
  KB). Failures show a fixed message and log only the exception's kind: a JSON error's message
  quotes the file. After an import the settings screen is rebuilt (`key` on a generation counter),
  so no card keeps showing, and on its next tap writing back, a value from before the import.

## Split screen: which app can take the second pane

`CarAppConnection.surfaceAvailable` only ever has one surface owner per connection (an onSurfaceDestroyed before a new onSurfaceAvailable), and an app is never in both panes, so two *different* hosted Car App Library apps (two `CarAppConnection`s, two bound services, two surfaces) run side by side without sharing anything.

So split screen (`ui/car/CarSplit.kt`, `CarUiState.secondaryAppId`) puts built-in screens and hosted car apps in the second pane (`canOpenBeside`). The phone mirror and a launched phone app stay full screen only. A car app in the second pane gets Back like any other; when it finishes, the other pane's app is left alone on screen (`leftAlone`). The primary pane is unrestricted (today's one-app slot).

Opened by a long-press on a rail app icon ("open beside"), not the launcher grid: the launcher is one screen away from the dashboard, not reachable without leaving the app that's already open, while the rail stays on screen the whole time.

Dragging the divider moves an overlay handle only; the real pane weights (and so a hosted app's map surface size) update once, on release, per the project's rule against resizing a surface on every drag frame.

`screenBack` (a single field for "the screen with its own back stack") became a map keyed by app id (`registerScreenBack`/`unregisterScreenBack`), since split screen can now put two such screens on screen together (e.g. the browser beside messaging) where before only one app was ever visible at a time.

Scoped to the taskbar layout and screens ≥900 dp wide (the brief's number); the dock and classic layouts keep their current one-app behaviour.

An app is never in both panes (`openInPanes`): opening one that is already beside the main app (from the rail, or the phone during a call) moves the focus to its pane instead of opening it a second time in the main pane; any other app takes the main pane and the one beside it stays.

## Knob/D-pad reachability check (issue #320)

Every car-screen control must be reachable, highlighted and usable by a rotary knob or D-pad, not
just by touch: on knob-only head units (most Mazdas, older iDrive, Lexus/Mercedes touchpads) a
control the knob can't reach is unusable. Two had slipped through before this: the dock's tap
targets and the dashboard settings' card rows. Proposed by @JVKeller in #320; approved by the owner.

- **`Modifier.carClickable(shape, onClick)`** (`ui/car/CarFocus.kt`) pairs the existing
  `carFocusHighlight` with the click itself, so the knob-friendly way is also the easy way. Car
  screen clickables (`ui/car`, `media`, `host`) moved over to it.
- **`CarScreenReachabilityCheckTest`** (`app/src/test/java/net/prototux/aare/ui/car/`) is a plain
  text scan of those packages' sources for raw `.clickable`/`.toggleable`/`.selectable`/
  `combinedClickable`, tap/drag gesture detectors, and Material `Slider`/`Switch`/`Checkbox`. No new
  dependency: no Robolectric, no UI test framework. A justified exception goes in its `allowlist`
  with a one-line reason (kept short; migrating to `carClickable` is preferred). The phone UI isn't
  checked.
- **Deferred:** `Modifier.carAdjustable(onStep)`, for a value a gesture changes rather than a tap
  (a seek bar, a drag-to-resize handle) — built when a real case needs it, per the issue. Until
  then those stay allowlisted.
- **`Modifier.carAdjustable(shape, vertical, onStep)`** (`ui/car/CarFocus.kt`), built for the split
  screen divider, its first real case. Select (knob press, D-pad centre) starts adjusting; then
  each knob detent or D-pad press along the control steps the value by one, and select again (or a
  D-pad press across it) stops. A mode rather than always-on, because the knob's turn and the
  D-pad also move the focus: a control that always took them would trap the focus on it. The
  divider steps from rest point to rest point (`stepSplitRatio`) through the same commit as a drag,
  so stepping past two thirds closes that pane like dragging to the edge. The scan accepts a drag
  detector with `.carAdjustable(` in the lines just before it, so the divider left the allowlist.

## Stopping the assistant: the voice button, or a red stop

*Stopping the assistant* (Voice assistant settings) picks how the driver stops it, the steering
wheel's voice key included:
- **The voice button turns into Stop** (default): while the assistant is on (listening, or its bar
  or car-screen view showing), the voice button is red with a stop icon, and a press stops it. The
  bar keeps a plain close button that stops it too.
- **Red stop button, the voice button asks again**: a big red stop on the assistant bar; the voice
  button starts a new request, as before 1.6.0.
- **Red stop button, the voice button stops too**: the red stop, and a second press stops as well.
Stopping closes the assistant opened on the car screen, or closes the bar and takes its app's
microphone away (told through its stop-recording callback), or makes an assistant app open full
screen stop listening. An assistant that only runs on the phone can't be stopped from the car, so a
press still opens it.

Stopping the assistant bar (its big red stop button, the back key, or the voice button again) sends
back to the assistant first, while its app is still started (hiding the bar stops it): Lapin takes
back as "stop" and ends listening, thinking or speaking. The car's microphone is then taken back
too, for an app that doesn't. The bar closing by itself after an answer doesn't send back, so a
reply still being spoken isn't cut.

## Root car audio: music and navigation voice apart, through one su helper

With root alone (the normal APK, no Magisk module, no Shizuku app), car audio used to be the
phone's whole output, navigation voice mixed into the music. Now the root helper registers the
same audio policy as the Shizuku path, so music and navigation voice go to their own car channels.

- **Where the policy is registered:** in the su helper (`SuAudioMain mux`), which first drops from
  root to the shell uid (`dropRootToShell`), then calls `ShellAudioService.openPolicy`: the very
  code the Shizuku process runs (`startPolicy` now wraps it). Root itself would pass the
  MODIFY_AUDIO_ROUTING check, but the policy's recorder is a remote-submix one, which needs
  CAPTURE_AUDIO_OUTPUT on the recording uid: root doesn't hold it, the shell does.
- **One helper, one stdout, framed** (`SuAudioWire`): music and navigation voice start at
  different times, each with its own rate and channels (the guidance channel is often 16 kHz
  mono), and either can stop and start again. So the helper takes commands on stdin
  (`policy <id> <rate> <channels> <usages>`, `stop <id>`) and sends frames on stdout
  (`[id: 1 byte][length: 4 bytes, big-endian][payload]`; id 0 is control text: `started`,
  `failed <reason>`, `ended`). Rejected: one su process per channel (no framing, but two root
  grants, toasts and app_process instances per connection, and the two would need their own
  coordination for the fallback); two named pipes or unix sockets (paths or SELinux labels to get
  right between an app and a root process, for nothing a framed pipe can't do).
- **No stream holds up the other:** AARE's reader thread puts each stream's PCM in its own ring
  (`PcmRing`, one second), which drops its oldest sound, in whole sample frames, rather than block.
- **No orphan root process:** end of stdin (AARE closed it, or died) stops every stream and ends the
  helper; so does any failed write to AARE, as before. AARE closes the helper's stdin first when
  the last stream stops, since killing the su process alone may not reach the helper.
- **A main Looper, prepared, not looped:** `SuAudioMain.main` calls `Looper.prepareMainLooper()`
  first, as scrcpy's server does: the system context (`ActivityThread.systemMain`) creates a Handler
  on the calling thread, and a bare app_process main thread has no Looper (every root session
  failed with "Can't create handler inside thread ..."; Shizuku's process has one). It isn't
  looped: the helper's callbacks go to its own policy HandlerThread, so the stdin loop stays on
  the main thread with its exit paths unchanged.
- **Fallback:** a refused policy comes back as `failed <reason>`, written to the connection log
  ("root audio routing refused (…)"); the method is then marked failed for the session and AARE
  uses today's whole-output capture through su (`SuSource`, unchanged), as the Shizuku policy
  path falls back to Shizuku's whole output.
- **Shizuku and the privileged install don't change:** the new method (`ROOT_POLICY`) comes after
  both, and only when Shizuku isn't running.

## Touchscreen calibration

For head units whose touches still land in the wrong place after the automatic mapping
(`TouchMapper`), *Car screen > Touchscreen calibration* lets the user measure the car: set the
visible area from the phone (an L marks each corner on the car screen), touch 4 targets, then a
centre target to confirm. The 4 raw touch-downs give a least-squares affine map from raw panel
coordinates to the frame, which covers a wrong range or offset, swapped or mirrored axes and a
slight tilt.

Design choices:
- **The phone drives every step.** The car's touches can't be trusted before calibrating, so from
  the visible-area step on they go to the tool only, never to the car UI hidden under it.
- **Parked only.** It can't be started while the driving restrictions are on, and a run in progress
  ends with nothing saved when they come on (`CarRuntime.onDrivingRestricted`).
- **Nothing is kept without a confirming touch.** The new map must put the centre tap on the
  centre target within 15 s; a miss, a timeout, a cancel or a video restart keeps the mapping in use
  before. A tap that doesn't fit the other 3 is asked again.
- **Saved per head unit.** In `KnownHeadUnits`, by the head unit's key (`carKey()`, like its "Car
  quirks" choices), not its display name, which two cars can share; forgetting the car forgets it.
  The map and the margins are fractions of the frame, not pixels, so it holds at any resolution.
- **Part of the layout.** `layoutOf()` applies its visible area and `showLayout()` gives the map to
  `TouchMapper`, so a UI config update from the car keeps it, and saving or resetting lays the
  projection out again in place, with no new encoder.
- **It wins over the automatic mapping and `vehicles.json` margins.** It is what the user measured
  on that car; *Reset to the car's values* removes it.

## Wireless: a head unit that restarts its setup while the phone dials it

A wireless adapter that reset mid-drive rejoined, announced a new AAP server, then, while the phone
was still dialling it (and timing out), sent a new WifiVersionRequest over Bluetooth: it had started
its setup over. AARE ignored it ("during the session") and spent its three TCP attempts on the old
port, every time.

- **Only before the TCP connection is up:** `WirelessBootstrap.hold` treats a new WifiVersionRequest,
  or a WifiStartRequest for another port, as a restart only until the connector calls
  `sessionConnected()`. From then on both are ignored, as before. A head unit that never sends a
  second request sees no change on the wire.
- **End the attempt, redial at once:** the bootstrap fails with `setupRestarted` ("the head unit
  restarted its wireless setup"), the connector closes the TCP attempt under way, and
  `ProjectionService` dials again at once (two such redials per connection), so the new attempt
  reads the new server from a fresh handshake. Rejected: answering the new request on the same
  RFCOMM link, which would mean running the negotiation a second time inside `hold`; a fresh
  attempt reuses the tested path.
- **No race with the connect:** `sessionConnected()` and the restart check share a lock; a connect
  that completes after a restart is dropped, not used against the old setup.

## Car audio overhaul: capture methods and their settings (plan)

Owner's decisions, 2026-11-24. The car audio screen guesses which capture method runs (its own copy
of the fallback order, `AudioSettings` in `ui/MainActivity.kt`), never shows a fallback or a failure,
puts root and Shizuku in one row, and counts root only after a tap on that row (`root_audio`), a flag
the app launcher reads too (#396). Reports (#128, #159, #325) show users can't tell which method runs,
or why another doesn't.

**Methods, in the automatic order.** Names say how each works; what the user gets is shown apart.
1. *Audio routing (root)*: the audio policy, registered by the root helper.
2. *Audio routing (Shizuku)*: the same policy, registered by the Shizuku helper.
3. *Output recording*: the phone's whole output, through root or Shizuku, for phones that refuse the
   policy. Navigation voice and music are mixed together, notification sounds come too.
4. *Screen capture*: the user's screen-capture authorization; music only.

Root now comes before Shizuku (owner's choice): a phone with both moves from the Shizuku helper to
the root one, to note in the release. Shizuku methods are only tried when Shizuku is installed and
running. Nothing changes on the wire.

- **The root helper hands AARE a binder**, the same `IShellAudio` the Shizuku helper offers, as
  `LaunchMain` already does for the launcher. It replaces the stdin/stdout framing (`SuAudioWire`) of
  "Root car audio" above; the helper still drops to the shell uid. One code path for root and
  Shizuku, so the car microphone for phone apps works on root too. The handover must reach AARE
  only: check it against `LaunchMain`'s. Rejected: extending the framing with a way back for the
  microphone and its callbacks, a second protocol to keep in step with the Shizuku one.
- **Root is one app-wide permission.** AARE looks for `su` without running it; "Root found: tap to
  allow" runs it once; a visible switch replaces the hidden `root_audio`, and the launcher and every
  other root user read it. Migration: `root_audio` on means allowed.
- **Magisk module (privileged install): deprecated** in the 1.6.0 notes, removed in the next release,
  and not shown on the new screen. Removing it breaks existing setups, so that release is 2.0.0
  (owner).
- **Xposed plays no part in audio**: audioserver checks the permission natively (see "Xposed
  host-shims module").
- **Forcing a method**: *Automatic* by default; a forced method never falls back. The selector
  carries a warning instead of a label.
- **The screen shows the session's state, not its own guess**: `CarSession` publishes each method's
  result (in use, backup, failed with the reason, needs setup, not available).
- **Refusals are remembered per phone**: which method the phone refused at the last drive. Before a
  drive the screen says "expected" and counts it; AARE still tries that method first, in case it was
  a one-off.
- **Screen capture is only offered when nothing else can work**, so nobody grants a right AARE won't
  use. If it becomes the only way during a drive, the car says to open AARE on the phone, where
  Android's dialog appears.
- **No links that push other apps** (no "Get Shizuku"): requirements are only stated. "Open
  Shizuku" stays for a Shizuku that is installed but stopped.
- **Defaults**: full volume to the car on for new installs only (existing ones keep theirs, so no one
  gets much louder sound at the first drive after an update); the car microphone for phone apps on
  when root or Shizuku works, where the user never set it.

**The screen.** A status card: *Audio working*, *Music and navigation apart* (a neutral "partly"
mark for output recording, red only when navigation voice is missing), *Phone speaker quiet*, then
the active method. Below it the method selector, *Tried in this order* (names and one status word),
*Why not the others?* (folded: requirement tags, the reason, the action), *What the methods are*
(folded, one plain line each), then the car microphone and sound settings.

**Order of work**, one PR each:
1. Extraction, no behaviour change: one capture-method registry holding the order and each method's
   result (today `CaptureMethods.best`, `CarSession.startAudio` and the screen each have a copy), a
   focus controller out of `CarSession`, the hidden-API policy code shared by `AudioPolicyCapture`
   and `ShellMicInjector`, one microphone lease for `MicRouter` and `MicTakeover`. Tests for
   `CarAudioOut` and the order.
2. The root helper over a binder; the car microphone for phone apps on root; root as an app-wide
   permission (with its migration).
3. Root before Shizuku, the published per-method state, remembered refusals, forcing.
4. The new screen, the car's screen-capture notice and the new defaults; translations before the
   release tag.
5. The Magisk module removed: tools, release workflow, README.

## F-Droid groundwork: no built-in certificate, an update-check switch

Owner's decisions, 2026-11-24, ahead of a possible F-Droid submission (discussion #169).

- **The built-in phone certificate is gone.** Release builds carried a self-signed certificate from
  the `AARE_PHONE_CERT` / `AARE_PHONE_KEY` secrets, kept for a vendor or CA to sign one day. It was
  never signed, head units that accept it accept the generated one too, and a secret baked into
  the APK rules out building it from source alone (F-Droid) or reproducibly. It can come back if a
  signed certificate ever exists. A car that last worked with it is offered the next certificate.
- **`-Paare.updateCheck=false`** builds without the GitHub release check (`BuildConfig.UPDATE_CHECK`)
  and without its settings section, for stores that update the app themselves. A Gradle property,
  not a product flavour, so CI, the test commands and the release workflow stay as they are.
- **The certificate download stays** for now: the owner will ask F-Droid whether they accept it.

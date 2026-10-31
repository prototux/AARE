# AARE

AARE is an open-source reimplementation of the phone side of Android Auto. The phone talks the
Android Auto projection protocol (AAP) to the car's head unit, draws the car screen itself, and
runs apps that Google's Android Auto keeps out:

- **Navigation:** CoMaps, Organic Maps and other apps built with the AndroidX Car App Library,
  F-Droid builds included.
- **Media:** Tempus (Subsonic/Navidrome) and any app with a media3 or `MediaBrowserService`
  library: podcasts, radio, local players.
- **Messaging:** WhatsApp, Signal, SMS and any app whose notifications Android Auto can read:
  conversations read aloud, marked as read, answered with the car keyboard when parked.
- **Voice assistant:** the steering-wheel voice button opens the phone's default digital
  assistant, or another one you pick. Assistants with a car app, such as
  [Lapin](https://github.com/prototux/lapin), run on the car screen and hear the car's microphone.

It needs no Google Play Services.

![The car screen: CoMaps guiding, the music, and the bar along the bottom](docs/screenshots/main.png)

> [!WARNING]
> **This is early software.** The protocol stack is tested against a simulated head unit, against
> AARE's own desktop head unit and against Google's Desktop Head Unit up to the certificate check
> (see below). So far it has been tried in two real cars, one over wireless and one over USB (see
> [Tested on real head units](#tested-on-real-head-units)). Don't depend on it while driving
> until you have tried it in yours.

## What works

| Part | State |
|---|---|
| Protocol: framing, TLS 1.2 (phone = server), version, service discovery, channels | done, unit and loopback tests |
| Video: H.264 hardware encoding of a private virtual display, ack flow control, focus, key-frame recovery | done |
| Adaptive video bitrate: lower when the car or a weak wireless link falls behind, back up when it catches up | new, untested on hardware |
| Portrait head units: bottom bar, stacked dashboard, CarPlay mode | new, tested only with the desktop head unit's `--portrait` |
| Touch, keys, rotary controller, steering-wheel voice/media/home/back keys | done |
| Sensors: driving restrictions, night mode, car GPS and compass (passed to nav apps) | done |
| Every other sensor the car offers (RPM, odometer, fuel and range, temperature, HVAC, doors, lights, tyres, accelerometer, gyroscope…) for car apps (car hardware API) and the assistant | new, tested only with the desktop head unit's fake car |
| Car App Library host: handshake, templates, map surface, navigation trips, alerts, car hardware, microphone | done |
| Template rendering (list, grid, pane, message, search, sign-in, tabs, navigation, map...) | done |
| Media apps: browse, search, now playing, transport, active session tracking | done |
| Messaging apps: conversations from notifications, read aloud, mark as read, reply when parked | new |
| Turn-by-turn to the instrument cluster and now-playing to the head unit | done |
| Instrument-cluster video (cars with a cluster display): the navigation app's cluster map, or AARE's turn card | new, tested only with the desktop head unit's `--cluster` |
| Directions from Google Maps, Waze… on the phone, on the dashboard and the cluster | new, untested with Google Maps and Waze |
| Phone screen in the car, with touch | new, untested on hardware |
| Phone audio to the car: screen capture, Shizuku, or system audio routing like Android Auto (music and navigation voice apart) | screen capture tested; Shizuku and system routing new, untested |
| Voice assistant as a bar over the screen, with car tools | new, untested in a car |
| Car microphone to apps (any `CarAudioRecord` app) | done |
| Phone status to the car (call state, caller name and photo, signal), battery level; the car's call buttons answer and hang up | new, tested only with the desktop head unit |
| More audio buffering after the car reports underflows | new, untested on hardware |
| USB (AOA) | done, tested on one head unit (see [Tested on real head units](#tested-on-real-head-units)) |
| Wireless (Bluetooth bootstrap + Wi-Fi + TCP) | done, tested on one head unit (see [Tested on real head units](#tested-on-real-head-units)) |
| Developer head-unit server over adb (DHU, openauto, Headunit Revived), optional LAN TCP 5277 | done |

## Not done yet

What Android Auto or the protocol offers that AARE doesn't do (yet).

Not planned (rare, or redundant with what AARE draws itself):

| Missing | Why not |
|---|---|
| Radio service (AM/FM/DAB tuning, presets) | Very few head units offer it to the phone. |
| Vendor extensions | Specific to each car maker, undocumented. |
| Generic notifications service | AARE shows notifications on its own screen; sending them to the car would show them twice. |
| Native media browser service | Same: media apps are browsed on AARE's own screen. |
| User switch, connected-devices list, service discovery update | Only matter with several phones in the car or channels changing during a connection. |
| Audio kept open with silence | AARE closes the car's audio stream after a few seconds of silence and reopens it when sound comes back, like Android Auto does for navigation prompts. |

## Requirements

- A phone with **Android 8** or newer. Some features need a newer Android:

  | Feature | Needs |
  |---|---|
  | Car audio by screen capture | Android 10 (on Android 8 and 9: the privileged install, or the car's Bluetooth) |
  | Car audio through Shizuku | Android 12 |
  | WPA3-only car Wi-Fi (wireless) | Android 10 (WPA2 and WPA2/WPA3 work from Android 8) |
  | Signal bars on the car screen | Android 9 |
  | Hanging up or declining from the car | Android 9 |

  On Android 17, wireless head units and the head unit server's network mode also need the
  *Local network* permission (asked during setup): without it, Android silently drops the
  connection to the head unit.

- A head unit that accepts AARE's certificate (see *Compatibility*), or a computer to run the
  desktop head unit.

## Installing

Download `aare-<version>.apk` from the [releases](https://github.com/prototux/AARE/releases) and
open it on the phone, or `adb install -r aare-<version>.apk`. Each release also has the Magisk
module (rooted phones, see [Car audio](#car-audio)) and the desktop head unit.

Releases are signed with AARE's release key, SHA-256
`63:EF:0D:E1:DB:2C:09:BB:7F:B2:5C:E7:EA:4A:DC:35:38:AF:42:3B:88:52:78:B5:9D:F2:88:9C:7F:18:28:6F`.
Builds you make yourself are signed with your own debug key, so switching between the two needs
an uninstall first.

## AA Key extraction

As head units based on the official Google's host implementation checks the certificate/key of AARE,
in order to use those, key extraction is needed:

1. Download the APKm of Android Auto/com.google.android.projection.gearhead, for example from
   [APKPure](https://apkpure.com/fr/android-auto/com.google.android.projection.gearhead) or
   [APKMirror](https://www.apkmirror.com/apk/google-inc/android-auto/)

2. Run the extract_key.py on the apkm: `./extract_key.py com.google.android.projection.gearhead.apkm`

3. The key and certificate are the `carservice_key.pem` and `carservice_cert.pem`. Copy them on your phone.

4. Open those two files with AARE. It should now work with the head unit.

## Using it

1. Open AARE on the phone and finish *Finish setting up*: permissions, notification access (for
   messages, directions from Google Maps and Waze, and now playing) and car audio (see
   [Car audio](#car-audio)).
2. Have a look at the settings pages (car screen, apps, voice assistant, messages, car audio,
   connection). They mirror Android Auto's.
3. Plug the phone into the car's Android Auto USB port. For wireless, pair it with the car over
   Bluetooth first.
4. To try it without a car, tap **Preview car screen**: it shows the car UI on the phone with the
   same apps, or use the desktop head unit below.

The bar is laid out like Android Auto's: on a side next to the driver, or along the bottom
(*Car screen* in the settings). It holds:

- at the driver's end, the voice button and the menu button (the app grid on the home screen,
  home anywhere else);
- in the middle, the navigation app, the media app, the apps used last, Phone and Settings;
- at the far end, the clock, the weather, the phone's signal and battery, and the notification
  bell.

The dashboard shows the map (or directions from Google Maps or Waze), what's playing and the next
calendar event, over a wallpaper if you pick one. On very wide screens (1920x720 and up) it shows
three cards side by side. The weather comes from [Open-Meteo](https://open-meteo.com/) (no account,
no Google), asked for an approximate position (about 10 km); you can turn it off.

**Instrument cluster.** On cars whose head unit offers a cluster display, AARE sends it its own
video, like Android Auto: the navigation app's cluster map when the app supports it
(`androidx.car.app.category.FEATURE_CLUSTER`), otherwise a turn card with the next maneuver, its
distance, the road and the arrival time (also for Google Maps and Waze directions). Without
guidance it shows the time and what's playing.

Built-in car apps:

- **Phone:** recent calls, favourite contacts and a keypad (when parked). Incoming calls show
  over any screen with *Answer* and *Decline*, then a strip with the caller, the duration and
  *Hang up*. Calls go through the car's Bluetooth hands-free link as usual.
- **Notifications:** the latest messages, with *Read aloud* and *Mark read*.
- **Settings:** colors, bar position, a dark bar even by day, style, passenger mode, message
  notifications, phone audio.

On the phone, the *Apps* page sets which apps show in the car and in which order, and the
*Messages* page has Android Auto's driving options: Do Not Disturb while connected (calls and
priority conversations still come through) and an automatic reply, once per conversation per
drive.

**Passenger mode** (*Car screen > Driving* on the phone, or *Settings* in the car) ignores the
car's driving status: typing a destination, long lists and the phone screen stay available while
moving, for a passenger. The driver must not use the screen while driving.
- **Phone screen:** the phone's own screen (see below).

As an easter egg, *Settings > Style > CarPlay* gives the car screen a CarPlay-like look. AARE is
not affiliated with Apple; CarPlay is a trademark of Apple Inc.

| | |
|---|---|
| ![The voice assistant (Lapin) listening over the map](docs/screenshots/assistant.png) | ![All apps](docs/screenshots/menu.png) |
| The voice button: the assistant as a bar over the screen | All apps |
| ![The car's settings](docs/screenshots/settings.png) | |
| The car's settings | |

### Which apps appear

| Kind of app | Found by | In AARE |
|---|---|---|
| Car App Library apps (CoMaps, Organic Maps, OsmAnd, EV charging…) | `androidx.car.app.CarAppService` | yes |
| Media apps | `MediaBrowserService` / media3 `MediaLibraryService` | yes |
| Messaging apps | `<uses name="notification"/>` in `automotive_app_desc`, and the default SMS app | yes, from their notifications |
| Google Maps, Waze and other apps on Google's private car interface | Android Auto only | no |

Some apps, Google Maps and Waze among them, draw their car screen through an undocumented
interface that only Google's Android Auto app can host, and they check that the host is Google's.
AARE can't host them. AARE's settings list every app that declares Android Auto support but that
AARE can't show, with the reason. Two things make up for it:

- **Their directions in the car.** While Google Maps, Waze or another app guides you on the
  phone, AARE reads its navigation notification and shows the next turn, the distance and the
  arrival time on the dashboard and on the car's instrument cluster. Their voice prompts reach
  the car through the phone's Bluetooth link.
- **Phone screen.** An entry in the car's app list that shows the phone's own screen, so you can
  use those apps from the car:
  - it uses the screen-capture permission of *Car audio and phone screen* (choose *Entire
    screen* when Android asks);
  - touching it needs AARE's accessibility service (*Touch on the phone screen* in *Setup*;
    for a sideloaded APK, Android may first ask you to *Allow restricted settings* in AARE's
    app info);
  - it is off by default (turn on *Phone screen in the car* in *Setup*), and not offered on a
    session from the developer head unit server unless you also turn on *Phone screen on the
    server* under *Head unit server* (risky: whoever connects sees the phone's screen and can tap
    on it);
  - the phone must stay unlocked: the mirror and the touch replay pause while it is locked, and
    the picture is the phone's, in portrait unless you turn the phone;
  - while driving it is paused unless a navigation app is in front and guiding.

### Voice assistant

| Choice | What the voice button does |
|---|---|
| **Same as the phone** (default) | the phone's default digital assistant (*Settings > Apps > Default apps > Digital assistant*) |
| an assistant with a car app (e.g. [Lapin](https://github.com/prototux/lapin)) | a bar over the current screen, like Android Auto's assistant: it listens through the car's microphone and answers right away |
| any other assistant | opens it on the phone; it uses the phone's microphone |

The assistant bar shows the assistant's car screen when it is a message (Lapin's is). Tap it to
open the assistant full screen; it closes by itself after the answer.

**Car tools.** The assistant the voice button opens can act on the car through
`content://net.prototux.aare.car` (`ContentResolver.call`): `call("tools")` returns tool specs in
the function-calling JSON schema, and `call("run", name, {"args": json})` runs one. AARE only
answers that assistant, and only while a car (or the preview) is connected. The tools:

| Tool | Does |
|---|---|
| `car_status` | what's on screen, driving or parked, navigation, now playing, the phone's battery and signal |
| `car_navigate` | navigates to a place in the car's navigation app |
| `car_media` | play, pause, next, previous |
| `car_open` | shows an app, home, the app list or the phone screen |
| `car_messages` | the latest messages, to read them out |
| `car_reply` | replies to a conversation (the assistant reads the reply back for confirmation first) |
| `car_mark_read` | marks a conversation as read |
| `car_display` | dark, light or the car's day/night colors |

Lapin adds them to its own device tools when it runs on AARE, so you can say "take me to the
nearest charger", "what's the next turn?", "read my messages" or "reply to Alex that I'm 10
minutes late".

### Car audio

AARE uses the best way to send the phone's sound to the car that the phone allows:

| Method | Needs | Navigation voice | Phone speaker |
|---|---|---|---|
| **System audio routing**, as Android Auto does it | a rooted phone, AARE installed as a privileged app (below) | yes, on the car's navigation channel | off |
| **Shizuku** | [Shizuku](https://shizuku.rikka.app/) running, Android 12+ | yes, mixed with the music | off |
| **Screen-capture permission** | one consent (choose *Entire screen*) | no | still plays |

- **Privileged install (root):** `tools/magisk/build-module.sh` makes `build/aare-magisk.zip`.
  Install it from Magisk (*Modules > Install from storage*) and reboot. AARE is then in
  `/system/priv-app` with `MODIFY_AUDIO_ROUTING`. Later updates install normally if they are
  signed with the same key.
- **Shizuku (no root):** install Shizuku and start it (wireless debugging, once after each
  reboot). Then on AARE's *Car audio* page tap *Shizuku* to allow AARE.
- **Screen capture:** works everywhere, but Android only gives normal apps media, game and
  "unknown" sounds, so navigation voice stays on the phone, and apps may opt out.

Some cars keep playing the phone's **Bluetooth** audio while in Android Auto mode (the Toyota
Land Cruiser in [Tested on real head units](#tested-on-real-head-units) does). With those, none
of the above is needed: pair the phone with the car and leave *Car audio* unset.

## Testing without a car: the desktop head unit

`./headunit.sh` turns your computer into a car for a phone or emulator connected with adb. You
need `adb` (Android SDK platform-tools), `ffmpeg` and a JDK 17-21.

1. On the phone, turn on USB debugging and plug it into the computer.
2. Install a **debug** build of AARE (`./build.sh && adb install -r app/build/outputs/apk/debug/app-debug.apk`).
3. Run `./headunit.sh`.

The script forwards TCP 5277 to the phone's `@aare_hu` socket over adb (`adb forward tcp:5277 localabstract:aare_hu`), switches on AARE's head-unit server (debug builds do it
when asked; on release builds, turn on *Head unit server* in AARE's settings), builds the
desktop head unit and opens a window showing the car screen. The *Phone screen* app is not
offered on this server unless you turn on *Phone screen on the server* (next to *Head unit
server* in AARE's settings), as its peer is unauthenticated.

The phone only accepts the head-unit server's adb connection from adb itself (uid shell or root),
so other apps on the phone can't use it (processes running as shell, such as `adb shell` or Shizuku, are accepted).
For Google's DHU, or any tool on the computer running adb, run `adb forward tcp:5277 localabstract:aare_hu`
and point it at `localhost:5277`; head units on another device (Headunit Revived, openauto on a Pi) need
*Accept from the network*. The desktop head unit takes `--adb-target tcp:5277` to drive the official
Android Auto app's head unit server instead. *Accept from the
network* in the settings also opens TCP 5277 on every network interface with no authentication: anyone
on the same Wi-Fi can connect (apps on the phone itself are refused on every interface), so by default AARE switches it off again after one session. openauto and Headunit Revived reconnect
on their own and would find it off: for them, turn off *Turn off network mode after a session* so
network mode stays on until you switch it off.

```sh
./headunit.sh                       # AARE, through AARE's desktop head unit
./headunit.sh google                # the official Android Auto app, through Google's Desktop Head Unit
./headunit.sh --res 1920x1080 --time-scale 10 --speed 110 --rhd
./headunit.sh --cluster             # also an 800x480 instrument cluster, in a second window
./headunit.sh --portrait            # a portrait screen (720x1280)
```

| On the computer | In the car |
|---|---|
| mouse | the touch screen |
| Esc / Backspace | back |
| H | home |
| V | the steering-wheel voice button |
| wheel / arrows | rotary controller |
| K / `,` / `.` | play-pause / previous / next |
| N / P | night mode / parked |

The window also gives you:

- **Audio:** the car's audio channels play on your speakers.
- **Microphone:** your microphone becomes the car's microphone, so you can talk to the assistant.
- **Side panel:** the phone's turn-by-turn instructions and now playing.
- **Simulated drive:** follows a real 775 km **Paris → Marseille** route (OpenStreetMap data via
  OSRM), slower in towns, with pause and ×10 / ×60 / ×300 time acceleration. It is sent as the
  car's GPS, speed and gear.
- **Emulator GPS:** on an emulator, the phone's own GPS is moved along the route too, so apps
  that use the phone's location (CoMaps) follow it.
- **Headless mode:** `--headless DIR --script "tap:640,360@10 key:4@12"` runs without a window
  and saves screenshots, for automated tests.

AARE's head unit works with AARE (or other open-source phone implementations), not with Google's
Android Auto app: Android Auto only accepts head units whose certificate is issued by Google, and
this one's is self-signed. The head unit says so when a phone refuses it. AARE refuses a certificate it
hasn't seen until you tap *Trust* on its *New head unit* notification (or on AARE's home screen) and connect again; the head
unit keeps its own certificate in `~/.config/aare/headunit` (`%APPDATA%\aare\headunit` on Windows), so you tap *Trust* once, unless you give it another with `--hu-cert`/`--hu-key`.

**Google mode** runs Google's Desktop Head Unit, downloading it if needed, with the same drive on
an emulator's GPS. The official app only accepts head units with a Google-issued head-unit
certificate, which AARE doesn't include, so Google's own tool is the way to compare. Turn on
*Start head unit server* in Android Auto's developer settings first. AARE's head unit can also
present a head-unit certificate you supply (`--hu-cert`, `--hu-key`).

Other tools:

- `./gradlew :aap:runPhoneSim --args="test.h264"`: a desktop "phone" that serves an H.264 file
  on TCP 5277, to exercise head-unit software.
- `./build.sh :aap:test`: protocol tests, including full sessions against a simulated head unit.

## Compatibility: read this before trying it in a car

### Tested on real head units

| Head unit | Phone | Link | Result |
|---|---|---|---|
| ZLink 3 aftermarket unit, model K2001N (Allwinner T3, Android 7, sold as Android 12), in a VW Golf 6 | Pixel 10 Pro XL, Android 17 | Wireless | Works: full sessions with video, with AARE's own certificate. On Android 17 it needs the local network permission ([#22](https://github.com/prototux/AARE/pull/22)). Known issue: the unit asks for 1280x720 at 160 dpi with 256x120 margins, and the screen edges are cut off. |
| Denso Ten E-DA2, the factory unit of a 2026 Toyota Land Cruiser 79 series (wired Android Auto only) | Samsung Galaxy S23 (SM-S911B), Android 16, not rooted | USB | Works, all features tried ([#37](https://github.com/prototux/AARE/issues/37)), with AARE's own self-signed certificate. The unit also plays the phone's Bluetooth audio during Android Auto, so the car audio setup isn't needed. |

If you try AARE on another head unit, please open an issue or a pull request to add it here,
whether it worked or not.

### Phone certificate

During the handshake the head unit checks the phone's TLS certificate.

- **Google's head unit library:** Google's Desktop Head Unit and head units built on Google's
  receiver library accept only a phone certificate issued by Google's "Google Automotive Link"
  CA. AARE was tested against Google's DHU: the TLS handshake completes, then the head unit
  refuses authentication (status -3).
- **Open-source head units:** openauto/Crankshaft, Headunit Revived, LIVI and aa-proxy-rs accept
  AARE's generated certificate, as do some OEM units.
- **Importing a certificate:** AARE does not ship or extract Google's key. If you have a
  certificate and key you're entitled to use, import it under *Advanced > Phone certificates*.
- **Fallback order:** AARE offers its certificates in turn. After a head unit rejects one, the
  next connection uses the next one.

[docs/certificates.md](docs/certificates.md) explains the certificates in detail: what Google's
Android Auto presents and requires, which head units check what, and how to read AARE's
connection log when a car doesn't connect.

### Car apps that only trust Google

A Car App Library app decides which hosts it accepts (`HostValidator`).

- **Debug builds** of most apps accept any host.
- **Release builds:** apps built with car-app library 1.8.0-beta01 or older also accept a host
  holding `android.car.permission.TEMPLATE_RENDERER`. AARE holds it, so CoMaps (1.7.0), Organic
  Maps (1.8.0-alpha02) and Lapin (1.7.0) work today.
- **Newer library versions** dropped that path on phones. Those apps need AARE's signing
  certificate digest in their allow-list:

  ```kotlin
  HostValidator.Builder(context)
      .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
      .addAllowedHost("net.prototux.aare", "<sha256 of AARE's signing certificate>")
      .build()
  ```

  For the releases, the digest is the release key's SHA-256 above (without the colons, in
  lowercase). For your own builds, get it with `apksigner verify --print-certs app-debug.apk`.
  For apps you build yourself, add it to their allow-list. For others, ask upstream.

### Calls

Calls go through Bluetooth (HFP), as with Android Auto.

### VPN

AARE works with a VPN app running on the phone (WireGuard, OpenVPN...), as long as the VPN's
address ranges don't overlap the head unit's network. Over wireless, the head unit's Wi-Fi has its
own addresses (often `192.168.x.x`): if the VPN claims the same range, the phone sends the car's
traffic into the VPN and the connection times out. Then change the VPN's range, or exclude the head
unit's network from the VPN (most VPN apps have a setting for local network access). USB isn't
affected.

### Coexisting with Google's Android Auto

- **USB:** both apps answer the same USB accessory, so Android asks which one to use. Choose
  AARE and "always", or disable Android Auto.
- **Permissions:** AARE never redeclares the permissions Android Auto defines, so both can be
  installed.

## Building

CI builds and tests every push. Pushing a `v1.2.3` tag publishes a release (`.github/workflows/`).

You need a JDK 17-21 and the Android SDK (Android Studio installs both).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or set ANDROID_HOME
./build.sh                    # debug APK: app/build/outputs/apk/debug/app-debug.apk
./build.sh :aap:test          # protocol tests: framing, TLS, a full session against a simulated head unit
./build.sh :app:testDebugUnitTest
```

`build.sh` finds a suitable JDK and runs Gradle. On a machine without swap, set
`AARE_BUILD_MEMORY=5G` to run the build in a memory-capped systemd scope. Opening the project
in Android Studio works too.

## Layout

| Folder | What |
|---|---|
| `aap/` | the protocol, pure Kotlin/JVM: framing, TLS, session, channels, wireless bootstrap, simulators and tests |
| `aap/src/main/proto/` | AAP protobuf definitions, from [aasdk](https://github.com/opencardev/aasdk) |
| `app/.../car/` | a projection session: video, input, sensors, audio, microphone, cluster |
| `app/.../host/` | the Car App Library host, the car runtime (app list, routing, UI root), the assistant bar and car tools |
| `app/.../host/templates/` | Compose rendering of car app templates |
| `app/.../media/` | media apps: discovery, media3 browser, now playing, UI |
| `app/.../mirror/` | the *Phone screen* app: mirroring and touch |
| `app/.../audio/` | car audio: capture methods (audio policy, Shizuku, screen capture), microphone |
| `tools/magisk/` | Magisk module that installs AARE as a privileged app |
| `app/.../notify/` | notifications: messaging apps, and directions from navigation apps on the phone |
| `app/.../transport/` | USB accessory, TCP server, wireless (Bluetooth + Wi-Fi) |
| `app/.../ui/` | phone UI, car shell (rail, dashboard, launcher), theme |
| `headunit/` | the desktop head unit (`./headunit.sh`) |
| `docs/research/` | protocol notes: Android Auto app, reference projects, car app hosting |

## Contributing

Bug reports and pull requests are welcome. Reports from real cars are the most useful: say which
head unit (make, model, year, or the open-source software it runs), USB or wireless, and attach
`adb logcat` output around the connection (`adb logcat | grep -i aare`).

Before sending a pull request, run `./build.sh :aap:test` and build the app.

## Thanks

To [@dustfeather](https://github.com/dustfeather), for a security review of AARE and the fixes
that came with it:

- the protocol: the handshake order enforced, fragment reassembly capped;
- trust: new or changed head unit certificates need your approval, the developer head unit
  server is reachable through adb only unless you open it to the network;
- car apps: the microphone, location and calls need the app's own permission or your action,
  artwork and notification images only load from the app that supplied them;
- wireless: open Wi-Fi refused, and the local network permission Android 17 needs;
- the phone screen off by default and paused while the phone is locked;
- GitHub Actions pinned to commits, and Dependabot;

and for the first test of AARE in a real car.

## License

GPL-3.0-or-later (see [LICENSE](LICENSE)). The protobuf definitions come from aasdk
(GPL-3.0-or-later, see [aap/src/main/proto/NOTICE.md](aap/src/main/proto/NOTICE.md)).

Not affiliated with or endorsed by Google or Apple. Android Auto is a trademark of Google LLC;
CarPlay is a trademark of Apple Inc.

# Per-car settings: vehicles.json

Some head units misreport their screen, or need the phone to speak the protocol slightly
differently. `vehicles.json` fixes one car without a new build of AARE.

There are two files in the same format:

- **The built-in one** (`app/src/main/assets/vehicles.json`), shipped with AARE for head units
  known to need something. Add a rule there with the car report or issue that shows it is needed.
- **Yours**, in AARE's external files folder (*Advanced > Vehicle overrides*: the path is shown
  there, with *Write a template* and *Import*). It is read at every connection.

For one head unit, your matching rule wins, field by field, over the built-in ones: a field you
leave out keeps the built-in value, or AARE's default.

Most built-in rules are **built-in car fixes**, marked `"optIn": true`: they apply only with
*Advanced > Vehicle overrides > Built-in car fixes* on (off by default), or for a car whose rule in
your file says `"builtInFixes": true`. Without them every car behaves as it did before they were
shipped. See [Built-in rules](#built-in-rules).

Some quirks also have a switch per car in *Advanced > Car quirks*, for the head unit connected
last: pixel aspect correction, the video resolution limit, single-channel navigation voice, the
Wi-Fi start silences, music during navigation prompts (GAIN_TRANSIENT answering a prompt's
request), pausing it on guidance-only, wireless with this car (Allow, or Block: never wirelessly
to this car, other cars unchanged), forcing electric, the "Exit to car" entry and the microphone
SETUP message. Automatic there keeps what the rules below resolve for the car (as if the page
didn't exist); On/Off force it for that car regardless of any rule. The choices are kept with the
head unit's identity (by the Bluetooth address it announces), so *Forget* on the car or on its
certificate clears them too.

## Format

```json
{
  "rules": [
    {
      "match": { "make": "Volkswagen", "model": "", "name": "MIB Regio" },
      "apply": { "micSetup": false }
    }
  ]
}
```

**`match`**: what the head unit reports at service discovery. Every key you set must match; a
rule without any never matches. A key is a string, or a list of alternatives (any one matching is
enough); an empty string or list means "any".

| Key | Matches |
|---|---|
| `make`, `model` | The car's make and model: case-insensitive substrings. |
| `name` | The connection log's `head unit:` string (`make model head-unit-make head-unit-model`): a case-insensitive substring. |
| `exact` | `true`: `make`, `model` and `name` must be the whole value (still case-insensitive). |
| `year` | The model year the head unit reports. |
| `headUnitMake`, `headUnitModel` | Who made the head unit, and its model (`Harman`, `MGU21`…). |
| `headUnitBuild` | The head unit's software build. |
| `protocol` | The projection protocol version the head unit asks for, `"major.minor"` (`"1.3"`), exactly. |
| `minProtocol`, `maxProtocol` | The same version, at least / at most (`"maxProtocol": "1.1"` is everything before 1.2). |

`year`, the `headUnit…` keys and the protocol keys are always the whole value, case-insensitive,
like built-in car lists. A rule with a protocol key never matches a head unit whose
version isn't known. The connection log's `vehicles.json match keys:` line shows the values a
head unit reported.

In your file the first matching rule is used. In the built-in file every matching rule is: each
covers one quirk, and one head unit can have several (the earlier rule wins a key both set).

For example, a rule for every Ford head unit by Harman older than protocol 1.4:

```json
{ "match": { "make": "Ford", "headUnitMake": "Harman", "maxProtocol": "1.3" }, "apply": { "maxHeight": 720 } }
```

**`apply`**, every key optional:

| Key | Type | Effect |
|---|---|---|
| `dpi` | number | The density of the projected screen. |
| `widthMargin`, `heightMargin` | number | Pixels of the video the car doesn't show, split between both sides. |
| `maxHeight` | number | The largest video height to pick (480, 720, 1080…). |
| `prefer60fps` | true/false | Prefer 60 fps video configurations. |
| `skipServices` | list | Services AARE would open that stay closed for this car. |
| `openServices` | list | Services the car lists that AARE doesn't use, opened anyway; AARE sends nothing on them and ignores what the car sends. |
| `micSetup` | true/false | Send SETUP on the microphone channel when it opens (default: true). |
| `videoResolutions` | list | The only video resolutions to pick (`"800x480"`…), when the head unit offers one of them. |
| `pixelAspect` | number | Width over height of one of the car screen's pixels (1.15: wider than tall). The UI is laid out wider (or taller, below 1) and squeezed into the video, so it looks right on the car. Default: 1 (the built-in file sets it for some head units before protocol 1.2). |
| `reportedPixelAspect` | true/false | Use the pixel aspect the head unit reports (protocol 1.2 on) when `pixelAspect` isn't set (default: false). |
| `singleChannelCapture` | true/false | Navigation voice goes with the music on the media channel; the guidance channel stays unused. |
| `forceElectric` | true/false | Tell apps the car is electric when it reports no fuel type or an unknown one. |
| `wifiMediaStartSilence`, `wifiGuidanceStartSilence` | number | Silent audio messages sent ahead of a music / navigation voice stream, over Wi-Fi (default: 4 and 4; 0 for music from protocol 5.0 on). Over USB none are sent. For music the silence takes the place of the oldest sound waiting, so it doesn't put the music further behind the video. |
| `mediaOnGainTransient` | true/false | Music keeps playing when the car grants GAIN_TRANSIENT to a navigation prompt (default: false, only navigation voice does then). Granted to the music's own request, or sent by the car on its own, GAIN_TRANSIENT always lets music play. |
| `pauseOnGuidanceOnly` | true/false | The car saying on its own that only navigation voice may play pauses the phone's music, which plays again on the car's next gain, on its own or answering a request (default: true). |
| `wireless` | true/false | Connect to this head unit wirelessly (default: true). Known before the session from the car's last one: AARE keeps, on the phone, who each head unit said it was by the Bluetooth address it announces (the 32 seen most recently; *Forget* on the car or on its trusted certificate clears it). A Bluetooth device a wireless session ever worked with (a wireless adapter, say) is never left out. |
| `exitToCar` | true/false | The app list's "Exit to car" entry (default: true). |
| `exitLabel` | text | What that entry is called: `"name"` (the car's display name, else its make), `"make"`, or `"default"` (the generic label). Default: the generic label, or `"name"` with the built-in car fixes on for the car. |
| `builtInFixes` | true/false | In your file: apply the built-in car fixes to this car (`true`), or not even with the setting on (`false`). Default: the *Built-in car fixes* setting. |
| `dropOverflow` | true/false | Stream video (head units 6.0 and later) and audio (5.0 and later) in drop overflow mode: the head unit drops what it can't take instead of pacing the phone with acks (default: false). Try it on a car whose picture or sound stalls because it stops acking. |
| `pairAgainWhenRunning` | true/false | Send the Bluetooth pairing request again once the session runs, for a car that drops the phone's hands-free link when projection starts (default: false). |
| `videoHeadersOnce` | true/false | Send the video's SPS/PPS only in the codec config, not again before every key frame (default: false). Try it on a car that plays the sound and acks every frame but shows no picture. |
| `videoAfterFocus` | true/false | Start the video stream only once the car grants focus, as Android Auto does, and start it anyway if the car never answers the focus request (default: false, right after the car's video config). For a car that shows no picture and acks no frame when the stream starts first. |
| `sensorChannelFirst` | true/false | Open the sensor channel first and the other channels once the car has answered it, or after 1 s (default: false, all at once). |

Services are named as the connection log writes them after `services:`: `video`, `mic`,
`input`, `sensors`, `bluetooth`, `navstatus`, `playback`, `phonestatus`, `mediabrowser`,
`vendor`, `notifications`, `wifi`, `radio`, `audio:AUDIO_STREAM_MEDIA`… A name without a colon
also covers its whole family: `"audio"` is every audio channel. The log line `not opening: …`
lists what AARE leaves closed, and `vehicles.json: opening/not opening …` what a rule changed.

Closing a service AARE needs (`video`, `input`) breaks the projection: these keys are for a head
unit that hangs up or shows nothing because of one channel.

## Built-in rules

Always applied (they refine what every car gets):

- `micSetup: false` for the VW Play (MIB Regio, #162).
- Exceptions to the audio defaults: more start silence for some Uconnect, BMW and Hyundai units,
  music on GAIN_TRANSIENT for ZJ head units, and no pause on GUIDANCE_ONLY for Ford SYNC.

The **built-in car fixes** (`"optIn": true`), copied from known car lists, apply only with the
*Built-in car fixes* setting on, or `"builtInFixes": true` in your rule for the car:

- pixel aspect for older Mitsubishi, Pioneer and Kenwood head units (before protocol 1.2);
- 800x480 video for Subaru's Harman units;
- mixed navigation voice for some early Kia, Hyundai, Ford SYNC and Volkswagen units;
- EV fuel type for some Hyundai, Kia and Genesis models;
- no wireless for some DENSO TEN, Pioneer, Desay SV and Alpine head units;
- "Exit to car" named after the car (its display name, else its make; the make for Audi, Jaguar
  and Land Rover, the generic label for GM brands and BYD), and none for Xiaomi.

`optIn` means something only in the built-in file: your own rules always apply. To undo one fix
for your car with the setting on, set the key back in your own file (`"wireless": true`,
`"pixelAspect": 1`…), or leave them all out with `"builtInFixes": false`.

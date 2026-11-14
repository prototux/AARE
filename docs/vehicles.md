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
| `pixelAspect` | number | Width over height of one of the car screen's pixels (1.15: wider than tall). The UI is laid out wider (or taller, below 1) and squeezed into the video, so it looks right on the car. Default: what the head unit reports from protocol 1.2 on, else 1. |
| `singleChannelCapture` | true/false | Navigation voice goes with the music on the media channel; the guidance channel stays unused. |
| `forceElectric` | true/false | Tell apps the car is electric when it reports no fuel type or an unknown one. |
| `wifiMediaStartSilence`, `wifiGuidanceStartSilence` | number | Silent audio messages sent ahead of a music / navigation voice stream, over Wi-Fi (default: 4 and 4; 0 for music from protocol 5.0 on). Over USB none are sent. |
| `mediaOnGainTransient` | true/false | Music keeps playing when the car grants GAIN_TRANSIENT (default: false, only navigation voice does). |
| `pauseOnGuidanceOnly` | true/false | The car saying on its own that only navigation voice may play pauses the phone's music (default: true). |
| `wireless` | true/false | Connect to this head unit wirelessly (default: true). Known before the session from the car's last one: AARE keeps, on the phone, who each head unit said it was by the Bluetooth address it announces. A Bluetooth device a wireless session ever worked with (a wireless adapter, say) is never left out. |
| `exitToCar` | true/false | The app list's "Exit to car" entry (default: true). |
| `exitLabel` | text | What that entry is called: `"name"` (the car's display name, else its make; the default), `"make"`, or `"default"` (the generic label). |

Services are named as the connection log writes them after `services:`: `video`, `mic`,
`input`, `sensors`, `bluetooth`, `navstatus`, `playback`, `phonestatus`, `mediabrowser`,
`vendor`, `notifications`, `wifi`, `radio`, `audio:AUDIO_STREAM_MEDIA`… A name without a colon
also covers its whole family: `"audio"` is every audio channel. The log line `not opening: …`
lists what AARE leaves closed, and `vehicles.json: opening/not opening …` what a rule changed.

Closing a service AARE needs (`video`, `input`) breaks the projection: these keys are for a head
unit that hangs up or shows nothing because of one channel.

## Built-in rules

Most built-in rules copy known car lists (marked so in the file): pixel aspect for
older Mitsubishi, Pioneer and Kenwood head units, 800x480 for Subaru's Harman units, mixed
navigation voice for some early Kia, Hyundai, Ford SYNC and Volkswagen units, EV fuel type for some
Hyundai, Kia and Genesis models, start silence for some Uconnect, BMW and Hyundai units, the
head units it doesn't connect to wirelessly, and how it names "Exit to car". To undo one for your
car, set the key back in your own file (`"wireless": true`, `"pixelAspect": 1`…).

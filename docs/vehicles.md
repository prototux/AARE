# Per-car settings: vehicles.json

Some head units misreport their screen, or need the phone to speak the protocol slightly
differently. `vehicles.json` fixes one car without a new build of AARE.

There are two files in the same format:

- **The built-in one** (`app/src/main/assets/vehicles.json`), shipped with AARE for head units
  known to need something. Add a rule there with the car report or issue that shows it is needed.
- **Yours**, in AARE's external files folder (*Advanced > Vehicle overrides*: the path is shown
  there, with *Write a template* and *Import*). It is read at every connection.

For one head unit, your matching rule wins, field by field, over the built-in one: a field you
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

**`match`**: case-insensitive substrings of what the head unit reports, as the connection log
prints it after `head unit:` (`make model head-unit-make head-unit-model`). `name` is that whole
string. Every field you set must match; a rule without any never matches. The first matching rule
of each file is used.

**`apply`**, every key optional:

| Key | Type | Effect |
|---|---|---|
| `dpi` | number | The density of the projected screen. Wins over the phone's "Car screen density" setting, which in turn wins over the head unit's own value and AARE's size-based default: vehicle override > phone setting > head unit > default. |
| `widthMargin`, `heightMargin` | number | Pixels of the video the car doesn't show, split between both sides. |
| `maxHeight` | number | The largest video height to pick (480, 720, 1080…). |
| `prefer60fps` | true/false | Prefer 60 fps video configurations. |
| `skipServices` | list | Services AARE would open that stay closed for this car. |
| `openServices` | list | Services the car lists that AARE doesn't use, opened anyway; AARE sends nothing on them and ignores what the car sends. |
| `micSetup` | true/false | Send SETUP on the microphone channel when it opens (default: true). |

Services are named as the connection log writes them after `services:`: `video`, `mic`,
`input`, `sensors`, `bluetooth`, `navstatus`, `playback`, `phonestatus`, `mediabrowser`,
`vendor`, `notifications`, `wifi`, `radio`, `audio:AUDIO_STREAM_MEDIA`… A name without a colon
also covers its whole family: `"audio"` is every audio channel. The log line `not opening: …`
lists what AARE leaves closed, and `vehicles.json: opening/not opening …` what a rule changed.

Closing a service AARE needs (`video`, `input`) breaks the projection: these keys are for a head
unit that hangs up or shows nothing because of one channel.

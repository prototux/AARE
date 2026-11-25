# Custom cards

Besides its own cards, AARE's dashboard shows cards you make: clocks, gauges, anything drawn from the
time, the weather, the calendar, the music, the phone's battery and the car's data. A card is one
JSON file, like a smartwatch face: a canvas and the layers drawn on it.

## Making one

1. Draw the card on a computer with the [card editor](../tools/card-editor/README.md), a web page; or
   write it in any text editor, starting from the example below or from one of AARE's own clocks, in
   [`app/src/main/assets/faces`](../app/src/main/assets/faces).
2. Copy the file to the phone (cable, e-mail, cloud…).
3. In AARE, *Car screen > Clock and custom cards > Import cards*. The card is added to the dashboard;
   order or hide it under *Car screen > Dashboard*, like the others. A file that isn't right is
   refused, saying what is wrong and where.

Importing a file whose card has the **same name** replaces that card, keeping its place: correct the
file and import it again. Tap a card on that page to delete it.

## The file

```json
{
  "aareCard": 1,
  "name": "My clock",
  "size": [400, 240],
  "layers": [
    { "type": "ticks", "count": 12, "r": 100, "length": 12, "stroke": 4 },
    { "type": "hand", "value": "hour", "length": 54, "stroke": 8 },
    { "type": "hand", "value": "minute", "length": 84, "stroke": 5, "color": "primary" },
    { "type": "text", "text": "{shortDate}", "y": 180, "size": 20 }
  ]
}
```

| Key | |
|---|---|
| `aareCard` | `1`, the format's version. Needed. |
| `name` | Shown in the settings. Needed. |
| `author` | Optional. |
| `size` | `[width, height]` of the canvas, in units of its own. It is scaled, whole, to fit the card and centred. A dashboard card is about 400 x 240. Needed. |
| `background` | A [colour](#colours) for the whole card. Without one, the theme's card. |
| `layers` | What is drawn, in order: later ones cover earlier ones. Needed. |
| `strip` | Another `size`, `background` and `layers`, used when the card is a thin strip (3 times as wide as high, or more). |
| `tall` | The same for tall cards (narrower than 0.9 times the height), as on very wide screens. |

Positions start at the top left. Angles are in degrees, 0 at the top, clockwise. Keys starting with
`_` or `$` are ignored, for notes. The limits: 2 MB, 200 layers; pictures are shown at 1024 pixels at
most.

## Layers

Every layer has a `type`, and can have:

| Key | Default | |
|---|---|---|
| `x`, `y` | the middle (`path`: 0, 0) | Where its centre is. |
| `color` | `onSurface` | A [colour](#colours). |
| `alpha` | 1 | Opacity, 0 to 1. |
| `rotate` | | Degrees, or a [value](#values) to turn with. |
| `pivot` | `x`, `y` (`path`: the canvas' middle) | `[x, y]`, what it turns around. |
| `show` | | A [condition](#conditions), or a list of them: all must hold. |

### `text`

| Key | Default | |
|---|---|---|
| `text` | needed | With [tokens](#tokens), like `"{time} · {temp}"`. `\n` starts a new line. |
| `size` | 40 | |
| `font` | `default` | `default` (the theme's), `sans`, `condensed`, `serif`, `mono`, `cursive`, `segment` (digits of a seven-segment display; put an `"88:88"` in a darker colour behind for the unlit ones). |
| `weight` | `normal` | `thin`, `light`, `normal`, `medium`, `bold`, `black`, or 1 to 1000. |
| `italic`, `upper` | false | Italic; capitals. |
| `align` | `center` | What `x` is: the text's `start` (left), `center` or `end` (right). |
| `valign` | `middle` | What `y` is: its `top`, `middle`, `bottom` or `baseline`. |
| `width` | | The room it has. A longer line is made smaller, down to half, then cut. |
| `lines` | 1 | More than one wraps at `width` instead. |
| `letterSpacing` | 0 | |
| `lineHeight` | the font's | In times the size. |
| `timeZone` | the phone's | For the time and date tokens: `"America/New_York"`. |
| `glow`, `glowRadius` | none, 12 | A colour around the letters. |

### `hand`, `ticks`, `numbers`, `arc`

| Type | Key | Default | |
|---|---|---|---|
| `hand` | `value` | needed | The [value](#values) it points at, like `"hour"`, `"minute"`, `"second"`. |
| | `length` | needed | From the centre. |
| | `tail` | 0 | Past the centre, on the other side. |
| | `stroke` | 6 | Thickness. |
| | `shape` | `round` | `round`, `flat`, `taper`, `leaf`. |
| `ticks` | `r` | needed | Radius of their outer end. |
| | `count` | 60 | |
| | `length`, `stroke` | 8, 2 | |
| | `skip` | 0 | Leaves out every so many: 5 leaves room for the hours among the minutes. |
| | `dots` | false | Dots, `stroke` wide, instead of lines. |
| | `start`, `sweep` | 0, 360 | Where they start and how far they spread, in degrees. |
| | `round` | true | Round ends. |
| `numbers` | `r` | needed | |
| | `values` | `"12"` | `"12"`, `"4"` (12 3 6 9), `"24"`, `"60"`, `"roman"`, or a list of texts. The first is at `start`. |
| | `size`, `font`, `weight` | 24, … | As in `text`. |
| | `upright` | true | false turns them with the circle. |
| | `start`, `sweep` | 0, 360 | |
| `arc` | `r` | needed | A ring, or a part of one. |
| | `stroke` | 8 | |
| | `start`, `sweep` | 0, 360 | |
| | `value` | all of it | The [value](#values) that fills it. |
| | `track` | none | Colour of the whole ring behind. |
| | `round` | true | |

### Shapes, icons and pictures

| Type | Keys |
|---|---|
| `circle` | `r`; `stroke` (0: filled) |
| `rect` | `w`, `h`; `corner` 0, `stroke` 0 |
| `line` | from `x`, `y` to `x2`, `y2`; `stroke` 2, `round` true |
| `path` | `d`: an SVG path, its origin at `x`, `y`; `scale` 1, `stroke` 0, `round` true |
| `icon` | `icon`: `weather` (follows the sky), `calendar`, `music`, `battery`, `bolt`, `place`, `clock`, `car`, `fuel`, `thermostat`, `compass`, `heart`, `star`, `home`, `person`, `notifications`; `size` 40 |
| `image` | `data`: the picture as `"data:image/png;base64,…"` (PNG, JPEG or WebP); `w`, `h`. With a `color`, all in that colour. |

With `rotate`, a picture or a path makes a hand of your own:
`{ "type": "image", "data": "…", "w": 20, "h": 180, "rotate": "minute" }`.

## Colours

- `"#rgb"`, `"#rrggbb"`, or `"#aarrggbb"` with opacity.
- A colour of the car screen's theme, so the card follows its style and day and night: `primary`,
  `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `secondary`, `secondaryContainer`,
  `onSecondaryContainer`, `tertiary`, `tertiaryContainer`, `onTertiaryContainer`, `surface`,
  `onSurface` (text), `onSurfaceVariant` (dimmer text), `surfaceContainer` (the card),
  `surfaceContainerHigh`, `surfaceContainerHighest`, `outline`, `outlineVariant`, `error`. With an
  opacity in percent: `"primary/40"`.
- `{ "day": "#222222", "night": "#eeeeee" }`.
- For `color` and `background`, a gradient: `{ "gradient": ["#f55", "#55f"], "angle": 90 }`, with 2 to
  12 colours. `angle` is where it runs to: 180 (the default) top to bottom, 90 left to right.
  `"kind": "radial"` runs from the centre out, `"sweep"` around it.

## Values

What a `hand`, an `arc` or `rotate` follows: a name, or an object to choose the range.

| Name | Runs over |
|---|---|
| `second`, `minute`, `hour`, `hour24` | 0–60, 0–60, 0–12, 0–24 |
| `weekday`, `day`, `month` | 0 (Monday) – 7, 0–31, 0–12 |
| `battery` | 0–100 %, the phone's |
| `song` | 0–1, how far it is |
| `temperature` | -20–50 |
| `speed`, `speedMph` | 0–200 km/h, 0–120 mph |
| `rpm`, `fuel`, `compass` | 0–8000, 0–100 %, 0–360° |

`{ "value": "speed", "min": 0, "max": 160, "from": -120, "to": 120 }` turns a hand from -120° at 0 to
120° at 160 km/h. For an `arc`, `from` and `to` are 0 and 1, empty and full. The speed, revs, fuel and
compass come from the car, if it sends them; until then their layer isn't drawn (an `arc` shows its
track).

A card with seconds on it is redrawn every second, the others every minute.

## Tokens

| Token | |
|---|---|
| `{time}`, `{hour}`, `{minute}`, `{second}` | In the phone's 12 or 24 hours. |
| `{ampm}` | Empty with 24 hours. |
| `{time:HH:mm:ss}` | In [a format of yours](https://developer.android.com/reference/java/text/SimpleDateFormat). |
| `{date}`, `{shortDate}` | "Wednesday 25 November", "Wed 25 Nov", in the phone's language. |
| `{weekday}`, `{day}`, `{month}`, `{year}` | |
| `{date:dd/MM}` | In a format of yours. |
| `{temp}` | The weather's temperature. |
| `{outside}` | The car's own thermometer. |
| `{event}`, `{eventDay}`, `{eventTime}`, `{eventPlace}` | The next calendar event ("No events" without one). |
| `{track}`, `{artist}` | |
| `{battery}` | The phone's, in percent, without the sign. |
| `{speed}`, `{speedMph}`, `{rpm}`, `{fuel}`, `{range}` | The car's. `–` while it doesn't say. |

`{{` and `}}` are plain braces.

## Conditions

`weather`, `event`, `music` (something to play), `playing`, `charging`, `speed`, `fuel` (the car says
it), `day`, `night` (the car screen's colours), `am`, `pm`. A `!` in front: without it.

```json
{ "type": "text", "text": "{temp}", "show": "weather" },
{ "type": "text", "text": "{date}", "show": "!weather" }
```

## Privacy

A card can't run code or reach the network, and only reads what the dashboard knows already. One with
the weather on it makes AARE ask for it, as the weather card does (Open-Meteo, with an approximate
position); one with an event reads the calendar, if AARE has that permission.

## For developers

The format is read by `faces/FaceSpec.kt` and drawn by `ui/car/FaceView.kt`; the bundled clocks are
`app/src/main/assets/faces`. The card editor draws cards itself, so what changes in the format changes
in `tools/card-editor/template.html` too.

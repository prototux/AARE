# Home actions

Buttons and values from a smart home on the car screen, over MQTT or HTTP. Not tied to one system:
anything that takes an MQTT message or an HTTP request works; presets fill in the usual
Home Assistant setups. Set up on the phone under **Home actions**; off until switched on there.

- **Actions** are buttons. One sends an MQTT publish (topic, payload, QoS 0 or 1, retain) or an
  HTTP request (method, URL, headers, body). An action marked *Ask first* asks on the car screen
  before sending (a garage door). An action can also be offered **on arrival** (below), always
  with a confirmation tap.
- **Tiles** are values: the last message on an MQTT topic, or an HTTP GET polled every N seconds
  (at least 10), optionally through a JSON path, with a unit.
- In the car: a **Home** app in the launcher (every tile, then every action) and a **Home** card
  for the dashboard (Settings > Dashboard on the phone or in the car), with a few tiles and the
  first four actions marked *On the dashboard card*, as many as the card's slot has room for.
  Buttons are one tap (two with the confirmation), so they stay usable while driving; nothing in
  the car asks for text.

## Connections

- **MQTT broker**: host, port, TLS on or off, user name and password, client id (blank: a random
  `aare-…` per connection). AARE's own MQTT 3.1.1 client (`home/mqtt`): CONNECT with a clean
  session, PUBLISH at QoS 0 or 1, SUBSCRIBE at QoS 0, PINGREQ every ¾ of the 30-second keep-alive,
  DISCONNECT. No QoS 2, no will message, no persistent session.
- **HTTP server**: a base URL and default headers. A URL that starts with `/` goes on the base
  URL; the default headers (the token) are added only to requests to the base URL's own origin
  (scheme, host and port), never to another host. *Allow plain http on my home network* (off by
  default) lets `http://` addresses on the home network work; see Security.

AARE connects only while a car screen is up (a car session or the on-phone preview, sharing one
connection) and disconnects when the last one closes. A dropped MQTT connection is retried after
2 s, then 4, 8… up to 5 minutes. Settings changed on the phone during a drive apply at once.

## Arrival trigger

Per action, opt-in (off by default): a place (latitude, longitude, the phone's location as a shortcut) and a
radius (50–5000 m). During a car session (not the preview) AARE checks every 5 seconds the car's
GPS, or the phone's last known location, ignoring fixes older than 2 minutes or vaguer than the
radius (at least 100 m). The action is offered when the car comes within the radius **after having
been seen outside** `max(1.5 × radius, radius + 100 m)` during the drive: a drive that starts at the
place (leaving home) is no arrival, and a car parked at the edge doesn't fire again.

An arrival never runs an action by itself, whatever its *Ask first*: the car screen shows a notice
with a *Run* button for 30 seconds, and nothing is sent without that tap. A location can be wrong
(a fix near home while driving past) and nobody is watching the screen when it fires, so a garage
door or a gate always waits for one tap.

## Presets

A preset only fills in fields; what it adds is an ordinary action or tile, editable afterwards.

| Preset | Adds |
| --- | --- |
| Home Assistant service | action `POST /api/services/<domain>/<service>`, body `{"entity_id": "<entity>"}`; the domain is the entity's prefix (`cover.garage` → `cover`), `homeassistant` without one |
| Home Assistant state | tile `GET /api/states/<entity>`, JSON path `$.state` |
| Home Assistant webhook | action `POST /api/webhook/<id>` |
| Home Assistant over MQTT | action on the command topic, tile on the state topic (e.g. `homeassistant/switch/garage/set` and `…/state`) |
| MQTT button | action publishing a payload on a topic |
| HTTP webhook | action `POST <url>` |
| HTTP JSON value | tile `GET <url>` through a JSON path |

The Home Assistant REST presets also set the HTTP server: the base URL and the long-lived access
token, sent as `Authorization: Bearer <token>` (blank keeps the saved token).

## JSON path

A small subset: `$`, `.key`, `[0]`, `['key with dots']`, e.g. `$.state` or
`$.attributes.temperature`; the leading `$.` is optional. An empty path shows the payload as it is
(MQTT state topics often send a bare `ON` or `21.5`). Numbers lose a trailing `.0`; objects show as
compact JSON; texts are cut at 64 characters. A missing value shows as `—`.

## Storage format

Two parts, both JSON with `"version": 1`, read leniently: a missing field takes its default, an
action or tile of an unknown `type` (from a newer version) is skipped.

- **Settings** (`home_actions` in the main settings file): `enabled`, `mqtt` (`host`, `port`,
  `tls`, `username`, `clientId`), `http` (`baseUrl`), `actions` (`id`, `name`, `confirm`, `onCard`,
  `type` `mqtt` with `topic`, `payload`, `qos`, `retain`, or `http` with `method`, `url`, `body`;
  optional `arrival` with `lat`, `lon`, `radius`, `enabled`) and `tiles` (`id`, `name`, `unit`,
  `type` `mqtt` with `topic`, `jsonPath`, or `http` with `url`, `jsonPath`, `interval`).
- **Secrets** (the private `home_secrets` preferences file): `mqttPassword` and `headers`, the
  header lists by owner (`http`, `action:<id>`, `tile:<id>`), as `[name, value]` pairs.

Settings imported without their secrets (another phone) still load, without the password and
headers.

## Security notes

- Passwords, tokens and every header value are in their own private file, so nothing that copies
  the main settings file (a settings export) carries them. The app has backups off.
- The connection log never shows the password or header values: errors are scrubbed of every
  known secret, URLs lose their user info, secret-looking query values and webhook ids, and the
  MQTT broker is shown as `mqtt(s)://host:port` only.
- MQTT over TLS uses the phone's trusted certificates and checks the certificate is for the host
  (an SSL socket alone doesn't). There is no "accept any certificate" option; for a self-signed
  broker, install its CA on the phone.
- Plain MQTT (no TLS) sends the password in clear on the network: use it only on a network you
  trust.
- `https://` is the default and works with any address. Plain `http://` is refused unless the HTTP
  server's *Allow plain http on my home network* is on (off by default, with a warning that
  anyone on that network can read the requests, the token included), and even then it goes only
  to the home network: `localhost`, a `*.local` name, or a private address (10/8, 172.16/12,
  192.168/16, link-local 169.254/16, loopback 127/8 and `::1`, IPv6 `fc00::/7`). A name must also
  resolve to such an address, and the request goes to the address that was checked. A public
  `http://` address is refused, option or not. The editors show why and won't save such a URL.
- Android's HttpURLConnection refuses cleartext for the whole app, and the network security config
  can't allow it for private address ranges only (only by name), so AARE keeps the app-wide block
  and sends plain http to the home network through a small HTTP/1.1 client of its own
  (`home/PlainHttp.kt`: one request per connection, replies capped like the https ones). The
  browser and everything else in AARE stay https-only.
- HTTP redirects are followed only on the same host, and never from https to http; replies over
  64 KiB are refused.
- A webhook id works like a password (whoever knows it can trigger the automation). It is part of
  the action's URL, so it is kept with the settings, not the secrets; prefer a token-protected
  service call for anything sensitive.
- Arrival triggers are off unless turned on per action, and always ask for one tap on the car
  screen before sending.

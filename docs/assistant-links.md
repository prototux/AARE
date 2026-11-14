# Assistant links: `aare://`

An assistant that isn't wired into AARE as a car app — one that answers in a notification, or
whose reply contains a link — can still trigger a handful of AARE's car tools, by putting an
`aare://` link in that reply. Tapping it (a notification action, a link in a chat bubble…) opens
AARE's `AssistantLinkActivity`, which maps the link to one of the actions below.

This is separate from `CarControlProvider`, which lets an assistant *running on the car screen*
(Lapin, for example) call the full car-tools API directly. A link is for everything else: an
assistant with no code path into AARE, only a reply the user can tap.

Links are **off by default**: turn on *Links from the assistant* in AARE's voice assistant
settings. With the setting off, every link is ignored (and logged). Even with it on, only the
assistant chosen there (or, with none chosen, the phone's default digital assistant) can open one:
see [Who may open a link](#who-may-open-a-link).

## Format

```
aare://navigate?q=<destination>
aare://play[?action=play|pause|next|previous]
aare://open?app=<app name>
aare://call?n=<phone number>
```

| Link | Parameter | Does |
|---|---|---|
| `navigate` | `q` (required) | Asks the driver on the car screen, then starts navigation to `q` in the car's navigation app, same as the `car_navigate` tool. |
| `play` | `action` (optional, default `play`; `q` also accepted) | `play`, `pause`, `next` or `previous`, same as the `car_media` tool. |
| `open` | `app` (required, `q` also accepted) | Asks the driver on the car screen, then shows that app, same as the `car_open` tool (matches by name, same as asking the voice assistant). |
| `call` | `n` (required) | Asks the driver to confirm on the car screen before dialling; never dials straight from the link. Only digits and a leading `+` are kept. |

Anything else — an unknown host, a different scheme, a missing required parameter, a `play`
action outside that list, a `call` number with no digits — is rejected and nothing happens. This
allowlist is the whole of `AssistantLinks.parse`; nothing beyond these four actions runs,
whatever the link asks for.

## When it runs

- Only while a car session is already running (`CarRuntime.active`): a link opened with no car
  connected is ignored, so it can't be used to wake one up or act on a session that isn't there.
- `navigate`, `open` and `call` always show a confirmation on the car screen first (10 seconds,
  "Asked by a link in an assistant's reply"); nothing happens unless the driver taps it. Only
  `play` runs straight away.
- The diagnostic log records the action kind (`navigate`, `call`…) or why a link was refused,
  never the link itself: it can hold a destination or a phone number.

## Who may open a link

The activity is exported, since another app has to start it, but it is not `BROWSABLE`: no web
page can open an `aare://` link, AARE's own browser included.

It then acts only when the app that started it is the assistant `CarControlProvider` already
trusts (`Assistants.authorized`: the one chosen in AARE, else the phone's default digital
assistant setting). The caller is what Android itself reports, in this order:

1. `getCallingPackage()`, set when the assistant uses `startActivityForResult`;
2. else `getReferrer()` when Android filled it in (`android-app://<package>`): the app that called
   `startActivity`, or the one that created the notification's `PendingIntent`.

A referrer the intent carries itself (`EXTRA_REFERRER`, `EXTRA_REFERRER_NAME`) is any string the
sender likes, so a link carrying one is refused, as is a link with no known caller and any link
started by AARE's own package. An assistant that opens links through a chooser, or through
another app that forwards them, shows up as that other app and is refused too.

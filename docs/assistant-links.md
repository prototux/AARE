# Assistant links: `aare://`

An assistant that isn't wired into AARE as a car app — one that answers in a notification, or
whose reply contains a link — can still trigger a handful of AARE's car tools, by putting an
`aare://` link in that reply. Tapping it (a notification action, a link in a chat bubble…) opens
AARE's `AssistantLinkActivity`, which maps the link to one of the actions below.

This is separate from `CarControlProvider`, which lets an assistant *running on the car screen*
(Lapin, for example) call the full car-tools API directly. A link is for everything else: an
assistant with no code path into AARE, only a reply the user can tap.

Links are **off by default**: turn on *Links from apps* in AARE's voice assistant settings. Any
app on the phone can open an `aare://` link, so the driver decides whether replies may drive the
car screen; with the setting off, every link is ignored (and logged).

## Format

```
aare://navigate?q=<destination>
aare://play[?action=play|pause|next|previous]
aare://open?app=<app name>
aare://call?n=<phone number>
```

| Link | Parameter | Does |
|---|---|---|
| `navigate` | `q` (required) | Starts navigation to `q` in the car's navigation app, same as the `car_navigate` tool. |
| `play` | `action` (optional, default `play`; `q` also accepted) | `play`, `pause`, `next` or `previous`, same as the `car_media` tool. |
| `open` | `app` (optional, `q` also accepted) | Shows that app on the car screen, same as the `car_open` tool (matches by name, same as asking the voice assistant). |
| `call` | `n` (required) | Asks the driver to confirm on the car screen before dialling; never dials straight from the link. Only digits and a leading `+` are kept. |

Anything else — an unknown host, a different scheme, a missing required parameter, a `play`
action outside that list, a `call` number with no digits — is rejected and nothing happens. This
allowlist is the whole of `AssistantLinks.parse`; nothing beyond these four actions runs,
whatever the link asks for.

## When it runs

- Only while a car session is already running (`CarRuntime.active`): a link opened with no car
  connected is ignored, so it can't be used to wake one up or act on a session that isn't there.
- `call` always shows a confirmation on the car screen first; the other three run straight away,
  the same way saying "navigate to the airport" to the in-car assistant would.

## What AARE can and can't check about the caller

Android does not reliably tell an exported activity who started it for an ordinary link tap
(`ACTION_VIEW`, not `startActivityForResult`): `Activity.getCallingPackage()` is null, and
`Activity.getReferrer()` is only set when the caller supplied `EXTRA_REFERRER`, which notification
actions and most browsers don't for a custom scheme. So, like any `http://` link on the phone, any
app can launch this one — AARE cannot confirm a given `aare://` link truly came from an
assistant's reply rather than some other app. The defence is in what the link is allowed to do
(the allowlist above), not in who opened it.

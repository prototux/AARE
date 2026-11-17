# Car links

Assistant apps such as Claude or ChatGPT can't call AARE's car tools: they only write text, and
they have no tool to open a link on the phone. Car links close that gap without a touch from the
driver:

1. The assistant answers as usual and puts a car link behind a single emoji.
2. The voice reading the reply aloud skips the emoji and the address behind it.
3. A MacroDroid macro sees the emoji on the screen and taps it.
4. Android hands the link to AARE instead of the browser, and AARE runs the car tool.

## Links

`https://aare.internal/go/<tool>?<arguments>`, for every car tool (see the README). `.internal`
is reserved for private networks and never resolves on the internet: there is no website behind
these links, so a link AARE doesn't take goes nowhere, and no destination leaks to a server.
AARE claims the address for itself through Shizuku or root when the car screen starts. Chat apps
make only `http` and `https` addresses tappable, which is why it is not an `aare://` link.

| Link | Does |
|---|---|
| `…/go/car_navigate?destination=Berlin` | navigates to the place in the car's navigation app |
| `…/go/car_stop_navigation` | ends the route in the car's navigation app |
| `…/go/car_media?action=next` | `play`, `pause`, `next` or `previous` |
| `…/go/car_display?mode=dark` | `dark`, `light` or `auto` (the car's day/night setting) |
| `…/go/car_open?what=Spotify` | shows an app, `home` or `apps`; never the phone screen mirror |
| `…/go/car_status` | AARE says what's playing, the next turn and the phone's battery |
| `…/go/car_messages` | AARE reads the latest messages aloud (`app=WhatsApp` for one app only) |
| `…/go/car_reply?conversation=Max&message=…` | sends the reply (`app=` when several conversations have that name) |
| `…/go/car_mark_read?conversation=Max` | marks the conversation as read |

The assistant never sees what a link did, so AARE speaks for it: the messages, the status, or
"That didn't work." when a tool fails. Everything else is silent; the assistant has already said it.

AARE runs a link only when the car (or the car screen preview) is connected and the app that
opened it is the voice assistant chosen in AARE (*Voice assistant* page).

> [!WARNING]
> Android takes the app that opened a link from what that app says about itself, and any app can
> claim to be the assistant. While the car is connected, an app on the phone could therefore send
> `car_reply` links and reply to your conversations in your name. Only install apps you trust.

The links need no setting in Android: when AARE starts a hosted app through Shizuku or root, it
approves its own link domain, so Android opens them in AARE without asking.

## Assistant instructions

Put this into the assistant's instructions, for example a Claude project used in the car or
ChatGPT's custom instructions:

```
You are my assistant in the car. Answer in one or two short sentences.
When I want something done in the car, answer briefly and add a last line with only an
emoji that links to the car action. Only one such line per answer. Arguments are URL-encoded.

- Navigate:      [🧭](https://aare.internal/go/car_navigate?destination=<place>)
- End the route: [🏁](https://aare.internal/go/car_stop_navigation)
- Play:          [▶️](https://aare.internal/go/car_media?action=play)
- Pause:         [⏸️](https://aare.internal/go/car_media?action=pause)
- Next:          [⏭️](https://aare.internal/go/car_media?action=next)
- Previous:      [⏮️](https://aare.internal/go/car_media?action=previous)
- Dark/light:    [🌙](https://aare.internal/go/car_display?mode=dark) or
                 [☀️](https://aare.internal/go/car_display?mode=light)
- Show an app:   [📱](https://aare.internal/go/car_open?what=<app, home or apps>)
- Car status:    [ℹ️](https://aare.internal/go/car_status)
- Read messages: [📨](https://aare.internal/go/car_messages)
- Reply:         [✉️](https://aare.internal/go/car_reply?conversation=<name>&message=<text>)
- Mark as read:  [✅](https://aare.internal/go/car_mark_read?conversation=<name>)

The car reads status and messages aloud itself; you don't see them, so don't make them up.
Before a reply, read it back and wait for my yes; only then add the link.
If a place, app or name is unclear, ask first and add no link.

Example: "Take me to Berlin main station" →
Alright, have a good trip!
[🧭](https://aare.internal/go/car_navigate?destination=Berlin%20Hauptbahnhof)
```

## MacroDroid macro

Assistant apps draw their links as text without a clickable node, so an accessibility click on the
emoji does nothing: the macro taps it for real, through Shizuku.

- **Trigger:** *Screen Content*, only in the assistant app (for example Claude), text matches the
  regular expression `^(🧭|🏁|▶|⏸|⏭|⏮|📱|🌓|ℹ|📨)\x{FE0F}?$`.
- **Action:** *Shell Script*, with *Shizuku*: `sh /sdcard/Download/aare-tap.sh`.

`/sdcard/Download/aare-tap.sh` finds the newest link emoji on the screen, taps it, and then taps
*Open* in the assistant's "Open this website?" prompt, only while AARE is connected to a car
(it checks AARE's `aare:projection` wake lock, held exactly then). It remembers the answer it tapped, so
reopening an old chat doesn't run its link again:

```sh
# Taps the newest AARE car link emoji in the assistant's reply, then "Open" in the app's link prompt.
# Remembers the reply it tapped, so reopening an old chat doesn't run its link again.
# Only while AARE runs a car session: it holds this wake lock exactly then. At home, nothing.
dumpsys power | grep -q 'aare:projection' || exit 0
dir=/sdcard/Download
links='&#129517;|▶️?|⏸️?|⏭️?|⏮️?|&#128241;|&#127763;|ℹ️?|&#128232;'
dump() { uiautomator dump $dir/aare-ui.xml >/dev/null 2>&1; }
# Taps the last node whose text matches $1; fails when there is none.
tap() {
  b=$(grep -oE "text=\"($1)\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" $dir/aare-ui.xml | tail -n 1 | grep -oE '[0-9]+' | tail -n 4 | tr '\n' ' ')
  set -- $b
  [ -n "$4" ] && input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
dump
# The reply text right before the newest link, with the link: what identifies this answer.
key=$(grep -oE 'text="[^"]+"' $dir/aare-ui.xml | grep -B1 -E "text=\"($links)\"" | tail -n 2 | tr '\n' ' ')
[ -z "$key" ] && exit 1
[ "$key" = "$(cat $dir/aare-last.txt 2>/dev/null)" ] && exit 0
echo "$key" > $dir/aare-last.txt
tap "$links" || exit 1
for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 0.5
  dump
  tap 'Öffnen|Open' && exit 0
done
exit 1
```

The tap comes from inside the assistant app, so Android reports the assistant as the link's
sender, which AARE requires.

## Not tested yet

- A tap on a link in the Claude app's voice mode in the car, and whether it stops voice mode.
- Whether Android reports the assistant as the sender for every assistant app.
- Whether MacroDroid sees the assistant when AARE shows it on the car screen's virtual display.
- Whether AARE's speech and the assistant's voice mode get in each other's way.
- Music search and phone calls have no car link; they still need the phone's own apps.

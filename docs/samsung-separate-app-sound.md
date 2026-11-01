# Samsung Galaxy: car audio via Separate app sound and Bluetooth

On compatible Samsung Galaxy phones, **Separate app sound** is a phone/One UI audio-output setting that can send audio from selected apps directly to a **paired Bluetooth audio device** (for example, the car's Bluetooth media input). This is a possible alternative when navigation speech is **not** carried by AARE's regular screen-capture audio source.

**This is phone-to-car Bluetooth audio, not audio sent over AARE's Android Auto Projection (AAP) media or guidance channel.** It does not alter AARE's connection handshake or make an unsupported head unit compatible.

## Configure on the phone

1. While parked, pair the Galaxy phone with the vehicle for **Bluetooth audio** and confirm the vehicle is playing that Bluetooth audio source.
2. Open **Settings → Sounds and vibration → Separate app sound**.
3. Turn **Separate app sound** on. Under **App**, select the navigation or music app(s) whose audio should use the independent output. Under **Audio device**, select the paired **Bluetooth device**.
4. Samsung requires the selected audio device to be **different from the phone's main audio output** for separate routing to take effect. Confirm the desired *media output* in the phone's quick settings and, if necessary, switch the main output accordingly.
5. With the car parked, play a music track and trigger a real **spoken navigation prompt** from the intended app. Listen for both and check for unwanted duplicate voices. Recheck output selection after connecting Android Auto or answering a call.

If a navigation app does not appear in the selectable-app list or its prompts take another output path, this method is not guaranteed to work. Do not assume calls or hands-free microphone (Bluetooth HFP) follow the same path as media (A2DP), or that two simultaneous Android Auto connections can share one audio device.

Official Samsung reference: [Use separate apps simultaneously on your Galaxy phone](https://www.samsung.com/us/support/answer/ANS10004611/) (Separate app sounds), and [Advanced audio settings](https://www.samsung.com/us/support/answer/ANS10003294/).

## Owner-reported field observation (2026-10-26)

A Galaxy S23 user reported that **music and navigation guidance speech are both audible through their actual vehicle's Bluetooth sound system** with Samsung **Separate app sound** enabled during their AARE-oriented testing. The user had also enabled AARE's screen-capture audio setting.

**What that confirms:** the user could hear both kinds of sound on the car speakers with their particular Galaxy/vehicle Bluetooth configuration.

**What it does not confirm:**
- That AARE's `MediaProjection` audio capture collected `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` PCM, or that the head unit received native AARE AAP GUIDANCE frames. Android's ordinary playback-capture API does not include navigation guidance usage.
- That the AARE display was successfully projected to the tested head unit, or that AARE delivered the audio over an Android Auto channel. The direct phone Bluetooth audio path is distinct from AAP.
- That factory Google Android Auto can run *concurrently* with AARE projection on another screen while preserving Bluetooth music, HFP calls, microphone, and audio focus. This needs its own parked device test.
- That all Galaxy versions, navigation apps, vehicle Bluetooth receivers, and head-unit combinations behave alike.

Evidence status: **user-reported audible phone→vehicle Bluetooth result**; no matched AAP packet/PCM/receiver capture or official AARE build SHA was supplied for this audio observation. It is deliberately **not** added to the list of head units proven compatible with AARE projection.

## Relationship to AARE's own Car audio setting

AARE has separate methods for carrying phone audio **over AAP**:

| Audio path | Where it is routed | What has been established |
|---|---|---|
| Samsung Separate app sound → car Bluetooth | Galaxy OS routes chosen app sound straight to the paired car Bluetooth audio device | Available in Samsung One UI; owner has heard music + nav prompts in this configuration |
| AARE screen-capture audio | Captured media/game/unknown-use audio → head unit's AAP media stream | Does **not** capture ordinary `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` |
| AARE Shizuku capture | Phone-wide PCM via AAP (combined media) | Separate AARE implementation; not required to explain the phone Bluetooth result |
| AARE privileged system routing | AAP media and guidance streams | Requires a privileged/root setup; not shown working in this field report |

If **both** AARE's AAP audio streaming and the independent Bluetooth output are enabled, test whether sound is doubled or audio focus changes before using it in a car. If the vehicle already receives the desired sound directly from Bluetooth, AARE's **Car audio** setting may not be needed; it does **not** control Samsung's separate app sound settings.

This is a **documentation-only compatibility/workaround note**, not an AARE feature implementation or head-unit audio fix. Always assess audio while parked; do not interfere with essential vehicle safety alerts.

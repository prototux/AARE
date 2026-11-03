# Debugging head-unit video flow (main + cluster channels)

This is a **passive instrumentation change**, not a compatibility fix. It does not adjust video pacing, ACK window limits, focus, stream choice, codec negotiation, TLS, or head-unit trust.

The video encoder already logs sparse keyframes; this change adds counters for **each media sink session**:

| Field | Meaning |
|---|---|
| `ch` | AAP video channel ID: generally main 3, cluster 12, but use actual advertised IDs |
| `queued` | DATA messages handed to the AAP outbound queue, **not proof of socket write** |
| `written` | DATA messages whose AAP writer onSent callback ran after successful write |
| `ackMsgs` | ACK **message** callbacks received, not decoded-frame count |
| `ackUnits` | Sum of nonnegative ACK unit counts; can include late acknowledgements from a previous stream |
| `inFlight/maxUnacked` | Approximate currently unacknowledged DATA window and negotiated maximum |
| `waiting` | Encoded frames waiting for send-window room |
| `focused`, `started`, `ackless` | Flags influencing the send path |
| `dropped` | Sender-side cumulative dropped video frames |

Counters reset on each `START`. Some asynchronous queued/written events can straddle a restart, so they should be interpreted as diagnostics, not a formally synchronized protocol trace. Codec configuration messages are not included in DATA counters.

## Reproduce a receiver with a black screen

1. Start a **phone-side** AARE Logcat and **head-unit-side** Logcat simultaneously *before* one 30-second parked test. Sample Android CMD commands (replace ADB serials):
   ```bat
   adb -s PHONE_SERIAL logcat -b main -v threadtime > phone_aare.txt
   adb -s HU_SERIAL logcat -b main -v threadtime > receiver_hur.txt
   ```
   Use two terminal windows; do not clear buffers first if you need earlier evidence. These logs can contain unrelated private identifiers, so review/redact them before sharing.
2. Look at the phone's `AareSession` (main) and `AareCluster` tags (cluster), `AareBitrate`, and AAP control/channel-open logs. The diagnostic message shows `queued/written/ackMsgs/ackUnits` plus window and focus.
3. Compare one session using AARE's own desktop head unit with one session using the failing HUR receiver, **one changed variable at a time**. Capture receiver decoder start, DATA receipt, ACK generation and displayed-frame evidence where receiver logs permit.

A zero `ackMsgs` with nonzero `written`, a full `inFlight/maxUnacked` and nonzero `waiting` raises a *no-ACK/window-stall hypothesis* but does not prove whether the HUR actually failed to send ACKs: the AARE reader could also be failing to process them. Conversely, a nonzero ACK count excludes a categorical claim of “zero ACKs”. The original `ackLatencyMs = 0` by itself did **not** prove that any ACK was missing.

## Motivating report (not verified as fixed)

Owner tests with AARE 1.1.1 / Huawei MediaPad M5 HUR 8.2.0 showed AAP/TLS 11-channel setup and video encoding attempts, but no M5 picture. An earlier phone log had video frames dropped during backlog and some early ch3 UI/focus notifications before service registration. A **different, later receiver-side log** showed wireless startup, main+cluster START, three software-decoder creations within 478 ms, Player/focus churn, and application-exit before socket teardown. They are **not two halves of a single synced run**, so precise DATA/ACK/frame causation remains unknown. Raw private logs (SSID, MAC, accessory/cert identifiers) are **not included**.

No build or actual head-unit runtime verification has been performed on this instrumentation branch. It intentionally leaves compatibility behavior unchanged until matched receiver/send-path data are available.

# Reference projects and AAP protocol notes (phone / MD side)

Research date: 2026-10-06, from shallow clones of each project. Everything below is paraphrased;
quoted strings are protocol constants.

Terminology: **HU** = head unit (car side, "receiver"), **MD** = mobile device (phone, "sender"), **AAP/GAL** =
Android Auto Projection protocol (Google calls it GAL, "Google Automotive Link"), **AOA** = Android Open Accessory,
**WPP** = Wi-Fi Projection Protocol (the Bluetooth/RFCOMM bootstrap for wireless AA), **SDR** = ServiceDiscoveryResponse.

---

## 0. TL;DR for the AARE (phone-side) project

* **Open-source phone-side implementations exist**, none of them an Android app:
  * **behead** (khimaros/behead, Rust, GPL-3.0-only, active Oct 2026): the closest match. `no_std` protocol core
    (`crates/aap`: framing, session state machine, hand-written prost messages), TCP (default `127.0.0.1:5277`) and
    Linux USB-gadget (configfs + FunctionFS, full AOA switch) transports, rustls TLS server with an ordered
    certificate-fallback list. Tested against openauto, a scripted Python HU, a Raspberry Pi and **a real 2021
    Mercedes Sprinter HU (which accepts a self-signed phone certificate)**. Its `DESIGN.md` and
    `docs/protocol-survey.md` are the best existing write-up of the MD side.
  * **AACS / AAServer** (tomasz-grobelny/AACS, C++, GPLv3-only, last commit 2022): MD side over a Linux USB gadget;
    worked on a Seat Ateca LG HU (whose clock had to be wound back, see TLS section). Many shortcuts (no receive-side
    reassembly, no video focus request, etc. — catalogued in behead's survey).
  * MD-side logic also lives inside the MITM proxies: **aa-proxy-rs** MITM (`ProxyType::HeadUnit` side acts as the
    phone toward the car, TLS server with `md_cert.pem`), its "car-wifi"/bt-wireless-proxy modes (acts as the phone
    toward a *wireless* HU: SDP lookup + RFCOMM client + Wi-Fi STA + TCP client), and **opengal_proxy** (C++,
    GPL-3.0-only, `MobileDeviceProxy` = TLS server).
  * lucalewin/vehiculum (Rust) has an "android_auto" server stub only; no license file.
* **TLS roles**: HU = TLS client (sends ClientHello, presents a client certificate), MD = TLS server (presents
  the "phone" certificate and requests the HU's). TLS 1.2 only, carried inside AAP control messages (id 3).
* **Does the HU verify the phone certificate?** Google's Head Unit Integration Guide (HUIG 1.3, 2016) says yes:
  both certificates are signed by the Google CA ("Google Automotive Link") and the receiver library on the HU
  verifies the MD certificate (chain + validity dates) and drops the connection if it fails. Field evidence:
  LG HU in a Seat validates (Tomasz Grobelny), HUs with a wrong RTC fail AA with certificate date errors
  (e.g. Mazda "year 2045" reports). Counter-evidence: openauto/aasdk, headunit-revived, LIVI and the 2021 Sprinter
  accept anything. **Expect real OEM HUs to require a currently valid Google-signed MD certificate**, which an
  open-source app cannot legitimately ship. The only known source is the key pair embedded (AES-obfuscated) in the
  Gearhead APK ("carservice.crt", expires 2026-12-23 per behead); public copies (AACS "O=CarService, OU=53") are
  expired. The phone in turn verifies the HU certificate (this is why the original 2015 self-signed headunit app
  broke after a Play Services update); our app may choose to be lenient.
* **Licensing**: every useful implementation is copyleft (GPLv2, GPLv3, AGPLv3). Only WirelessAndroidAutoDongle is
  MIT. The protobuf *schemas* everyone uses originate from descriptors embedded in Google's Desktop Head Unit
  binary (no license). See section 11.

---

## 1. Projects

### 1.1 opencardev/aasdk
* **What**: C++ library implementing the HU side of AAP (framing, TLS via OpenSSL memory BIOs, channels, USB AOA host,
  TCP). Basis of openauto, OpenAutoLink (via JNI) and many derivatives. Branch at HEAD dated 2026-06.
* **Language / license**: C++17 + Boost.Asio + protobuf + libusb + OpenSSL. GPL-3.0-or-later (file headers;
  Readme says GPLv3; no LICENSE file in the snapshot).
* **Transports**: USB (libusb host, AOA query chain), TCP (endpoint wrapper; the HU can be TCP client or server).
* **Protos**: `protobuf/aap_protobuf/**` (254 files, restructured from Google's DHU descriptors: `aaw/` = wireless
  RFCOMM messages, `service/*` = per-service messages, `channel/control/*` = GAL verification / vendor-extension
  ids, `shared/*`). Also `docs/protos.proto` = the raw single-file DHU dump
  (`java_package = "com.google.android.projection.proto"`), and legacy `aasdk_proto/`.
* **Certificate**: embeds a HU client cert `C=JP, O=JVC Kenwood, OU=01`, issuer `O=Google Automotive Link`
  (valid 2014-07-04 .. 2045-04-29) plus its RSA key (`cert/headunit.crt|key`, also hard-coded in `Cryptor.cpp`).
* **Reusable for us**: the framing/messenger logic (as a spec, mirror-imaged), message-id enums, the whole proto set,
  the HU behaviour we must interoperate with. Being GPLv3, direct code reuse forces our app to GPLv3.

### 1.2 opencardev/openauto
* **What**: the HU application on top of aasdk (Qt UI, OMX/Qt video, RtAudio/Qt audio, gpsd, BlueZ via Qt
  Bluetooth). Crankshaft-NG ships it.
* **Language / license**: C++/Qt, GPLv3.
* **Transports**: USB (via aasdk), Wi-Fi: (a) "connect to phone" dialog = HU is TCP *client* to phone IP port
  **5277** (Gearhead "head unit server" developer mode); (b) wireless: TCP *server* on port **5000** + Bluetooth
  RFCOMM server advertising the AA wireless UUID (`btservice/AndroidBluetoothServer.cpp`).
* **SDR it builds** (`AndroidAutoEntity::onServiceDiscoveryRequest` + each service's `fillFeatures`): see §5.3.
* **Reusable for us**: a free, easy test HU (desktop or Raspberry Pi) that does not verify the phone certificate.
  Its behaviour defines a big part of the "open-source HU" interop surface. Quirks: it never answers phone
  `PingRequest`s, acks every media frame with `max_unacked = 1`, grants audio focus for anything but RELEASE,
  answers nav focus with PROJECTED, advertises `VIDEO_800x480 @ VIDEO_FPS_30`, density 140 by default.

### 1.3 nisargjhaveri/WirelessAndroidAutoDongle
* **What**: Buildroot image turning a Raspberry Pi (Zero W/2W, 3A+, 4) into a wireless AA dongle. The Pi presents
  itself to the car as a phone in **AOA accessory mode** (USB gadget `18D1:2D00`, kernel `f_accessory` backported)
  and relays raw bytes to the phone over TCP; it does **not** terminate TLS.
* **Language / license**: C++ (`aawgd`), shell, Buildroot. **MIT**.
* **Transports**: BT RFCOMM (BlueZ profile, UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`, channel 8, role server),
  optional BLE advertisement of the same UUID ("dongle mode"), HSP-HS profile to keep the phone's link up, Wi-Fi AP
  (`AAWirelessDongle`/`ConnectAAWirelessDongle`, 10.0.0.1), TCP server **5288**, USB gadget (accessory + an MTP
  "default" gadget `18D1:4EE1` until the car asks for accessory).
* **Protos**: `WifiStartRequest.proto`, `WifiInfoResponse.proto` (with the correct on-air `SecurityMode` values).
* **Reusable for us**: the minimal, readable wireless bootstrap (MIT, so code-level reuse is fine); the USB gadget
  recipe that makes a Linux box look like a phone to a real car.

### 1.4 aa-proxy/aa-proxy-rs (formerly manio/aa-proxy-rs)
* **What**: Rust successor of the dongle daemon. Passthrough proxy (phone over BT+Wi-Fi ↔ car over USB gadget/TCP),
  plus an optional **MITM** mode that terminates TLS on both legs and rewrites messages (DPI, EV routing, developer
  mode, video-in-motion, album art, injected displays, WASM hooks...). Also "wired USB phone mode", DHU support,
  and experimental modes that act as the phone toward wireless HUs (SDP discovery of the HU's AA RFCOMM channel,
  join the HU's Wi-Fi, connect to its TCP endpoint).
* **Language / license**: Rust (tokio / io_uring), **GPL-2.0**.
* **MITM certificates** (`/etc/aa-proxy-rs/`): `md_cert.pem`/`md_key.pem` = what the proxy presents **to the
  car** while pretending to be the phone (TLS server, `set_accept_state`); `hu_cert.pem`/`hu_key.pem` = what it
  presents **to the phone** while pretending to be the car (TLS client); `galroot_cert.pem` = Google Automotive Link
  root used as CA file. The repo refuses to ship them (pointers to AACS, vehiculum, Headunit Reloaded). Its own
  verifiers accept any peer certificate (also X.509 v1). Two TLS backends: rustls (ECDHE+GCM only) and OpenSSL for
  "legacy head units that only offer TLS_RSA_WITH_AES_128_CBC_SHA". TLS 1.2 pinned, TLS 1.3 disabled, session
  resumption disabled ("AA devices may send tickets from a previous openssl session").
* **Protos**: `src/protos/protos.proto` (≈ DHU dump + extras), `ev.proto`, `WifiInfoResponse.proto`,
  `WifiStartRequest.proto`. Wireless message ids include 8/9 (WifiPing) and 11 (WifiSetupInfo).
* **Reusable for us**: the MITM path is a working reference for the MD side of the TLS handshake and for
  how real cars react; the bluetooth.rs code documents many real-HU wireless quirks (see §6, §8).

### 1.5 mossyhub/openautolink
* **What**: AA receiver app for Android Automotive OS cars without native AA (GM EVs). Embeds a fork of aasdk 1.6
  via JNI; Kotlin does transport, MediaCodec video, AudioTrack audio, VHAL sensors. A phone "companion" app relays
  the phone's AA socket. Implements the factory BT WPP bootstrap.
* **Language / license**: Kotlin + C++ (JNI), **GPL-3.0-or-later**.
* **Transports**: USB AOA v2 (Android USB host API), Wi-Fi WPP (BT RFCOMM + TCP, port 5277 in the bridge code).
* **Reusable for us**: very rich field notes in `docs/` (`embedded-knowledge.md`, `experimental-gal6.md`,
  `aa-undocumented-features.md`, `focus-bounce-encoder-throttle-postmortem.md`, `research/aa-replacement-*.md`
  = Gearhead 17.4 teardown and why an unprivileged app cannot host official Maps). Version-negotiation findings
  (GAL 1.7 / 4.3 / 5.0 / 5.1 / 6.0) are unique to this repo.

### 1.6 andreknieriem/open-headunit ("Headunit Revived")
* **What**: Android app turning a tablet/phone into an AA HU; revival of Mike Reid's 2015 headunit. Very active
  (Oct 2026), Play-store distributed.
* **Language / license**: Kotlin + C++ (JNI), **AGPL-3.0** (+ Michael A. Reid copyright notice).
* **Transports**: USB host (AOA), Wi-Fi: "Native mode" (implements WPP over RFCOMM and over TCP, Wi-Fi Direct or
  HU hotspot), "Headunit Server" (connects to the phone's port 5277), legacy Wireless Helper (≤ AA 17.3),
  FYT `/dev/auto_serial` external BT module.
* **TLS**: JSSE/Conscrypt `SSLEngine` in client mode with a HU key, `NoCheckTrustManager` (does not verify the
  phone), keeps a client session cache so reconnects can resume the TLS session.
* **Protos**: `app/src/main/proto/*.proto`; `wireless.proto` is the best public WPP schema (types 1–11, version 4.2
  with car info, WPP-over-TCP endpoint, rejection reasons, QR deep link payload).
* **Reusable for us**: second open-source HU to test against (on any Android tablet); the WPP state machine and its
  timeouts; documentation of AA 17.4 changes. AGPL makes code reuse even more constraining.

### 1.7 f-io/LIVI
* **What**: Linux/macOS head unit (Electron/TypeScript UI + Rust helper daemon `livi-helperd`) for CarPlay and
  Android Auto, wired and wireless, GStreamer zero-copy video.
* **Language / license**: TypeScript + Rust + C, **GPL-3.0**.
* **Transports**: USB AOA, Wi-Fi with its own AP; BT SDP record for AA:
  `ServiceClassIDList = 4de17a00-…`, `ProtocolDescriptorList = L2CAP, RFCOMM ch 8`,
  `BrowseGroupList = PublicBrowseRoot (0x1002)`, `ServiceName = "Android Auto Wireless"`; TCP 5277.
* **Notes**: AA stack re-implemented in TS (`stack/constants.ts` lists flags/ids; asks for protocol 1.7), TLS client
  in Rust with an accept-any verifier and the same public HU cert. Ships copies of aasdk's `aap_protobuf` plus older
  `oaa` protos. SDR advertises 20+ sensor types, cluster video (channel 19) and cluster input (20).
* **Reusable for us**: third independent HU for interop testing; another set of SDR defaults (dpi 140, ping
  interval 1500 ms / timeout 5000 ms).

### 1.8 mikereidis/headunit and borconi/headunit ("Headunit Reloaded")
* **What**: the original 2015 Android HU app (C/JNI `hu_aap.c`, `hu_ssl.c`, `hu_usb.c`) and Emil Borconi's fork.
* **License**: AGPL-3.0 (Michael A. Reid).
* **Notes**: the 2015 code used a *self-signed* HU certificate and stopped working after Google Play services 9.2
  (the phone started enforcing the Google-signed HU certificate). Hard-coded byte arrays document early message
  layouts (e.g. setup response `08 02 10 <max_unack> 18 00`, ack `08 00 10 01`). Borconi's `jni/hu_ssl.h` is one of
  the places the README of aa-proxy-rs points to for certificate material.

### 1.9 tomasz-grobelny/AACS (Android Auto Car Server) — **phone side**
* **What**: `AAServer` = MD-side AAP over a Linux USB gadget (libusbgx + FunctionFS), exposes channels on a Unix
  socket; `AAClient` = HU-side endpoint that proxies a real phone into AAServer; GStreamer/Snowmix video.
* **Language / license**: C++, **GPL-3.0-only**. Last commit 2022-05.
* **Certificates**: `AAServer/ssl/android_auto.crt` = `O=CarService, OU=53`, issuer Google Automotive Link, X.509 v1,
  expired (upstream copy: 2022-08-24); `AAClient/ssl/headunit.crt` = `O=Android-Auto-Internal, OU=01` (valid to
  2048).
* **USB**: pre-switch gadget `12D1:107E` ("TAG"/"AAServer") with a mass-storage LUN + FunctionFS ep0 that answers AOA
  request 51 with version 2, logs 52, switches on 53 to a `18D1:2D00` gadget with one vendor interface / 2 bulk eps.
* **Reusable for us**: historical MD-side reference; its proto names are partially wrong (see behead survey).

### 1.10 khimaros/behead — **phone side** (most relevant)
* **What**: "an android auto server in rust: the half of android auto that normally runs on a phone." Turns a
  Raspberry Pi/laptop into the phone: runs any H.264 encoder command, forwards HU touch/keys/sensors to Wayland
  sessions (kiosk, phosh, Kodi, Waydroid), plays PCM to the HU, reads the HU microphone.
* **Language / license**: Rust 2021, **GPL-3.0-only**. Very active (last commit 2026-10-04).
* **Architecture**: `crates/aap` (no_std, IO-free; `Session::receive()` / `take_output()`, `Tls` trait in memory-BIO
  style), `crates/server` (rustls TLS server, TCP listener, configfs/FunctionFS gadget with AOA switch, uinput,
  NMEA socket).
* **Certificates**: `--cert/--key` repeated or `--cert-dir`; on a handshake that starts but never finishes, the next
  connection offers the next certificate (TLS 1.2 ClientHello carries no CA hints). Logs TLS alerts
  (UnknownCA / CertificateExpired / BadCertificate). Companion tool "dexter" extracts the Google root and the
  Gearhead phone pair from the APK (behead tests use them only if supplied via env vars).
* **Reusable for us**: the session state machine and its ordering decisions, the AOA gadget lifecycle, the test
  harness idea (independent scripted HU + openauto in a VM). Licence prevents copying into a non-GPL app.

### 1.11 Other tools
* **gamelaster/opengal_proxy** (C++, GPL-3.0-only): TCP-via-ADB MITM; phone side offers only
  `ECDHE-RSA-AES128-GCM-SHA256`; HU proxy port 5277, real phone forwarded to 5278. Requires user-sourced certs.
* **milek7 "galdocs"** (https://milek7.pl/.stuff/galdocs/): leaked HUIG 1.3 (Google confidential, do not
  redistribute), `protos.proto`/`common.proto` extracted from the DHU with pbtk, a Wireshark Lua dissector, a
  libusb AOA→Unix-socket bridge (`apserver.c`), and an LD_PRELOAD TLS key logger for the DHU.
* **Google Desktop Head Unit (DHU)**: official HU emulator (Android SDK extras). Talks TCP to the phone's
  "head unit server" (port 5277, via `adb forward tcp:5277 tcp:5277`) or AOA with `--usb`. Whether the DHU verifies
  the phone certificate is **not documented anywhere we found** — must be tested.
* Not found: any open-source Android-app implementation of the phone side, and any public spec of the projection
  layer beyond the leaked HUIG.

---

## 2. AAP framing (as implemented by aasdk, confirmed by AACS/behead/aa-proxy-rs)

The transport (USB bulk endpoints or TCP) is a byte stream of **frames**; readers must reassemble frames across
arbitrary read boundaries.

```
offset  size  field
0       1     channel id (0 = control; others = service ids from the SDR)
1       1     flags
2       2     frame payload length, big endian (bytes on the wire after the header)
[4      4     total message length, big endian — ONLY on a FIRST frame of a multi-frame message]
4 or 8  N     payload (ciphertext if ENCRYPTED, else plaintext)
```

**Flags byte**

| bit | mask | meaning |
|-----|------|---------|
| 0   | 0x01 | FIRST fragment |
| 1   | 0x02 | LAST fragment |
| 2   | 0x04 | CONTROL message type (a control-enum message id carried on a *service* channel; see below) |
| 3   | 0x08 | ENCRYPTED (payload is TLS application data) |

Frame type = `flags & 0x03`: `0x03` BULK (unfragmented), `0x01` FIRST, `0x00` MIDDLE (Google: "continuation"),
`0x02` LAST.

Common flag bytes: `0x03` plaintext single frame (version + TLS handshake), `0x0B` encrypted single frame,
`0x0F` encrypted control message on a service channel (ChannelOpenRequest/Response), `0x09`/`0x08`/`0x0A` encrypted
first/middle/last fragment.

**Fragmentation**
* Sender splits when the plaintext message is ≥ `0x4000` (16384) bytes (`cMaxFramePayloadSize`), in chunks of at most
  16384 plaintext bytes. FIRST carries the 6-byte size block (2-byte frame length + 4-byte **total plaintext length of
  the whole message**, i.e. including the 2-byte message id); MIDDLE/LAST carry only the 2-byte length.
* The 2-byte frame length is the number of payload bytes on the wire, i.e. **ciphertext length** for encrypted frames.
* aasdk reassembles per channel (it keeps one partial message per channel id), so frames of different channels may
  interleave; a MIDDLE/LAST without a preceding FIRST is an error. Older aasdk (2018) rejected any interleaving
  (`MESSENGER_INTERTWINED_CHANNELS`); behead and AACS never interleave. **Safe choice: send all frames of one message
  back to back.**
* AACS used 2000-byte fragments ("weird hardware issues" beyond that); aasdk/behead/real phones use 16 KiB.
* USB: the AOA function's bulk buffer is 16384 bytes; a write that exactly fills the last USB packet must be followed
  by a ZLP (the Android kernel's f_accessory does this; behead does it in its gadget).

**Encryption**
* After the TLS handshake, every frame whose ENCRYPTED bit is set carries TLS records; each frame is encrypted and
  decrypted independently (the plaintext of all frames of a message is concatenated).
* **aasdk's `Cryptor::decrypt` assumes exactly one TLS record per frame with 29 bytes of overhead** (5-byte record
  header + 8-byte explicit nonce + 16-byte GCM tag): plaintext length = frame length − 29. So the MD must produce
  exactly one AES-GCM record per AAP frame (≤ 16384 plaintext bytes); CBC suites or multiple records per frame break
  aasdk-based HUs.
* The ENCRYPTED bit is per frame; receivers must follow it (aasdk sends PingRequest/PingResponse/AuthComplete
  **plaintext** even after the handshake; real phones send pings encrypted).

**Message payload** (after reassembly / decryption)
```
0  2  message id, big endian
2  …  body: protobuf (most messages) or raw bytes (version request/response, TLS records, media data)
```
* **CONTROL vs SPECIFIC**: message ids < 0x8000 come from the control enum; service-specific ids are ≥ 0x8000 (plus
  media data ids 0x0000/0x0001 on media channels). On **channel 0** everything is sent with bit 2 *clear* (aasdk uses
  `MessageType::SPECIFIC` for all control-channel messages). On a **service channel**, control-enum messages
  (ChannelOpenRequest 7 / ChannelOpenResponse 8, ChannelCloseNotification 9) are sent with bit 2 *set*; the
  service's own messages with bit 2 clear. Receivers in practice dispatch on (channel, message id); behead treats
  "channel 0 or CONTROL bit" as a control message.
* Media data: `MEDIA_MESSAGE_DATA` (0x0000) body = 8-byte big-endian timestamp (microseconds) + payload;
  `MEDIA_MESSAGE_CODEC_CONFIG` (0x0001) body = payload without timestamp (SPS/PPS etc.).

---

## 3. Message ids

### 3.1 Control channel (channel 0) — `ControlMessageType`

| id | name | direction | encryption |
|----|------|-----------|------------|
| 1 | VERSION_REQUEST | HU→MD | plain. Body: u16 major, u16 minor (BE), optional `VersionRequestOptions{snapshot_version}` protobuf after |
| 2 | VERSION_RESPONSE | MD→HU | plain. Body: u16 major, u16 minor, u16 status (0 = match, 0xFFFF = -1 no compatible version), optional `VersionResponseOptions{connection_configuration}` |
| 3 | ENCAPSULATED_SSL | both | plain. Body = raw TLS handshake records |
| 4 | AUTH_COMPLETE | HU→MD | aasdk: plain. `AuthResponse{status=0}` |
| 5 | SERVICE_DISCOVERY_REQUEST | MD→HU | encrypted. icons, label_text, device_name, `PhoneInfo{instance_id, connectivity_lifetime_id}` |
| 6 | SERVICE_DISCOVERY_RESPONSE | HU→MD | encrypted (see §5.3) |
| 7 | CHANNEL_OPEN_REQUEST | MD→HU, on the service's channel, CONTROL bit | `{sint32 priority=1, int32 service_id=2}` |
| 8 | CHANNEL_OPEN_RESPONSE | HU→MD, same channel, CONTROL bit | `{MessageStatus status}` |
| 9 | CHANNEL_CLOSE_NOTIFICATION | either | |
| 11 | PING_REQUEST | either | `{int64 timestamp, bool bug_report, bytes data}` |
| 12 | PING_RESPONSE | either | `{int64 timestamp, bytes data}` (echo) |
| 13 | NAV_FOCUS_REQUEST | MD→HU | `NavFocusRequestNotification{focus_type}` |
| 14 | NAV_FOCUS_NOTIFICATION | HU→MD | `{NavFocusType: 1 NATIVE, 2 PROJECTED}` |
| 15 | BYEBYE_REQUEST | either | `{ByeByeReason: 1 USER_SELECTION, 2 DEVICE_SWITCH, 3 NOT_SUPPORTED, 4 NOT_CURRENTLY_SUPPORTED, 5 PROBE_SUPPORTED}` |
| 16 | BYEBYE_RESPONSE | either | empty |
| 17 | VOICE_SESSION_NOTIFICATION | MD→HU | `{VoiceSessionStatus: START/END}` |
| 18 | AUDIO_FOCUS_REQUEST | MD→HU | `{AudioFocusRequestType: 1 GAIN, 2 GAIN_TRANSIENT, 3 GAIN_TRANSIENT_MAY_DUCK, 4 RELEASE}` |
| 19 | AUDIO_FOCUS_NOTIFICATION | HU→MD | `{AudioFocusStateType state, bool unsolicited}`: 0 INVALID, 1 GAIN, 2 GAIN_TRANSIENT, 3 LOSS, 4 LOSS_TRANSIENT_CAN_DUCK, 5 LOSS_TRANSIENT, 6 GAIN_MEDIA_ONLY, 7 GAIN_TRANSIENT_GUIDANCE_ONLY |
| 20 / 21 | CAR_CONNECTED_DEVICES_REQUEST / RESPONSE | | |
| 22 / 25 | USER_SWITCH_REQUEST / RESPONSE | | (older "oaa" protos used 25/26 as key BINDING req/resp — do not trust) |
| 23 | BATTERY_STATUS_NOTIFICATION | MD→HU | `{battery_level, time_remaining_s, critical_battery}` |
| 24 | CALL_AVAILABILITY_STATUS | | |
| 26 | SERVICE_DISCOVERY_UPDATE | HU→MD | |
| 255 | UNEXPECTED_MESSAGE | | |
| 65535 | FRAMING_ERROR | | |

`MessageStatus` (shared): 0 SUCCESS, 1 UNSOLICITED_MESSAGE, -1 NO_COMPATIBLE_VERSION, -2 CERTIFICATE_ERROR,
-3 AUTHENTICATION_FAILURE, -4 INVALID_SERVICE, -5 INVALID_CHANNEL, -6 INVALID_PRIORITY, -7 INTERNAL_ERROR,
-8 MEDIA_CONFIG_MISMATCH, -9 INVALID_SENSOR, -10 … -17 Bluetooth errors (-10 BLUETOOTH_PAIRING_DELAYED),
-18 KEYCODE_NOT_BOUND, -19…-22 radio/input, -23 AUTH_FAILURE_CERT_NOT_YET_VALID, -24 AUTH_FAILURE_CERT_EXPIRED,
-25 PING_TIMEOUT, -250 COMMAND_NOT_SUPPORTED, -251 FRAMING_ERROR, -253 UNEXPECTED_MESSAGE, -254 BUSY,
-255 OUT_OF_MEMORY.

### 3.2 Media channels (video sink, audio sinks, microphone source) — `MediaMessageId`

| id | name | direction (sink = video/audio out of the phone; source = HU microphone) |
|----|------|-------------------------------------------------------------------------|
| 0x0000 | DATA (media with timestamp) | sink: MD→HU; mic: HU→MD |
| 0x0001 | CODEC_CONFIG (media without timestamp) | MD→HU (SPS/PPS, AAC config) |
| 0x8000 | SETUP | MD→HU `Setup{MediaCodecType type}` (also sent by MD on the mic channel) |
| 0x8001 | START | MD→HU `Start{session_id, configuration_index}` (LIVI: HU→MD on the mic channel) |
| 0x8002 | STOP | MD→HU `Stop{}` |
| 0x8003 | CONFIG (setup response) | HU→MD `Config{status: 1 WAIT / 2 READY, max_unacked, repeated configuration_indices}` |
| 0x8004 | ACK | HU→MD for sinks `Ack{session_id, ack (count), repeated receive_timestamp_ns}`; MD→HU for mic data |
| 0x8005 | MICROPHONE_REQUEST | MD→HU `{open, anc_enabled, ec_enabled, max_unacked}` |
| 0x8006 | MICROPHONE_RESPONSE | HU→MD `{status, session_id}` |
| 0x8007 | VIDEO_FOCUS_REQUEST | MD→HU `VideoFocusRequestNotification{disp_channel_id (deprecated), mode, reason}` |
| 0x8008 | VIDEO_FOCUS_NOTIFICATION | HU→MD `{VideoFocusMode focus, bool unsolicited}`; modes 1 PROJECTED, 2 NATIVE, 3 NATIVE_TRANSIENT, 4 PROJECTED_NO_INPUT_FOCUS |
| 0x8009 / 0x800A | UPDATE_UI_CONFIG_REQUEST / REPLY | |
| 0x800B | AUDIO_UNDERFLOW_NOTIFICATION | |
| 0x8014 | MediaOptions (GAL ≥ 5.1, per OpenAutoLink) | |

`MediaCodecType`: 1 AUDIO_PCM, 2 AUDIO_AAC_LC, 3 VIDEO_H264_BP, 4 AUDIO_AAC_LC_ADTS, 5 VIDEO_VP9, 6 VIDEO_AV1,
7 VIDEO_H265.

### 3.3 Other services (all ids ≥ 0x8001; direction from the phone's point of view)

| service | id | name | direction |
|---------|----|------|-----------|
| Input source | 0x8001 | INPUT_REPORT `InputReport{timestamp µs, touch_event, key_event, absolute_event, relative_event, touchpad_event}` | HU→MD |
| | 0x8002 | KEY_BINDING_REQUEST `{repeated int32 keycodes (packed)}` | MD→HU |
| | 0x8003 | KEY_BINDING_RESPONSE `{status}` | HU→MD |
| | 0x8004 | INPUT_FEEDBACK (haptics) | MD→HU |
| Sensor source | 0x8001 | SENSOR_REQUEST `{SensorType type, int64 min_update_period}` | MD→HU |
| | 0x8002 | SENSOR_RESPONSE `{status}` | HU→MD |
| | 0x8003 | SENSOR_BATCH (`SensorBatch`, repeated per type) | HU→MD |
| | 0x8004 | SENSOR_ERROR | HU→MD |
| Bluetooth | 0x8001 | PAIRING_REQUEST `{phone_address, pairing_method}` | MD→HU |
| | 0x8002 | PAIRING_RESPONSE `{status, already_paired}` (HUIG: within 1 s) | HU→MD |
| | 0x8003 | AUTHENTICATION_DATA `{auth_data, pairing_method}` | HU→MD (openauto) |
| | 0x8004 | AUTHENTICATION_RESULT `{status}` | MD→HU |
| Navigation status (instrument cluster) | 0x8001 / 0x8002 | INSTRUMENT_CLUSTER_START / STOP | MD→HU |
| | 0x8003 | NAVIGATION_STATUS | MD→HU |
| | 0x8004 / 0x8005 | TURN_EVENT / DISTANCE_EVENT (deprecated) | MD→HU |
| | 0x8006 / 0x8007 | NAVIGATION_STATE / CURRENT_POSITION | MD→HU |
| | 0x8008 | VehicleEnergyForecast (GAL ≥ 5.1, OpenAutoLink) | MD→HU |
| Media playback status | 0x8001 | MEDIA_PLAYBACK_STATUS | MD→HU |
| | 0x8002 | MEDIA_PLAYBACK_INPUT | HU→MD |
| | 0x8003 | MEDIA_PLAYBACK_METADATA | MD→HU |
| Phone status | 0x8001 | PHONE_STATUS (calls, caller, photo) | MD→HU |
| | 0x8002 | PHONE_STATUS_INPUT | HU→MD |
| Wi-Fi projection (in-session) | 0x8001 | WIFI_CREDENTIALS_REQUEST `{}` | MD→HU |
| | 0x8002 | WIFI_CREDENTIALS_RESPONSE `{car_wifi_password, car_wifi_security_mode, car_wifi_ssid, supported_wifi_channels, access_point_type}` | HU→MD |
| Media browser | 0x8001–0x8006 | ROOT/SOURCE/LIST/SONG node, GET_NODE, BROWSE_INPUT | |
| Generic notification | 0x8001–0x8004 | SUBSCRIBE, UNSUBSCRIBE, MESSAGE, ACK | |
| Radio | 0x8001–0x8019 | 25 radio messages | |
| Vendor extension | — | opaque, defined by the vendor service name; GAL verification ids 0x8001–0x800B | |

`SensorType`: 1 LOCATION, 2 COMPASS, 3 SPEED, 4 RPM, 5 ODOMETER, 6 FUEL, 7 PARKING_BRAKE, 8 GEAR,
9 OBDII_DIAGNOSTIC_CODE, 10 NIGHT_MODE, 11 ENVIRONMENT_DATA, 12 HVAC_DATA, 13 DRIVING_STATUS_DATA,
14 DEAD_RECKONING_DATA, 15 PASSENGER, 16 DOOR, 17 LIGHT, 18 TIRE_PRESSURE, 19 ACCELEROMETER, 20 GYROSCOPE,
21 GPS_SATELLITE, 22 TOLL_CARD (newer HUs add vehicle energy model types). `DrivingStatus` is a bit mask:
0 UNRESTRICTED, 1 NO_VIDEO, 2 NO_KEYBOARD_INPUT, 4 NO_VOICE_INPUT, 8 NO_CONFIG, 16 LIMIT_MESSAGE_LEN.

Touch: `TouchEvent{repeated Pointer{x, y, pointer_id} pointer_data, action_index, PointerAction action}` with
0 DOWN, 1 UP, 2 MOVED, 5 POINTER_DOWN, 6 POINTER_UP (Android MotionEvent semantics); coordinates are in the
touchscreen space advertised in the SDR. Keys: `KeyEvent{repeated Key{keycode, down, metastate, longpress}}`, Android
keycodes plus AA extras (65536 ROTARY_CONTROLLER (sent as RelativeEvent delta), 65537 MEDIA, 65538 NAVIGATION,
65540 TEL). Both down and up must be sent for each press.

### 3.4 Channel id conventions
* Channel ids **are** the `Service.id` values the HU puts in the SDR; the MD must use them, never assume.
* aasdk/openauto static assignment (also used by LIVI/OpenAutoLink): 0 CONTROL, 1 SENSOR, (2 unused "MEDIA_SINK"),
  3 VIDEO, 4 MEDIA_AUDIO, 5 GUIDANCE_AUDIO, 6 SYSTEM_AUDIO, 7 TELEPHONY_AUDIO, 8 INPUT, 9 MICROPHONE, 10 BLUETOOTH,
  11 RADIO, 12 NAVIGATION_STATUS, 13 MEDIA_PLAYBACK_STATUS, 14 PHONE_STATUS, 15 MEDIA_BROWSER, 16 VENDOR_EXTENSION,
  17 GENERIC_NOTIFICATION, 18 WIFI_PROJECTION; LIVI adds 19 cluster video, 20 cluster input. aa-proxy-rs injects
  extra services at "next free id". Real cars use their own numbering.
* Audio sink type comes from `MediaSinkService.audio_type`: 1 GUIDANCE, 2 SYSTEM_AUDIO, 3 MEDIA, 4 TELEPHONY.
  Several video sinks can exist (`display_type` 0 MAIN, 1 CLUSTER, 2 AUXILIARY; `display_id`); the MD must pick
  MAIN (or the one without display_type) as the main screen.

---

## 4. Wireless RFCOMM bootstrap ("WPP") message ids

Framing on the RFCOMM socket (and on the newer WPP-over-TCP socket): `u16 BE body length` + `u16 BE message id` +
protobuf body (length excludes the 4-byte header).

| id | message | direction | notes |
|----|---------|-----------|-------|
| 1 | WifiStartRequest `{ip_address, port, [status]}` | HU→MD, or MD→HU | where the AAP TCP server is; an empty MD→HU one asks the HU to start (§6 step 3) |
| 2 | WifiInfoRequest `{}` | MD→HU | asks for AP credentials |
| 3 | WifiInfoResponse `{ssid, key/password, bssid, security_mode, access_point_type}` | HU→MD | `security_mode` on air: 0 UNKNOWN, 1 OPEN, 2 WEP_64, 3 WEP_128, 4 WPA_PERSONAL, **8 WPA2_PERSONAL**, 12 WPA_WPA2_PERSONAL, 20 WPA_ENTERPRISE, 24 WPA2_ENTERPRISE, 28 WPA_WPA2_ENTERPRISE, 32 WPA3_PERSONAL, 40 WPA2_WPA3_PERSONAL. aasdk's `aaw/WifiInfoResponse` wrongly reuses the in-session enum (WPA2_PERSONAL = 5); openauto compensates by sending its "WPA2_ENTERPRISE" (= 8). `access_point_type` 0 STATIC, 1 DYNAMIC |
| 4 | WifiVersionRequest `{major, minor, supported_wifi_channel_type, supported_wifi_channels, car_info, wpp_info{ip,port}}` | HU→MD | optional; real HUs send it, dongles often don't. open-headunit announces 4.2: below 4.1 the phone ignores `wpp_info`, at exactly 4.1 an allow-list of makes applies |
| 5 | WifiVersionResponse `{major, minor, device_serial, status, selected_wifi_channel_type, device_info, …}` | MD→HU | phones have answered status -8 (NO_SUPPORTED_WIFI_CHANNELS) and still completed |
| 6 | WifiConnectionStatus `{status, error_message}` | MD→HU | 0 = joined the AP |
| 7 | WifiStartResponse `{ip_address, port, status}` | MD→HU, or HU→MD | acknowledges type 1; HU→MD it answers the MD's type 1 with the HU's ip/port |
| 8 / 9 | WifiPingRequest / Response `{timestamp}` | either | keepalive; echo payload |
| 10 | WifiConnectionRejection `{reason: 1 MOBILE_DEVICE_ID_NOT_FOUND, 2 INVALID_SETUP_TOKEN}` | HU→MD over TCP only | |
| 11 | WifiSetupInfo | | seen in captures, refused except over RFCOMM |

`aaw/Status`: 0 SUCCESS, 1 UNSOLICITED, -1 NO_COMPATIBLE_VERSION, -2 WIFI_INACCESSIBLE_CHANNEL,
-3 WIFI_INCORRECT_CREDENTIALS, -4 PROJECTION_ALREADY_STARTED, -5 WIFI_DISABLED, -6 WIFI_NOT_YET_STARTED,
-7 INVALID_HOST, -8 NO_SUPPORTED_WIFI_CHANNELS, -9 INSTRUCT_USER_TO_CHECK_THE_PHONE, -10 PHONE_WIFI_DISABLED,
-11 WIFI_NETWORK_UNAVAILABLE.

---

## 5. Session handshake (HU point of view; mirror it for the MD)

### 5.1 Sequence

1. **Transport up.** USB: HU performs the AOA switch and claims the accessory interface. TCP: HU connects to the
   phone (dev mode 5277) or the phone connects to the HU (wireless). Either way **the HU speaks first.**
2. **HU → VERSION_REQUEST** (ch 0, flags 0x03): `00 01 | 00 01 | 00 06` = protocol 1.6 (aasdk). Newer HUs request
   1.7 (LIVI) or GAL 4.3/5.0/5.1/6.0 (OpenAutoLink; Gearhead answered 6.1 to a 6.0 request). HUIG: the HU must not
   send another version request or pings while waiting; reset USB if nothing arrives within ~15 s.
3. **MD → VERSION_RESPONSE** `00 02 | major | minor | status`. Phones answer the highest compatible version
   (1.7 for a 1.6 request, observed by OpenAutoLink); aasdk only checks `status != -1` (it even reads major/minor
   without byte-swapping). AACS/behead answer 1.5 and work with openauto. Note: the GAL version changes later
   behaviour (OpenAutoLink: ≥ 5.0 audio is ack-less, ≥ 4.3 uses rich UiConfig and active media session ids, 6.0
   enables 2-second HEVC GOPs).
4. **TLS 1.2 handshake inside ENCAPSULATED_SSL (id 3), plain frames on ch 0.** HU = client: ClientHello →
   MD (server): ServerHello, Certificate (phone cert), [ServerKeyExchange], CertificateRequest, ServerHelloDone →
   HU: Certificate (HU cert), ClientKeyExchange, CertificateVerify, ChangeCipherSpec, Finished →
   MD: ChangeCipherSpec, Finished. TLS 1.3 must stay disabled. Each handshake flight is one id-3 message.
5. **HU → AUTH_COMPLETE** (id 4, `AuthResponse{status = 0}`, aasdk sends it plaintext). HUIG: no encrypted packets
   from the HU before authentication completes.
6. **MD → SERVICE_DISCOVERY_REQUEST** (id 5, encrypted): device name, label, icons (small/medium/large PNG bytes),
   `PhoneInfo`.
7. **HU → SERVICE_DISCOVERY_RESPONSE** (id 6, encrypted, possibly > 16 KiB → fragmented). See §5.3.
8. **MD → CHANNEL_OPEN_REQUEST** on each service channel it wants (flags 0x0F, `{priority, service_id}`);
   **HU → CHANNEL_OPEN_RESPONSE** `{status 0}` on the same channel. OpenAutoLink: if a request is not answered the
   phone disconnects after ~60 s.
9. Per-service setup (MD side driving):
   * **Video**: MD → SETUP `{type = H264_BP (3) / H265 (7) / VP9 (5)}`; HU → CONFIG `{READY, max_unacked,
     configuration_indices=[i]}`; openauto then immediately sends VIDEO_FOCUS_NOTIFICATION(PROJECTED, unsolicited
     false); per HUIG the MD may send VIDEO_FOCUS_REQUEST(PROJECTED) and the HU answers with a notification (the HU
     must not grant PROJECTED before the sink is set up). MD → START `{session_id, configuration_index}`, then
     CODEC_CONFIG (SPS/PPS, no timestamp) and DATA frames (8-byte µs timestamp + access unit). HU → ACK per message.
     Never exceed `max_unacked` outstanding frames (openauto: 1; OpenAutoLink: 30).
   * **Audio sinks** (media/guidance/system): MD → SETUP `{PCM}`; HU → CONFIG; MD sends AUDIO_FOCUS_REQUEST on ch 0
     and waits (HUIG: ≤ 500 ms) for AUDIO_FOCUS_NOTIFICATION; MD → START, DATA (timestamped PCM, ~40 ms packets in
     behead; Gearhead pre-5.0 needs ACKs, 5.0+ is ack-less per OpenAutoLink), STOP when done.
   * **Microphone**: MD → SETUP `{PCM}`; HU → CONFIG; MD → MICROPHONE_REQUEST{open=true}; HU → MICROPHONE_RESPONSE
     {status, session_id}; HU → DATA (timestamped PCM 16 kHz mono); MD → ACK each; MICROPHONE_REQUEST{open=false}
     to close. Phones open the mic only while listening.
   * **Input**: MD → KEY_BINDING_REQUEST{keycodes it wants}; HU → KEY_BINDING_RESPONSE{0 or -18 KEYCODE_NOT_BOUND};
     then HU → INPUT_REPORT stream.
   * **Sensors**: MD → SENSOR_REQUEST per advertised sensor; HU → SENSOR_RESPONSE{0} then SENSOR_BATCH data
     (openauto pushes DRIVING_STATUS = UNRESTRICTED and NIGHT_MODE right after the response, then GPS from gpsd).
   * **Bluetooth**: MD → PAIRING_REQUEST{phone MAC, method}; HU → PAIRING_RESPONSE{status, already_paired} (≤ 1 s;
     `STATUS_BLUETOOTH_PAIRING_DELAYED` if busy); HU may send AUTHENTICATION_DATA (PIN); MD → AUTHENTICATION_RESULT.
     A HU can tell the phone to skip BT by announcing `car_address = "SKIP_THIS_BLUETOOTH"`.
   * **Navigation focus**: MD → NAV_FOCUS_REQUEST(PROJECTED); HU → NAV_FOCUS_NOTIFICATION.
   * **Wi-Fi projection** (USB sessions on wireless-capable HUs): MD → WIFI_CREDENTIALS_REQUEST; HU → RESPONSE with
     SSID/password, so the phone can later connect wirelessly.
10. **Steady state**: pings in both directions (HU ping cadence/timeouts are announced in
    `ConnectionConfiguration.ping_configuration`), focus changes (HU may send unsolicited AUDIO_FOCUS LOSS or
    VIDEO_FOCUS NATIVE when the driver switches to native screens), BATTERY_STATUS, VOICE_SESSION notifications.
11. **Teardown**: BYEBYE_REQUEST(reason) → BYEBYE_RESPONSE, either side; HUIG: HU must listen for MD ByeBye.

### 5.2 TLS details and certificates

* Roles: **HU = TLS client, MD = TLS server, mutual authentication** (HUIG: "TLS 1.2 with Client Authentication";
  aasdk `TLS_client_method` + `SSL_set_connect_state`; AACS/behead/aa-proxy-rs/opengal use server/accept state on
  the phone side).
* Keys: RSA 2048, X.509. Both certificates are issued by the root `C=US, ST=California, L=Mountain View,
  O=Google Automotive Link` (valid 2014–2044). Known leaf certs are **X.509 v1** (no extensions), which strict
  parsers (webpki/rustls) refuse — behead and aa-proxy-rs had to bypass certificate parsing.
* HU certificates in public repos: `O=JVC Kenwood, OU=01` (aasdk/openauto/LIVI/OpenAutoLink, to 2045),
  `O=Android-Auto-Internal, OU=01` (AACS AAClient, vehiculum client, to 2048). Phones accept these today.
* Phone certificates: `O=CarService, OU=53` (AACS AAServer, vehiculum server; expired), Gearhead's current
  `carservice.crt` (expires 2026-12-23 per behead; key stored AES-encrypted in the app code).
* **HU verification of the phone cert**: HUIG — receiver library verifies, and "SSL authentication might fail with
  'Certificate not yet valid' or 'Certificate expired' if the internal clock on the HU is incorrect … the HU MUST
  obtain the correct time." Google recommends one HU client certificate per make/model/year. Observed: LG/Seat HU
  verifies dates (Tomasz moved the HU clock back to keep an expired AACS cert working); openauto
  (`SSL_set_verify(SSL_VERIFY_NONE)`), headunit-revived (`NoCheckTrustManager`), LIVI (accept-any), aa-proxy-rs and
  a 2021 Mercedes Sprinter MBUX accept anything. Expect most OEM HUs (built on Google's receiver library) to verify.
* Cipher suites: modern HUs/phones negotiate `ECDHE-RSA-AES128-GCM-SHA256` (also AES256-GCM seen); some legacy HUs
  offer only `TLS_RSA_WITH_AES_128_CBC_SHA` (aa-proxy-rs needed OpenSSL for them) — but note aasdk's 29-byte
  overhead assumption only works with GCM. Session resumption: open-headunit deliberately resumes sessions; some HUs
  send tickets/IDs from earlier sessions — a phone-side server must either resume correctly or ignore them cleanly.

### 5.3 What the HU sends in ServiceDiscoveryResponse (typical values)

Top level (`ServiceDiscoveryResponse`): `repeated Service channels = 1`, deprecated make/model/year/vehicle_id/
head_unit_* (2–10, still sent by older HUs), `driver_position = 6` (0 LEFT, 1 RIGHT, 2 CENTER, 3 UNKNOWN),
`can_play_native_media_during_vr = 11`, `session_configuration = 13` (bit flags, e.g. hide clock/battery),
`display_name = 14`, `probe_for_support = 15`, `connection_configuration = 16`
(`ping_configuration{timeout_ms, interval_ms, high_latency_threshold_ms, tracked_ping_count}`,
`wireless_tcp_configuration{socket_receive_buffer_size_kb, socket_send_buffer_size_kb, socket_read_timeout_ms}`),
`headunit_info = 17` (make, model, year, vehicle_id, head_unit_make, head_unit_model, sw build, sw version).

`Service{id, one of: sensor_source_service 2, media_sink_service 3, input_source_service 4, media_source_service 5,
bluetooth_service 6, radio_service 7, navigation_status_service 8, media_playback_service 9, phone_status_service 10,
media_browser_service 11, vendor_extension_service 12, generic_notification_service 13, wifi_projection_service 14}`.

Typical values (openauto defaults unless noted):

| service | content |
|---------|---------|
| control/top | `driver_position`, `display_name "Crankshaft-NG"`, `probe_for_support=false`, ping {tracked 5, timeout 3000 ms, interval 1000 ms, high-latency 200 ms}; LIVI {interval 1500, timeout 5000}; open-headunit raises timeout to 15000 ms and sets TCP buffers to 64 KiB ("AA documents 16384 default, 65536 ceiling"); `headunit_info{make "Crankshaft", model "Universal", year "2018", …}`. Make "Google" + model "Desktop Head Unit" unlocks developer behaviour in Gearhead (aa-proxy-rs trick) |
| sensor (1) | `sensors: [DRIVING_STATUS_DATA, LOCATION, NIGHT_MODE]` (minimum useful set); LIVI advertises ~20 incl. SPEED, GEAR, PARKING_BRAKE, FUEL, ODOMETER, vehicle energy model; optional `location_characterization`, `supported_fuel_types`, `supported_ev_connector_types` |
| video (3) | `MediaSinkService{available_type=VIDEO_H264_BP, available_while_in_call=true, video_configs:[{codec_resolution, frame_rate, width_margin, height_margin, density, [decoder_additional_depth, viewing_distance, pixel_aspect_ratio_e4, real_density, video_codec_type, ui_config{margins, content_insets, stable_content_insets, ui_theme}]}], [display_id, display_type, initial_content_keycode]}`. Resolution enum: 1 800x480, 2 1280x720, 3 1920x1080, 4 2560x1440, 5 3840x2160, 6 720x1280, 7 1080x1920, 8 1440x2560, 9 2160x3840. FPS enum: **1 = 60, 2 = 30** (old aasdk/AACS had them swapped). openauto default 800x480@30, density 140, margins 0; LIVI dpi 140; HUIG: density from Google's screen-size calculator (historically 160/240), min 748 dp × 450 dp. Multiple configs may be listed; the phone answers with the chosen index in START |
| media audio (4) | `available_type=AUDIO_PCM, audio_type=MEDIA(3), audio_configs:[{48000 Hz, 16 bit, 2 ch}]` |
| guidance (5) | `audio_type=GUIDANCE(1)`, 16000/16/1 |
| system (6) | `audio_type=SYSTEM_AUDIO(2)`, 16000/16/1 (optional per HUIG) |
| telephony (7) | `audio_type=TELEPHONY(4)`, 16000/16/1 — **do not rely on it**: current Gearhead crashes when it is advertised; call audio goes over BT HFP |
| input (8) | `keycodes_supported` (e.g. 3 HOME, 4 BACK, 5 CALL, 6 ENDCALL, 19–23 DPAD, 84 SEARCH, 85–88/126/127 media, 231 VOICE_ASSIST, 65536 ROTARY, 65538 NAVIGATION), `touchscreen:[{width, height (=video resolution in openauto), type CAPACITIVE/RESISTIVE/INFRARED, is_secondary}]`, optional `touchpad`, `feedback_events_supported`, `display_id` |
| microphone (9) | `MediaSourceService{available_type=AUDIO_PCM, audio_config{16000, 16, 1}, available_while_in_call}` |
| bluetooth (10) | `car_address` (HU BT MAC, or "" / "SKIP_THIS_BLUETOOTH"), `supported_pairing_methods [PIN(4), NUMERIC_COMPARISON(2)]` (−1 UNAVAILABLE) |
| navigation status (12) | `{minimum_interval_ms, type IMAGE(1)/ENUM(2), image_options{height, width, colour_depth_bits}}` |
| media playback (13), phone status (14) | empty messages (presence = supported) |
| wifi projection (18) | `car_wifi_bssid` |

HUIG audio expectations: guidance/voice/UI 16 kHz 16-bit mono, media 48 kHz 16-bit stereo, mic ≥ 16 kHz mono;
PCM recommended, AAC-LC allowed (raw frames, 1024 samples @16 kHz / 2048 @48 kHz). Video: H.264 BP, I and P frames
only, level 3.1 (480p) / 3.2 (720p) / 4.2 (1080p); max bitrates 4 / 6 / 8 Mbit/s; 30 or 60 fps max, ≥ 5 fps.

---

## 6. Wireless flow

1. **Bluetooth prerequisites**: the phone must be bonded to the HU and typically HFP-connected. The HU publishes an
   SDP record with service class **`4de17a00-52cb-11e6-bdf4-0800200c9a66`**, protocol list L2CAP + RFCOMM
   (channel 8 is customary: dongle, LIVI; openauto lets Qt pick), PublicBrowseRoot, name e.g. "Android Auto
   Wireless" / "AA Wireless". Dongles also advertise the UUID over BLE and connect HSP-HS → phone HSP-AG (UUIDs
   0x1108/0x1112) to keep the ACL link; open-headunit "pokes" the phone by connecting to its HFP-AG (0x111F) or
   HSP-AG to make it notice the HU. Re-pairing is often needed after the HU starts advertising the UUID (phones
   cache SDP).
2. **The HU is the RFCOMM server; the phone connects as RFCOMM client** to the AA UUID (aa-proxy-rs, acting as a
   phone toward a wireless HU, does an SDP search for the UUID and connects to the returned channel).
3. RFCOMM exchange (HU view): [HU→4 WifiVersionRequest] → [MD→5 WifiVersionResponse] → HU→1 WifiStartRequest
   {ip, port} → MD→2 WifiInfoRequest → HU→3 WifiInfoResponse {ssid, key, bssid, security_mode=8, ap type} →
   MD→7 WifiStartResponse → MD→6 WifiConnectionStatus{0}. Pings (8/9) may arrive anytime. A phone already on the
   network skips 2/3. openauto sends 4 and 1 back to back right after accept; the dongle sends only 1 and expects 2.

   Phone-initiated variant (Android Auto 18.0): when the HU sends 4 but no 1, the phone sends an **empty** type 1
   itself 5 s after a compatible type 5, but only if it already intends to start projection (set by a type 1 from
   the HU, by the user starting wireless projection, or by a server flag that is off by default; on a plain
   Bluetooth auto-connect it sends nothing). The HU answers with type 7 {ip, port, status}. On SUCCESS the exchange
   continues as above with that ip/port, but the phone sends no type 7 of its own (type 6 still follows the join).
   WIFI_NOT_YET_STARTED (-6) and PROJECTION_ALREADY_STARTED (-4) make the phone ask again every 2 s, until 50 s after
   its first request; WIFI_DISABLED (-5) stops. Head units built on Google's receiver library may wait for this from a
   phone the user has not yet allowed on the head unit (the decompiled head-unit app answers PROJECTION_ALREADY_STARTED until then; not observed). That
   receiver also needs `phone_info.instance_id` (type 5 field 6 → 1) before it handles any phone message except
   pings and version responses, and closes RFCOMM when 10 s pass without a phone type 8 (the window opens at its
   type 4 and restarts with each ping; Android Auto pings every 1 s). On a Jancar HU built on this receiver (WPP 4.2),
   tested with AARE, phone_info and the pings were enough: the HU sent its own type 1 about 1 s after the version
   exchange. AARE does not send this request; a head unit that only starts on a phone request would still need it.
4. **The HU (or dongle) hosts the Wi-Fi AP** (WPA2-PSK; 5 GHz ch 149/80 MHz preferred by OpenAutoLink;
   `supported_wifi_channel_type` in type 4 and the -8 status cover band mismatches). Alternatives: Wi-Fi Direct with
   the HU as group owner (open-headunit native mode); legacy third-party "phone hotspot" flows needed the Gearhead
   startup intent that AA 17.4 removed.
5. **The HU is the TCP server; the phone is the TCP client** connecting to the ip:port from type 1. Ports in the
   wild: 5288 (WirelessAndroidAutoDongle, aa-proxy-rs), 5000 (openauto), 5277 (LIVI, OpenAutoLink bridge, behead
   default), 30515 (`GalConstants.WIFI_PORT` in Google's descriptors). It is negotiated, never assume.
6. Over that TCP socket the normal AAP session runs exactly as over USB: **HU still sends VERSION_REQUEST first and is
   still the TLS client.**
7. WPP-over-TCP (Gearhead ≥ ~17.5, WPP version ≥ 4.1/4.2): the HU's type-4 `wpp_info{ip, port}` is stored by the phone
   against the HU's BT address; later the phone dials that TCP endpoint and runs the WPP exchange over TCP instead of
   RFCOMM (HU can reject with type 10). Also a QR-code deep link `https://androidauto.com/projection/...?data=`
   carrying `ProjectionDeepLinkData{ssid, bssid, passkey, wpp_tcp_ip, wpp_tcp_port, bluetooth_mac, security_mode}`.
8. **Developer "head unit server" (port 5277)**: Gearhead → Developer settings → "Start head unit server" makes the
   **phone listen on TCP 5277**; Google's DHU connects via `adb forward tcp:5277 tcp:5277` (or `--usb` for AOA),
   openauto's "connect" dialog and headunit-revived's "Headunit Server" connect to `<phone-ip>:5277`. Here the phone
   is TCP server but the HU still opens with VERSION_REQUEST and is TLS client. This is the simplest transport for
   our app to offer first (works with DHU, openauto, headunit-revived, LIVI, aa-proxy-rs DHU mode).

---

## 7. USB (AOA)

* The HU is USB host and AOA accessory; the phone is the USB device. HUIG: the HU must attempt AOA on any attached
  device regardless of VID/PID and support AOA 1.0 and 2.0.
* Switch sequence (vendor control requests on ep0): **51 GET_PROTOCOL** (IN, device returns u16 LE version, 1 or 2;
  aasdk accepts only 1 or 2) → **52 SEND_STRING** (OUT, index 0 manufacturer, 1 model, 2 description, 3 version,
  4 URI, 5 serial; NUL-terminated, ≤ 256 bytes) → optional AOA2 requests (54–57 HID, 58 SET_AUDIO_MODE) → **53
  START**. The device re-enumerates as `18D1:2D00` (accessory) or `18D1:2D01` (accessory + ADB); AOA2 audio PIDs
  2D02–2D05. Accessory interface: class/subclass 0xFF/0xFF, protocol 0, two bulk endpoints (512 B HS, 64 B FS,
  1024 B SS), interface string "Android Accessory Interface"; the HU uses interface 0 and picks IN/OUT by direction.
* Strings sent by HUs:
  * HUIG (official): manufacturer **"Android"**, model **"Android Auto"**, description "Android Auto", version
    "1.0", URI empty, serial empty.
  * aasdk/openauto: "Android", "Android Auto", "Android Auto", "2.0.1", "https://f1xstudio.com", "HU-AAAAAA001".
  * milek7 `apserver.c`: "Android", "Android Auto", "Android Auto", "1.0", "", "".
  The phone keys on manufacturer + model.
* On an Android phone the kernel `f_accessory` driver answers 51/52/53 and re-enumerates; apps see
  `UsbManager.ACTION_USB_ACCESSORY_ATTACHED` filtered by an `accessory-filter` (`manufacturer="Android"
  model="Android Auto"`) and open it with `UsbManager.openAccessory()` (a ParcelFileDescriptor; reads should use
  16 KiB buffers). Gearhead registers the same filter, so the user/system chooses the handler; when not in accessory
  mode, the phone exposes its normal MTP/charging identity.
* Linux-gadget emulation of a phone (for test rigs): WirelessAndroidAutoDongle (configfs `accessory` function with a
  backported f_accessory), AACS and behead (FunctionFS; behead answers 51 = 2, switches only on 53, ZLP on exact
  multiples, treats a disable+re-enable within 2 s as a bus reset, not an unplug). GM AAOS HUs reportedly need a
  mass-storage function in the composite gadget (OpenAutoLink).

---

## 8. Practical reliability lessons (from code, comments and issues)

**Protocol / framing**
* Reassemble frames across arbitrary read boundaries (AACS broke when a frame was split across bulk transfers).
* Accept both encrypted and plaintext frames after the handshake (aasdk sends pings and AuthComplete plaintext).
* Produce exactly one AES-GCM TLS record per AAP frame, ≤ 16 KiB plaintext (aasdk's 29-byte overhead assumption).
* Write every proto2 `required` field even when zero; HUs parse with required-field checks (behead rule).
* Treat every enum received from the HU as open/int: new resolutions (4–9), codecs (5–7), sensor types, statuses
  (phones answered `-8` where a `sint32` declaration produced garbage — check signed vs zig-zag types).
* `ChannelOpenRequest.priority` is `sint32` (zig-zag) in Google's schema; old projects used int32.
* Old aasdk/AACS FPS enum was inverted; Google's is 60 = 1, 30 = 2.
* Never advertise or expect TELEPHONY audio (Gearhead 16+ crashes, stream-type mapper only knows 1/3/5).
* aasdk `MessageInStream` errors on MIDDLE/LAST frames without FIRST; do not interleave frames of different messages.
* Unknown control messages must be logged and ignored, not fatal (AACS exited on any unknown id).

**Flow control / media**
* Respect `Config.max_unacked`; HUs ack each message (openauto acks every frame with `ack = 1`, window 1). Without
  acks the phone's flow control stalls. Keep the ack window per channel and session id.
* Send SPS/PPS as CODEC_CONFIG (id 1) first; put parameter sets before every IDR so a HU can join mid-stream;
  HU decoders drop until the first IDR and need a fresh IDR after focus regain / surface recreation.
* Do not use a NATIVE_TRANSIENT→PROJECTED focus bounce as a keyframe request (OpenAutoLink regression).
* Gate media on START and on video focus PROJECTED; stop on NATIVE. The HU may take focus back unprompted.
* HUIG latencies: video/audio setup ≤ 500 ms; MD waits ≤ 500 ms for an audio-focus answer before playing; HU should
  buffer two audio frames before starting playback. `width_margin`/`height_margin` are what actually adapt the
  phone UI to unusual aspect ratios; `pixel_aspect_ratio_e4` is ignored by the phone.
* Gearhead's HEVC GOP is ~60 s at GAL < 6.0 (≈3600 frames at 60 fps) and 2 s at GAL 6.0.

**Keepalive / timeouts**
* Default ping: interval 1000 ms, timeout 3000 ms, 5 tracked pings; Wi-Fi HUs whose radio scans (6–22 s off
  channel observed) need ≥ 15 s timeouts. openauto never answers phone pings; OpenAutoLink notes "bridge pings to
  phone may be ignored". The phone side should answer every HU ping immediately and be tolerant when its own pings
  go unanswered.
* HU-side timeouts seen: TLS handshake 10–15 s (open-headunit), version response ≤ 15 s before USB reset (HUIG),
  BT pairing response 1 s (HUIG), WPP: phone's ~12 s first-message budget, HU waits 3 s for type 5 and 15 s for
  type 2 (extended 15 s per "still joining"), reconnect grace 8 s USB / 20 s wireless.
* Wireless: strict HUs must not be told the bootstrap succeeded before the TCP leg is really up (aa-proxy-rs);
  some HUs need WPP keepalive pings while the AP/DHCP comes up; BlueZ SAP plugin steals RFCOMM channel 8.

**TLS / certificates**
* HU certificates are X.509 v1; do not parse them with strict libraries (or skip verification).
* Some HUs only offer RSA-kx CBC suites; some try session resumption.
* HU clocks are often wrong (RTC at 2045 on some Mazdas, reset on battery disconnect) → certificate date errors on
  the HU side; nothing the phone can fix except presenting a cert valid at the HU's notion of "now".
* behead's ordered certificate fallback (next connection gets the next cert after an unfinished handshake) is a
  pragmatic way to support both lenient HUs (self-signed) and strict HUs (Google-signed, if the user supplies one).

**Android-specific constraints (OpenAutoLink teardown of Gearhead 17.4)**
* Gearhead is a privileged multi-app host; official Google Maps' projected presentation accepts only Google-signed
  callers or holders of `CAPTURE_VIDEO_OUTPUT`. A normally signed phone app must render its own UI / virtual
  display, use MediaProjection (with user consent) for mirroring, and cannot capture other apps' audio without
  their consent/`AudioPlaybackCaptureConfiguration` rules, nor inject input without accessibility or root.
* AA 17.4 disabled the exported Gearhead startup receiver used by third-party wireless launchers ("Wireless Helper",
  "self mode"); only factory WPP, dongles and the developer head-unit server still start sessions. Irrelevant for a
  replacement phone app, but it means open-source HUs increasingly implement factory WPP — good for us.

---

## 9. Answers to the specific questions

* **Open-source phone-side implementation?** Yes: behead (Rust, GPL-3.0-only, current) and AACS AAServer (C++,
  GPL-3.0-only, 2022); MD-side halves inside aa-proxy-rs (GPL-2.0) and opengal_proxy (GPL-3.0-only). None runs as an
  Android app; none ships a valid Google-signed phone certificate.
* **TLS roles**: HU client, MD server, mutual auth, TLS 1.2, carried in control message 3.
* **Does the HU verify the phone certificate?** Google's guide requires it (chain to "Google Automotive Link" +
  validity dates); field reports confirm OEM HUs that do (LG/Seat; clock-related failures). Open-source HUs and at
  least one OEM HU (2021 Sprinter) do not.
* **Certificates for aa-proxy-rs MITM**: phone-facing leg uses `hu_cert/hu_key` (a HU cert the phone accepts,
  e.g. the JVC Kenwood one); car-facing leg uses `md_cert/md_key` (a phone cert the car accepts); `galroot_cert` is
  the Google root.

---

## 10. Source pointers (for later deep dives)

* aasdk framing: `src/Messenger/{FrameHeader,FrameSize,MessageInStream,MessageOutStream,Cryptor}.cpp`,
  `include/aasdk/Messenger/*.hpp`; TLS: `src/Transport/SSLWrapper.cpp`; AOA: `src/USB/AccessoryModeQueryFactory.cpp`.
* openauto: `src/autoapp/Service/AndroidAutoEntity.cpp` (handshake, SDR top level), `Service/*/…Service.cpp`
  (`fillFeatures`), `btservice/AndroidBluetooth{Service,Server}.cpp` (SDP + RFCOMM), `autoapp/UI/ConnectDialog.cpp`
  (5277 client).
* WirelessAndroidAutoDongle: `aa_wireless_dongle/package/aawg/src/{bluetoothHandler,bluetoothProfiles,proxyHandler,
  common,usb}.cpp`, `board/common/rootfs_overlay/etc/init.d/S92usb_gadget`.
* aa-proxy-rs: `src/mitm.rs` (MITM handshake order ~line 4130), `src/ssl_{openssl,rustls}.rs`, `src/bluetooth.rs`.
* open-headunit: `app/src/main/proto/wireless.proto`, `connection/wifi/modes/nativeaa/{WppHandshakeSession,
  WppMessages,NativeAaHandshakeManager,BluetoothWakePolicy}.kt`, `aap/{AapSslContext,ConnectionConfigPolicy}.kt`.
* LIVI: `src/main/services/projection/driver/aa/stack/**`, `native/livi-helperd/crates/{livi-aa,livi-runtime}`.
* OpenAutoLink: `docs/embedded-knowledge.md`, `docs/experimental-gal6.md`, `docs/aa-undocumented-features.md`,
  `docs/research/aa-replacement-*.md`.
* behead: `DESIGN.md`, `docs/protocol-survey.md`, `crates/aap/src/{frame,session,proto}.rs`,
  `crates/server/src/{gadget,tls}.rs`.
* AACS: `AAServer/src/{AaCommunicator,ModeSwitcher,descriptors}.cpp`.
* galdocs: https://milek7.pl/.stuff/galdocs/ (HUIG 1.3 mirror, DHU proto dump, dissector).

---

## 11. Licenses (what we can reuse)

| project | license | implication for an Apache/MIT/proprietary app | implication for a GPLv3 app |
|---------|---------|-----------------------------------------------|-----------------------------|
| WirelessAndroidAutoDongle | MIT | code reusable with attribution | fine |
| aasdk | GPL-3.0-or-later | read-only reference; no code copying | code reusable |
| openauto | GPL-3.0 | reference only | reusable |
| OpenAutoLink | GPL-3.0-or-later | reference only | reusable |
| LIVI | GPL-3.0 | reference only | reusable |
| behead | GPL-3.0-only | reference only | reusable (GPLv3-only; compatible with a GPLv3 app) |
| AACS | GPL-3.0-only | reference only | reusable |
| opengal_proxy | GPL-3.0-only | reference only | reusable |
| aa-proxy-rs | GPL-2.0 (not "or later") | reference only | **not** combinable with GPLv3-only code without relicensing; reference only |
| open-headunit, mikereidis/borconi headunit | AGPL-3.0 | reference only | AGPL obligations if copied |
| vehiculum | no license file (tool says MIT) | all rights reserved by default | same |
| Protobuf schemas (aasdk `aap_protobuf`, aa-proxy-rs `protos.proto`, galdocs) | derived from descriptors in Google's DHU binary; no license from Google | wire-format facts (field numbers/types) are needed for interoperability; safest is to re-author our own `.proto` files from the documented field numbers rather than copy files verbatim | same caveat |
| HUIG 1.3 | Google Confidential (leaked) | read for facts, do not redistribute | same |
| Certificates/keys in repos (JVC Kenwood HU key, CarService MD key) and Gearhead-extracted keys | Google/OEM property, not licensed | do not ship; make certificates user-supplied | same |

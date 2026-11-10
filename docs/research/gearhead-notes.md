# Android Auto phone app ("gearhead") — protocol notes

Source: `com.google.android.projection.gearhead` **17.7.663653-release** (versionCode 177663653, minSdk 32, armeabi-v7a APKM).
Method: static analysis (jadx 1.5.6 decompile, protobuf-lite descriptor decoding, manifest/resources). No dynamic testing.
All class names are R8-obfuscated `defpackage.*` names valid **only for this exact build**. Message ids,
field numbers and constants are protocol facts. Everything else is written in our own words.

The protobuf descriptors were decoded with small throwaway scripts (every `GeneratedMessageLite`
"RawMessageInfo" string into field number, type and Java field: 1967 messages; and the proto enums).
They are not part of this repository.

> Important architectural fact: the whole AAP "car service" (the old GmsCore `com.google.android.gms.car`
> stack) now lives **inside gearhead** and runs in the `:car` / `:projection` processes. There are two copies of the
> protocol stack:
> - an older `san`-based one: `rze` control, `ryx` mux, `rzq`/`rzo`/`rzp` framer, `sbd` TLS;
> - a newer `jdy`-based one: `jcj` control, `jca` mux, `jcq`/`jdt` framer, `jep` TLS, `jee` service registry.
>
> Both stacks have identical wire behaviour. The newer one is what `jsh`/`jrv` (session start) instantiate.

---

## 1. TLS / authentication

### 1.1 Roles
- **The phone is the TLS *server*; the head unit (HU) is the TLS *client*.**
  - `rze`/`jcj` create the `SSLEngine` with `setUseClientMode(false)` and `setNeedClientAuth(true)`.
  - The key manager `sbb` only implements the server-alias methods; the client-alias methods throw "should not be requested for server side".
- **Mutual TLS:** the phone *requires* a client certificate from the HU.
- The protocol is `SSLContext.getInstance("TLSv1.2")` from the platform provider (Conscrypt), or optionally `GmsCore_OpenSSL` in the newer stack (`jep.d`).
  - No custom cipher-suite or protocol list is set.
  - An `UNSUPPORTED_PROTOCOL` failure is classified as "obsolete SSL" (`AUTH_FAILED_OBSOLETE_SSL`).
- TLS records travel inside AAP frames on **channel 0, message type 3** (`MESSAGE_ENCAPSULATED_SSL`). The frames are unencrypted. See §2.
  - The HU starts the handshake by sending the ClientHello in a type-3 message.
  - The phone feeds each type-3 payload to `SSLEngine.unwrap` and answers with its own type-3 message containing whatever `wrap` produced.
  - When the engine reaches FINISHED, the phone logs "Phone finished SSL handshaking" and **waits for the HU's AUTH_COMPLETE (type 4)**.

### 1.2 What certificate/key the phone presents
- A **static certificate and RSA-2048 private key embedded in the APK**. Every Android Auto install presents the same one.
  - It is not generated per device.
  - It is not attested.
  - It is not fetched from Play Services or a Google server, apart from the optional flag override below.
- The certificate chain sent is `[CarService leaf, Google Automotive Link root]` (`sbb.getCertificateChain`, alias `"com.google.android.gms.car"`, key type RSA).

Embedded certificates (public data):

| file | subject | issuer | validity | serial | key / sig | notes |
|---|---|---|---|---|---|---|
| `gal_root_ca.pem` / `.der` | C=US, ST=California, L=Mountain View, **O=Google Automotive Link** | self-signed | 2014-06-06 18:28:19Z → **2044-06-05** | `C14EE7A5A4544D42` | RSA-2048 / sha1WithRSA, `CA:TRUE` | SHA-256 fp `49:E5:2E:FC:…:D1:7F` |
| `carservice_phone.pem` / `.der` | C=US, ST=California, L=Mountain View, **O=CarService** | O=Google Automotive Link (verifies against the root above) | 2014-07-04 00:00:00Z → **2026-12-31 05:01:18Z** | `75F4A9DD0BD9AC` | RSA-2048 / sha256WithRSA, no extensions | SHA-256 fp `58:A0:3F:C6:…:C3:18` |

**Surprise: the built-in phone certificate expires on 2026-12-31**, about 3 months after this build.
- HUs report expiry explicitly: the status enum has `STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED = -24` and `…CERT_NOT_YET_VALID = -23`, and gearhead maps them to `AUTH_FAILED_BY_CAR_CERT_EXPIRED`. This strongly suggests **head units validate the phone certificate, including its dates**.
- Google can replace the cert/key remotely through Phenotype flags in the config package `com.google.android.gms.car` (class `adib`):

| flag | contents |
|---|---|
| `SenderlibCertFeature__backup_key_raw` | base64 of the certificate PEM |
| `SenderlibCertFeature__backup_table` | base64 of the salt/key table |
| `SenderlibCertFeature__p_table` | base64 of the encrypted private key |
| each `…_verify` companion | base64 SHA-1 of the item (a plain integrity check, not a signature) |

  If all three items are present and match their hashes, they replace the built-in ones (`sbd` constructor / `jep.a()` choose `sbc`/`jeo` instead of `sba`/`jen`). The defaults are empty. Expect newer APK versions, or a flag push, to carry a renewed CarService certificate.

### 1.3 Where the private key lives (not extracted)
- The certificate PEM string is in `sba.a()` / `jen.a()` (dex `classes.dex`).
- The private key is **not stored in clear**. It is:
  - `sbd.c` / `jep.c`: a 1712-byte `byte[]` holding AES-256-CBC (PKCS5 padding) ciphertext of a PEM `-----BEGIN PRIVATE KEY-----` (PKCS#8, RSA) text;
  - `sbd.b` / `jep.b`: a 256-byte `byte[]` "table" (salt).
- Key/IV derivation (`rze` constructor, `jep.d`, helper `uga.bl`):
  1. Start with a 48-byte zero buffer.
  2. Run a byte-mixing routine over every byte of the phone-cert PEM string, keyed by the 256-byte table.
  3. Run it again over every byte of the root-CA PEM string.
  4. Run it 7 more times over the buffer itself.
  5. Bytes 0–31 of the buffer are the AES key and bytes 32–47 are the IV.
- `uga.I` strips the 28-byte PEM header and 26-byte footer, base64-decodes the rest and loads it with `KeyFactory("RSA")` + `PKCS8EncodedKeySpec`.
- A user can therefore recover the key at runtime from their own copy of the APK: run that derivation on the two PEM strings and the two arrays, or hook `sbb.getPrivateKey` / `KeyManager.getPrivateKey("com.google.android.gms.car")`.
- I did **not** decrypt or verify it; an in-memory check was declined by the sandbox.

### 1.4 Verification of the head unit's certificate
- The trust store is a fresh in-memory `KeyStore` holding a **single** trust anchor: the Google Automotive Link root above (alias "GAL"). The `TrustManagerFactory` default (PKIX) trust manager is used, with no custom `checkClientTrusted`. So the HU's chain must validate to the GAL root, and the normal PKIX checks apply, including validity dates.
- Additional checks on the HU leaf after the handshake (`sbd.a` / `jep`):
  - If the subject contains `CarService`, the handshake is rejected ("Head Unit must not use CarService certificate"). The HU may not reuse the phone's certificate.
  - If the subject contains `Google Automotive Link`, it is rejected ("must send its own certificate, not the CA's").
- The peer subject, serial, notBefore and notAfter are logged and exported. They go into the session bundle as `peer_cert_name` / `peer_cert_serial_number` / … and the "SSL_HANDSHAKE" telemetry.
- Consequence for us: we are the phone, so we must present a cert the HU trusts. As far as is known, HUs check that it chains to the same GAL root, which means only Google's CarService cert/key works. In return, our phone side should accept HU certs chaining to the GAL root.

### 1.5 Auth complete
- After its side of the handshake finishes, the phone waits for **AUTH_COMPLETE (type 4)** *from the HU*. It is an `AuthResponse` (`xnh`) with `1: int32 status` (0 = success).
- On non-success the phone tears down: `-24` means cert expired and `-23` means not yet valid, and both get specific error codes.
- In the newer stack, when the flag `jej.n` ("strict") is set, receiving AUTH_COMPLETE before the local engine reached FINISHED is an error. In that mode, any message other than {1, 3, 4, 11, 12, 15, 16, 255, 65535} received before SSL completes kills the connection.
- After success, the phone switches the framer to encrypted mode and **sends ServiceDiscoveryRequest (type 5)**.

---

## 2. Framing and the control channel

### 2.1 Frame header (writer `rzp`/`jdt`, reader `rzo`/`jcq`)
```
byte 0      channel id (0..255; 0 = control)
byte 1      flags
            bit0 0x01 FIRST fragment
            bit1 0x02 LAST fragment          (unfragmented message = 0x03)
            bit2 0x04 CONTROL  – "channel-control" message on a service channel
                                  (ChannelOpenRequest/Response, ChannelClose); the receiver routes
                                  these to the channel state machine instead of the service endpoint.
                                  Normal channel-0 messages do NOT set it.
            bit3 0x08 ENCRYPTED
bytes 2-3   uint16 BE: length of this frame's payload (ciphertext length if encrypted)
bytes 4-7   uint32 BE: TOTAL plaintext message length — present only on a FIRST frame that is
            not also LAST (i.e. multi-fragment messages)
```
- **Fragment size** (`rzl`, "FramerConnectionSettings"):
  - The default `fragmentSize` is **16128** bytes, header included. The plaintext per fragment is `fragmentSize − 4`, or `− 8` on the first fragment.
  - The builder rejects values below 263.
  - The receive buffer for one encrypted frame is 16384 bytes.
  - Channel-control messages must fit in one fragment.
- **Encryption is per fragment.** Each fragment's plaintext slice is passed separately through `SSLEngine.wrap`, and the length field carries the resulting TLS record length. The receiver unwraps each frame independently and appends the result to the message buffer. The total-length field counts plaintext bytes.
- Per channel, only one message can be reassembling at a time ("Received duplicate first fragment").
- **Message payload:** `uint16 BE message type` followed by the protobuf, or raw bytes for SSL, media data and the version messages.
- **Framing error:** the phone sends a 2-byte message with type `0xFFFF` on channel 0 and closes. On receiving `0xFFFF` it terminates with FRAMING_ERROR. Type `0x00FF` (255) means "unexpected message".
- **Which messages are encrypted:**
  - Encrypted: everything after auth, including all channel-0 control messages from type 5 on.
  - Sent in clear: VERSION_RESPONSE, SSL handshake data, and a ByeByeRequest sent before auth.

### 2.2 Control-channel message ids
Names come from the in-app table `xab.M`. Direction is from the phone's point of view.

| id | name | dir | proto (class) / notes |
|---|---|---|---|
| 1 | VERSION_REQUEST | HU→ph | raw: `uint16 major, uint16 minor` |
| 2 | VERSION_RESPONSE | ph→HU | raw: `uint16 major, uint16 minor, int16 status` (+ optional proto, see below) |
| 3 | ENCAPSULATED_SSL | both | raw TLS records |
| 4 | AUTH_COMPLETE | HU→ph | `xnh {1: int32 status}` |
| 5 | SERVICE_DISCOVERY_REQUEST | ph→HU | `xub` (below) |
| 6 | SERVICE_DISCOVERY_RESPONSE | HU→ph | `xuc` (below) |
| 7 | CHANNEL_OPEN_REQUEST | ph→HU | `xon {1: sint32 priority, 2: int32 service_id}`; sent **on the target channel** with flags CONTROL\|ENCRYPTED; channel id == service id |
| 8 | CHANNEL_OPEN_RESPONSE | HU→ph | `xoo {1: enum status}`; must come back on that channel with the CONTROL bit |
| 9 | CHANNEL_CLOSE_NOTIFICATION | both | empty `xom` (CONTROL bit) |
| 11 | PING_REQUEST | both | `xsd {1: int64 timestamp, 2: bool bug_report, 3: bytes data}` |
| 12 | PING_RESPONSE | both | `xse {1: int64 timestamp, 2: bytes data}`; echoes the timestamp |
| 13 | NAV_FOCUS_REQUEST | ph→HU | `xqu {1: enum NavFocusType}` (1 NATIVE, 2 PROJECTED) |
| 14 | NAV_FOCUS_NOTIFICATION | HU→ph | `xqt {1: enum NavFocusType}` |
| 15 | BYEBYE_REQUEST | both | `xnt {1: enum reason}`: 1 USER_SELECTION, 2 DEVICE_SWITCH, 3 NOT_SUPPORTED, 4 NOT_CURRENTLY_SUPPORTED, 5 PROBE_SUPPORTED, 6 WIRELESS_PROJECTION_DISABLED, 7 POWER_DOWN, 8 USER_PROFILE_SWITCH |
| 16 | BYEBYE_RESPONSE | both | empty `xnu` |
| 17 | VOICE_SESSION_NOTIFICATION | ph→HU | `xvi {1: enum}`: 1 = start, 2 = end |
| 18 | AUDIO_FOCUS_REQUEST | ph→HU | `xne {1: enum}`: 1 GAIN, 2 GAIN_TRANSIENT, 3 GAIN_TRANSIENT_MAY_DUCK, 4 RELEASE |
| 19 | AUDIO_FOCUS_NOTIFICATION | HU→ph | `xnd {1: enum state, 2: bool unsolicited}`. States: 1 GAIN, 2 GAIN_TRANSIENT, 3 LOSS, 4 LOSS_TRANSIENT_CAN_DUCK, 5 LOSS_TRANSIENT, 6 GAIN_MEDIA_ONLY, 7 GAIN_TRANSIENT_GUIDANCE_ONLY |
| 20/21 | CAR_CONNECTED_DEVICES_REQUEST/RESPONSE | — | named only; no handler seen |
| 22/25 | USER_SWITCH_REQUEST/RESPONSE | — | named only |
| 23 | BATTERY_STATUS_NOTIFICATION | ph→HU | `xni {1: uint32 level, 2: uint32 time_remaining, 3: bool critical}` (sender `iol`) |
| 24 | CALL_AVAILABILITY_STATUS | HU→ph | `xnv {1: bool call_available}` |
| 26 | SERVICE_DISCOVERY_UPDATE | HU→ph | `xud {1: Service}`; replaces one service at runtime |
| 255 | UNEXPECTED_MESSAGE | | |
| 0xFFFF | FRAMING_ERROR | | |

Status enum `xqp` (shared by all services): 0 SUCCESS, 1 UNSOLICITED_MESSAGE, -1 NO_COMPATIBLE_VERSION, -2 CERTIFICATE_ERROR, -3 AUTHENTICATION_FAILURE, -4 INVALID_SERVICE, -5 INVALID_CHANNEL, -6 INVALID_PRIORITY, -7 INTERNAL_ERROR, -8 MEDIA_CONFIG_MISMATCH, -9 INVALID_SENSOR, -10…-17 BLUETOOTH_* (PAIRING_DELAYED, UNAVAILABLE, INVALID_ADDRESS, INVALID_PAIRING_METHOD, INVALID_AUTH_DATA, AUTH_DATA_MISMATCH, HFP_ANOTHER_CONNECTION, HFP_CONNECTION_FAILURE), -18 KEYCODE_NOT_BOUND, -19 RADIO_INVALID_STATION, -20 INVALID_INPUT, -21 RADIO_STATION_PRESETS_NOT_SUPPORTED, -22 RADIO_COMM_ERROR, -23 AUTH_FAILURE_CERT_NOT_YET_VALID, -24 AUTH_FAILURE_CERT_EXPIRED, -25 PING_TIMEOUT, -26 CAR_PROPERTY_SET_REQUEST_FAILED, -27 CAR_PROPERTY_LISTENER_REGISTRATION_FAILED, -250 COMMAND_NOT_SUPPORTED, -251 FRAMING_ERROR, -253 UNEXPECTED_MESSAGE, -254 BUSY, -255 OUT_OF_MEMORY.

### 2.3 Version negotiation (`jcj.a`, case 1)
- **The HU sends VERSION_REQUEST and the phone answers.** The phone never initiates.
- Version constants: `c` = 1.6, `d` = 1.7, `e` = 6.1.
  - If the HU requests a version ≤ **1.7**, the phone answers **1.7**. It answers 1.7 even when the HU asked for less; it does not echo min(HU, phone).
  - If the HU requests anything above 1.7, the phone answers **6.1**.
- The status is always SUCCESS. If the HU asks for more than 6.1 the phone only logs "maximum supported is 6.1".
- The phone remembers the **HU's requested version** and uses it to gate features:
  - ≥1.6: extras in the version response and in the SDP request;
  - ≥4.3: UI features from UiConfig;
  - ≥5.0: "ackless" audio, ≥5.0 single video codec check;
  - ≥6: decoder_additional_depth forced to 0.
- Optional trailer: if the HU's version is ≥ 1.6 and the flag `FrameworkGalFeature__use_ping_configuration` is on (default off), the 8 raw bytes are followed by a protobuf `xvb {1: xos {1: xsc {1,2,3,4: uint32}}}`. This is a ping configuration. The flag `FrameworkGalFeature__gal_ping_configuration` defaults to `{1:3, 2:1000, 3:200, 4:5}`. The individual field meanings are not determined; they look like count / interval ms / timeout ms / threshold.

### 2.4 Session sequence (phone view)
1. Transport up (USB/TCP). The HU sends VERSION_REQUEST, and the phone replies VERSION_RESPONSE in clear.
2. HU ClientHello (type 3), then handshake ping-pong on type 3. The phone is the server and requests a client cert.
3. The HU sends AUTH_COMPLETE (type 4, status 0). From here on everything is encrypted.
4. The phone sends **ServiceDiscoveryRequest (5)**, and the HU replies **ServiceDiscoveryResponse (6)**.
5. For each service it wants, the phone sends **ChannelOpenRequest (7)** on channel = service id, and the HU replies ChannelOpenResponse (8). Service-specific setup then follows (§4).
6. Pings flow during the session. On a HU PingRequest the phone answers immediately with the same timestamp; if `bug_report` is true it may trigger a bug report. On USB the phone does not originate pings. On Wi-Fi a ping manager (`jua`, extends `jdx`) does; its period and timeout come from flags.
7. Shutdown: either side sends BYEBYE_REQUEST(reason) and the peer answers BYEBYE_RESPONSE. When the HU sends DEVICE_SWITCH, the phone treats it as a handoff.

### 2.5 ServiceDiscoveryRequest — what the phone sends (`xub`, built in `jrt`)

| field | type | content |
|---|---|---|
| 1 | bytes | small icon: PNG `res/raw/car_android_32.png` (32×32) |
| 2 | bytes | medium icon: `car_android_64.png` (64×64) |
| 3 | bytes | large icon: `car_android_128.png` (128×128) |
| 4 | string | label: resource `car_hu_label` = **"Android"** |
| 5 | string | device name: `Build.MODEL`, prefixed with `Build.MANUFACTURER + " "` unless the model already starts with it |
| 6 | message `xmq` | PhoneInfo; only if HU version ≥ 1.6. The phone fills `1: string` with a persistent random UUID kept in SharedPreferences `DEVICE_ID_SHARED_PERF/DEVICE_ID` (`dto.E`). `xmq` has 4 string fields; the Wi-Fi path also fills 3 (BT name) and 4 (BT MAC) |

### 2.6 ServiceDiscoveryResponse — what the phone reads (`xuc`, consumed in `jru` / `dto.F`)

| field | type | meaning (from usage) |
|---|---|---|
| 1 | repeated `Service` (`xua`) | the services |
| 2 | string | vehicle make |
| 3 | string | vehicle model ("…truck" suffix ⇒ vehicle type truck) |
| 4 | string | year |
| 5 | string | vehicle id |
| 6 | enum | driver position: 0 LEFT, 1 RIGHT, 2 CENTER, 3 UNKNOWN |
| 7 | string | HU make |
| 8 | string | HU model |
| 9 | string | HU software build |
| 10 | string | HU software version |
| 11 | bool | can play native media during VR |
| 13 | int32 | session-configuration bitmask. bit0 = HU draws the clock (hide projected clock). bit1 and bit2 = HU draws the phone-signal / battery indicators. bit3 = native media during VR. The bit0–2 meanings come from how they feed `CarDisplayUiFeatures`; the exact bit1/bit2 assignment is not fully certain |
| 14 | string | display name (falls back to make) |
| 15 | bool | probe-for-support |
| 17 | `xmp` HeadUnitInfo | 1 make, 2 model, 3 year, 4 vehicle_id, 5 HU make, 6 HU model, 7 HU sw build, 8 HU sw version, 9 int32 (vehicle type, 1 car / 2 truck). If absent, it is synthesised from fields 2–10 |

The car is then looked up in the local DB tables `allowedcars` / `rejectedcars` (`jpb`). A new car gets a random id, and the user has to accept it on first connection.

**`Service` (`xua`):** `1: int32 id` (required; the channel number) plus exactly one of:

| field | presence bit | service | handler |
|---|---|---|---|
| 2 | 0x2 | SensorSourceService `xty` | `jok`/`ine` → `jel` |
| 3 | 0x4 | MediaSinkService `xqm` (video if video configs present, else audio) | `ixo` video, `ito`/`qwn` audio |
| 4 | 0x8 | InputSourceService `xpt` | `imn` → `jdg` |
| 5 | 0x10 | MediaSourceService `xqn` (microphone) | `izm` → `jdo` |
| 6 | 0x20 | BluetoothService `xnq` | `qwx` → `rye` |
| 7 | 0x40 | RadioService `xsx` | `ina` → `jei` |
| 8 | 0x80 | NavigationStatusService `xrl` | `imr` → `jdq` |
| 9 | 0x100 | MediaPlaybackStatusService (empty) | `iyp` → `jdk` |
| 10 | 0x200 | PhoneStatusService (empty) | `imt` → `jdw` |
| 11 | 0x400 | MediaBrowserService (empty) | (none found) |
| 12 | 0x800 | VendorExtensionService `xuz {1 name, 2 package whitelist, 3 data}` | `jsb` (matched by name) |
| 13 | 0x1000 | GenericNotificationService (empty) | (none found) |
| 14 | 0x2000 | WifiProjectionService `xvl {1: string car_wifi_bssid}` | `inp` → `jeu` |
| 15 | 0x4000 | CarControlService `xoc` (car properties) | `imh` → `jbb` |
| 16 | 0x8000 | CarLocalMediaService `xoj` | `imp` → `jbi` |
| 17 | 0x10000 | "buffered media" sink `xnr` | `iwg` → `jfa` |
| 18 | 0x20000 | CarIntentService `xoe` | `imo` → `jbg` |

Field meanings of the sub-messages the phone parses:

- **`xty` SensorSourceService:** 1 repeated `{1: enum SensorType}`, 2 uint32 location_characterization, 3 repeated FuelType, 4 repeated EvConnectorType.
- **`xqm` MediaSinkService:**
  - 1 enum codec (`xqg`: 1 PCM, 2 AAC_LC, 3 H264_BP, 4 AAC_LC_ADTS, 5 VP9, 6 AV1, 7 H265)
  - 2 enum audio stream type (1 GUIDANCE, 2 SYSTEM_AUDIO, 3 MEDIA, 4 TELEPHONY)
  - 3 repeated AudioConfiguration `xnc {1 sample_rate, 2 bits, 3 channels}`
  - 4 repeated VideoConfiguration `xvd`
  - 6 uint32 display_id
  - 7 enum display_type (0 MAIN, 1 CLUSTER, 2 AUXILIARY)
  - 8 enum initial content keycode
  - 9 enum (unknown)
- **`xvd` VideoConfiguration:**
  - 1 resolution enum `xvc`: 1 800x480, 2 1280x720, 3 1920x1080, 4 2560x1440, 5 3840x2160, 6 720x1280, 7 1080x1920, 8 1440x2560, 9 2160x3840
  - 2 frame-rate enum (must decode to 30 or 60)
  - 3 width_margin, 4 height_margin
  - 5 density (**required**, > 0)
  - 6 decoder_additional_depth
  - 8 pixel_aspect_ratio_e4
  - 9 real density
  - 10 video codec
  - 11 UiConfig `xup`
- **`xpt` InputSourceService:**
  - 1 packed int32 keycodes (Android KEYCODE values)
  - 2 touchscreen `{1 width, 2 height, 3 type}`
  - 3 touchpad `{1 w, 2 h, 3 ui_navigation, 4 physical_w, 5 physical_h, 6/7 bools, 8 sensitivity}`
  - 4 repeated feedback events supported (1 SELECT, 2 FOCUS_CHANGE, 3 DRAG_SELECT, 4 DRAG_START, 5 DRAG_END)
  - 5 uint32 display_id
- **`xqn` MediaSourceService (mic):** 1 codec, 2 AudioConfiguration (required by the phone).
- **`xnq` BluetoothService:** 1 car MAC address (required), 2 packed pairing methods (−1 UNAVAILABLE, 1 OOB, 2 NUMERIC_COMPARISON, 3 PASSKEY_ENTRY, 4 PIN).
- **`xrl` NavigationStatusService:**
  - 1 minimum_interval_ms (required)
  - 2 type enum (required; image vs enum-only turn data)
  - 3 image options `{1 height, 2 width, 3 colour_depth_bits}`

---

## 3. Transports

### 3.1 USB (AOA)
- **Accessory filter** (`res/xml/car_usb_accessory_filter.xml`). The manufacturer is always `Android`, and the model is one of `Android Open Automotive Protocol`, `Android Auto`, `Android`.
  - The filter places no constraints on description, version, URI or serial.
  - Model `Android` is ignored ("Do not handle accessory model 'Android'") unless a server flag (`adic…r()`) is set.
  - **A HU should use `Android` / `Android Auto`.**
- **Entry point:** `com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl` (process `:car`). It handles `android.hardware.usb.action.USB_ACCESSORY_ATTACHED`, plus `com.google.android.gms.car.usb.USB_ACCESSORY_FORCE_START` and `com.google.android.gms.carsetup.START_DUPLEX`.
  - Receivers: `CarUsbReceiver` and `CarUsbReceiverTPlus`. The latter handles `USB_ACCESSORY_HANDSHAKE` on Android 14+, and its extras are used only for timing telemetry.
- **Opening the accessory** (`com.google.android.gms.carsetup.setup.UsbConnectionHelper`):
  1. Checks `MANAGE_USB`, plus `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` on API 31+.
  2. Calls `UsbManager.getAccessoryList()` and `hasPermission`, then `openAccessory()` to get a `ParcelFileDescriptor`. Older FDs are closed first. It retries once after 500 ms.
  3. Watches `USB_ACCESSORY_DETACHED`.
- **Streams:** the FD is wrapped as a plain `FileInputStream`/`FileOutputStream`. The reader thread `rzn` reads into a 16384-byte buffer, and the USB output is unbuffered.
- **Framer settings by connection type:**

| connection type | QoS | concurrent channel map | buffered output |
|---|---|---|---|
| 1 USB | on | on | none |
| 2 Wi-Fi | on | on | 16384 |
| 3 dev server | defaults | defaults | defaults |

- **Recovery:** the watchdog `ConnectionResetReceiver` (actions RESET_USB_PORT, RESET_USB_GADGET, RESET_USB_ROLES, RESET_USB_FUNCTION) and the USB monitors `seq`/`sdo`/`sdp`/`sea` reset USB when no accessory appears.

### 3.2 Wireless (Bluetooth RFCOMM + Wi-Fi)
- **RFCOMM service UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`.** The byte-reversed form `669a0c20-0008-f4bd-e611-cb52007ae14d` is also accepted in the HU's SDP record.
- **The phone is the RFCOMM client** (`createRfcommSocketToServiceRecord`, a secure socket). The HU must run the server. The device must already be BT-bonded, and the phone runs `fetchUuidsWithSdp` if the UUID is not yet known.
- **WPP framing over RFCOMM:** `[uint16 BE length][uint16 BE type][protobuf]`, with the payload limited to 1024 bytes.

| id | message | dir (phone view) | fields |
|---|---|---|---|
| 1 | WifiStartRequest `xvx` | HU→ph (ph→HU for an MD-initiated start) | 1 ip, 2 port, 3 reason (0 USER_REQUEST, 1 AUTO_LAUNCH, 2 AUTOMATIC_RESTART) |
| 2 | WifiInfoRequest `xvp` | ph→HU | empty |
| 3 | WifiInfoResponse `xvq` | HU→ph | 1 ssid, 2 password, 3 bssid, 4 security_mode, 5 access_point_type (0 STATIC, 1 DYNAMIC) |
| 4 | WifiVersionRequest `xvz` | HU→ph | 1 major, 2 minor, 3 channel_type, 4 repeated channels, 5 HeadUnitInfo `xmp`, 6 WppEndpoint `{1 ip, 2 port}` |
| 5 | WifiVersionResponse `xwa` | ph→HU | 1 major, 2 minor (echoed), 3 device serial, 4 status, 5 selected channel type, 6 PhoneInfo `xmq`, 7 setup token |
| 6 | WifiConnectStatus `xvn` | ph→HU | 1 status, 2 error hint (not sent for WPP ≥ 4.5) |
| 7 | WifiStartResponse `xvy` | ph→HU (or HU→ph) | 1 ip, 2 port, 3 status |
| 8/9 | WifiPing request/response `xvs`/`xvt` | ph→HU / HU→ph | 1 int64 timestamp_ms; the phone pings every 1 s and gives up after 10 misses |
| 10 | ConnectionRejection `xvo` | HU→ph | 1 reason (1 MOBILE_DEVICE_ID_NOT_FOUND, 2 INVALID_SETUP_TOKEN) |
| 11 | WifiSetupInfo `xvw` | HU→ph | 1 major, 2 minor, 3 setup token, 4 endpoint, 5 AP info |

- **Security modes:** 1 OPEN, 2 WEP_64, 3 WEP_128, 4 WPA, 8 WPA2_PERSONAL, 12 WPA/WPA2, 20/24/28 enterprise, 32 WPA3_PERSONAL, 40 WPA2_WPA3_PERSONAL. **The phone only accepts 8 or 40**, with a non-empty SSID, a valid BSSID and an ASCII password.
- **WPP status codes:** 0 SUCCESS, 1 UNSOLICITED, −1 NO_COMPATIBLE_VERSION, −2 INACCESSIBLE_CHANNEL, −3 INCORRECT_CREDENTIALS, −4 PROJECTION_ALREADY_STARTED, −5 WIFI_DISABLED, −6 NOT_YET_STARTED, −7 INVALID_HOST, −8 NO_SUPPORTED_CHANNELS, −9 CHECK_PHONE, −10 PHONE_WIFI_DISABLED, −11 NETWORK_UNAVAILABLE.
- **Sequence:**
  1. The phone connects RFCOMM.
  2. HU WifiVersionRequest; phone WifiVersionResponse.
  3. HU WifiStartRequest(ip, port); phone WifiStartResponse and WifiInfoRequest.
  4. HU WifiInfoResponse.
  5. The phone joins the AP (`WifiNetworkSpecifier`, no INTERNET capability).
  6. **The phone opens a TCP client connection to the HU's ip:port.** 5288 is only the usual HU port; the phone hard-codes none. Connect timeout 10 s, 3 tries.
  7. Normal AAP, including in-band TLS, then runs over that socket.
- **Phone-initiated start (Android Auto 18.0):** if the HU sends no WifiStartRequest within **5 s** of a compatible version exchange (and no projection connection is up), the phone sends one itself, with an **empty** body, but only if it already intends to start projection: that intention is set by a WifiStartRequest from the HU, by the user starting wireless projection, or by a server flag that is off by default; on a plain Bluetooth auto-connect the phone sends nothing. The HU answers with a WifiStartResponse (ip, port, status). On SUCCESS the phone goes on with that ip/port as from step 3 (WifiInfoRequest, join, connect) but sends **no** WifiStartResponse of its own; WifiConnectionStatus still follows the join. WIFI_NOT_YET_STARTED (−6) and PROJECTION_ALREADY_STARTED (−4) make it ask again every **2 s**, until **50 s** after the first request; WIFI_DISABLED (−5) stops. Head units built on Google's receiver library may wait for this request from a phone the user has not yet allowed on the head unit (the decompiled head-unit app answers PROJECTION_ALREADY_STARTED until then; not observed). That receiver also needs PhoneInfo with a non-empty instance_id (field 6 → 1) in the WifiVersionResponse, dropping every phone message except pings and version responses without it, and closes RFCOMM when **10 s** pass without a phone WifiPingRequest (the window opens at its WifiVersionRequest and restarts with each ping; the phone pings every **1 s**). On a Jancar head unit built on this receiver (WPP 4.2), tested with AARE, phone_info and the pings were enough: the head unit sent its own WifiStartRequest about 1 s after the version exchange.
- **WPP-over-TLS (newer):** the HU's WifiSetupInfo provides a TCP endpoint and a setup token. The phone connects and wraps the socket in TLS **as server with client auth**, using the same CarService cert and GAL root (`pkf`).
- **No** Wi-Fi Direct, NSD/mDNS or UDP discovery is used.
- `GH.WirelessProxy`: an HTTP-CONNECT style proxy on phone TCP 8990, so the HU can reach the internet. It is not needed for projection.

### 3.3 Developer "head unit server" (TCP 5277)
- `com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService`.
- To enable it: tap the version entry in AA settings 10 times, which enables developer settings. The overflow menu then offers "Start head unit server".
- **The phone listens** on `ServerSocket(5277)`, bound to all interfaces. The DHU connects as client, usually through `adb forward tcp:5277 tcp:5277`.
- Each accepted socket is bridged through a socketpair into `START_DUPLEX` (connection type 3), one HU at a time. The protocol is plain AAP over the socket, including version exchange and TLS. The DHU uses the same cert scheme.

### 3.4 Generic entry
`rey.f(fd, type, startActivities, suppressRestart)` starts a session on any stream FD via `FirstActivityImpl` + `START_DUPLEX`, with extras `connection_fd`, `connection_type`, `start_activities`, `connection_tag` and `suppress_restart`.

---

## 4. Service channels (phone perspective)

All service messages use ids ≥ 0x8000 and are encrypted. Shorthand: "ph" = phone, "HU" = head unit.

### 4.1 Media sink (video and audio output; base class `jga`)
Media message ids come from the in-app table `xab.K`/`xab.L`:

| id | name | dir | proto |
|---|---|---|---|
| 0x0000 | MEDIA_DATA | ph→HU (sink), HU→ph (mic) | raw: `int64 BE timestamp (µs)` + payload |
| 0x0001 | CODEC_CONFIG | ph→HU | raw codec config (SPS/PPS, AAC ASC), no timestamp |
| 0x8000 | SETUP | ph→HU | `xug {1: enum codec}` |
| 0x8001 | START | ph→HU | `xui {1: int32 session_id, 2: uint32 config_index, 3: enum (2 = "ackless" audio when HU ≥ 5.0), 4: xqh options}` |
| 0x8002 | STOP | ph→HU | empty `xuj` |
| 0x8003 | CONFIG | HU→ph | `xor {1: enum status, 2: uint32 max_unacked, 3: repeated uint32 configuration_indices}` |
| 0x8004 | ACK | HU→ph (and ph→HU for mic) | `xmx {1: int32 session_id, 2: uint32 ack_count, 3: repeated uint64 timestamps}` |
| 0x8005 | MICROPHONE_REQUEST | ph→HU | `xqq {1: bool open, 2: bool anc, 3: bool echo_cancel, 4: int32 max_unacked}` |
| 0x8006 | MICROPHONE_RESPONSE | HU→ph | `xqr {1: int32 status, 2: int32 session_id}` |
| 0x8007 | VIDEO_FOCUS_REQUEST | ph→HU | `xvh {2: enum mode, 3: enum reason}` |
| 0x8008 | VIDEO_FOCUS_NOTIFICATION | HU→ph | `xvf {1: enum mode, 2: bool unsolicited}` |
| 0x8009 | UPDATE_UI_CONFIG_REQUEST | HU→ph | `xuw {1: UiConfig}` |
| 0x800A | UPDATE_UI_CONFIG_REPLY | ph→HU | `xuw {1: UiConfig}` |
| 0x800B | AUDIO_UNDERFLOW_NOTIFICATION | HU→ph | empty |
| 0x800C | ACTION_TAKEN_NOTIFICATION | | |
| 0x800D–0x8010 | INTEGRATED_OVERLAY_{PARAMETERS, START, STOP, SESSION_DATA_UPDATE} | mixed | START is `xpx {1: int32}` HU→ph; STOP has no payload |
| 0x8011/0x8012 | UPDATE_HU_UI_CONFIG_REQUEST/RESPONSE | | 0x8012 is `xuy {1: enum}` HU→ph |
| 0x8013 | MEDIA_STATS | HU→ph | `xqo` (latency stats) |
| 0x8014 | MEDIA_OPTIONS | | |
| 0x8015 | CRITICAL_UI_NOTIFICATION | | |

- Video focus modes: 1 PROJECTED, 2 NATIVE, 3 NATIVE_TRANSIENT, 4 PROJECTED_NO_INPUT_FOCUS. Reasons: 0 UNKNOWN, 1 PHONE_SCREEN_OFF, 2 LAUNCH_NATIVE, 3 LAST_MODE, 4 USER_SELECTION.
- **Flow:**
  1. Once the channel opens, the phone sends SETUP(codec).
  2. The HU answers CONFIG(status, max_unacked, indices). It must arrive exactly once; a second CONFIG is an error.
  3. The phone sends START(session_id, config_index). Starting before CONFIG is an error.
  4. The phone sends CODEC_CONFIG, then DATA frames.
  5. The HU ACKs with `session_id` (compared mod 256) and a count of 1–4096 frames. A count outside that range is a protocol error.
- **Flow control:** the phone keeps at most `max_unacked` frames in flight. In "drop overflow" mode the minimum is 24. After 400 consecutive waits without an ACK it reports "Car not sending ACK" (`VIDEO_ACK_TIMEOUT` / `AUDIO_ACK_TIMEOUT`).
- On STOP the session id increments.
- **Video codec choice (`ixo`):**
  - H.264 Baseline by default.
  - **H.265 if any HU video config asks for it and the phone has an encoder.** Otherwise the user sees an error.
  - VP9/AV1 exist in the enum, but I found no phone-side selection of them.
  - FPS must be 30 or 60, and density is required.
  - With HU ≥ 5.0 (flag-gated), mixing codecs within one display is an error.
- **Audio sinks:** PCM16 at 48000 or 16000 Hz, 1 or 2 channels (`uga.ak`).
  - The phone **requires a 48 kHz stereo config for MEDIA** and **16 kHz mono for GUIDANCE/SYSTEM**. Without them it fails with "no default config … missing".
  - If the HU's codec is AAC_LC or AAC_LC_ADTS, the phone encodes AAC-LC.
  - The audio endpoint types are 3 = guidance, 4 = system, 5 = media.

### 4.2 Microphone (MediaSource; `jfy`/`jdo`/`izn`)
1. The phone sends MICROPHONE_REQUEST `{open=true, anc=false, ec=false, max_unacked=2}`. Closing sends `{open=false}`.
2. The HU answers MICROPHONE_RESPONSE `{status, session_id}`.
3. The HU sends audio as MEDIA_DATA (type 0, 8-byte timestamp + PCM).
4. **The phone ACKs every packet** with 0x8004 `{session_id, ack=1}`.

The mic format comes from the HU's MediaSourceService config, normally 16 kHz mono PCM16.

### 4.3 Input (`jdg`, `imn`)

| id | name | dir | proto |
|---|---|---|---|
| 0x8001 | INPUT_REPORT | HU→ph | `xpq` |
| 0x8002 | KEY_BINDING_REQUEST | ph→HU | `xpy {1: packed int32 keycodes}` (sent right after the channel opens) |
| 0x8003 | KEY_BINDING_RESPONSE | HU→ph | `xpz {1: int32 status}` |
| 0x8004 | INPUT_FEEDBACK | ph→HU | `xpp {1: enum feedback}` |

- **`xpq` InputReport:** 1 uint64 timestamp (required), 3 touch_event, 4 key_event, 5 absolute_event, 6 relative_event, 7 touchpad_event.
- **TouchEvent `xun`:**
  - 1 repeated pointer `{1 x, 2 y, 3 pointer_id}`
  - 2 action_index
  - 3 action: Android MotionEvent action codes (0 DOWN, 1 UP, 2 MOVE, 5 POINTER_DOWN, 6 POINTER_UP)
- **KeyEvent `xqc`:** repeated `{1 keycode, 2 down, 3 metastate, 4 longpress}`.

### 4.4 Sensors (`jel`)

| id | name | dir | proto |
|---|---|---|---|
| 0x8001 | SENSOR_REQUEST | ph→HU | `xtv {1: enum sensor_type, 2: int64 min_update_period}` |
| 0x8002 | SENSOR_RESPONSE | HU→ph | `xtw {1: status}`; the phone waits 2 s for it |
| 0x8003 | SENSOR_BATCH | HU→ph | `xts` |
| 0x8004 | SENSOR_ERROR | HU→ph | `xtt {1 type, 2 error}`; errors: 1 OK, 2 TRANSIENT, 3 PERMANENT |

- **Sensor types:** 1 LOCATION, 2 COMPASS, 3 SPEED, 4 RPM, 5 ODOMETER, 6 FUEL, 7 PARKING_BRAKE, 8 GEAR, 9 OBDII_DTC, 10 NIGHT_MODE, 11 ENVIRONMENT, 12 HVAC, 13 DRIVING_STATUS, 14 DEAD_RECKONING, 15 PASSENGER, 16 DOOR, 17 LIGHT, 18 TIRE_PRESSURE, 19 ACCELEROMETER, 20 GYROSCOPE, 21 GPS_SATELLITE, 22 TOLL_CARD, 23 VEHICLE_ENERGY_MODEL, 24 TRAILER, 25 RAW_VEHICLE_ENERGY_MODEL, 26 RAW_EV_TRIP_SETTINGS.
- **SensorBatch field N is a repeated list of sensor type N.** Examples:
  - 1 = location `{2 lat_e7, 3 lon_e7, 4 accuracy_e3, 5 altitude_e2, 6 speed_e3, 7 bearing_e6}`
  - 8 = gear `{1 enum}`
  - 10 = night mode `{1 bool}`
  - 13 = driving status `{1 int32 bitmask}`
- **Subscriptions:** at setup the phone always requests **DRIVING_STATUS** (period 0; `sgm`, `juf`). Other sensors are requested on demand when a phone-side client registers. Typical ones are NIGHT_MODE for day/night, LOCATION when car GPS is used, and SPEED/GEAR/PARKING_BRAKE.

### 4.5 Navigation status (`jdq`, `imr`)

| id | dir | proto |
|---|---|---|
| 0x8001 INSTRUMENT_CLUSTER_START | HU→ph | — |
| 0x8002 INSTRUMENT_CLUSTER_STOP | HU→ph | — |
| 0x8003 NAVIGATION_STATUS | ph→HU | `xri {1: enum status}` (active/inactive/rerouting) |
| 0x8004 NEXT_TURN_EVENT | ph→HU | `xrf {1 road, 2 turn_side, 3 event/maneuver, 4 bytes image (PNG), 5 turn_number, 6 turn_angle}` |
| 0x8005 NEXT_TURN_DISTANCE | ph→HU | `xre {1 distance_m, 2 time_to_turn_s, 3 display_distance_e3, 4 display_unit}` |
| 0x8006 NAVIGATION_STATE | ph→HU | `xrh {1 repeated steps (maneuver, road, lanes, cue), 2 repeated destinations}` |
| 0x8007 NAVIGATION_CURRENT_POSITION | ph→HU | `xqx {1 step distance, 2 destination distances, 3 road}` |
| 0x8008 / 0x8009 | ph→HU | `xtf` / `xte {1: bytes}` wrapping a serialized vehicle-energy forecast (`xtn`) |

### 4.6 Media playback status (`jdk`)
- **0x8001 PLAYBACK_STATUS** (ph→HU) `xsg`: 1 state (0 UNKNOWN, 1 PLAYING, 2 PAUSED, 3 STOPPED, 4 BUFFERING), 2/3 uint64, 4 enum, 5/6 uint64.
- **0x8002 PLAYBACK_INPUT** (HU→ph) `xpv`: 1 enum action.
- **0x8003 PLAYBACK_METADATA** (ph→HU) `xqi`: 1 song, 2 artist, 3 album, 4 album-art bytes, 5 playlist, 6 duration_s, 7 rating.

### 4.7 Phone status (`jdw`, `imt`)
- **0x8001 PHONE_STATUS** (ph→HU) `xrz`:
  - 1 repeated call `{1 state, 2 duration_s, 3 number, 4 name, 5 number_type, 6 thumbnail bytes}`
  - 2 signal_strength
  - Call states: 1 IN_CALL, 2 ON_HOLD, 3 INACTIVE, 4 INCOMING, 5 CONFERENCED, 6 MUTED.
- Telephony audio itself stays on Bluetooth HFP.

### 4.8 Bluetooth (`rye`, `qwx`)

| id | dir | proto |
|---|---|---|
| 0x8001 PAIRING_REQUEST | ph→HU | `xnn {1 phone MAC, 2 pairing method}` |
| 0x8002 PAIRING_RESPONSE | HU→ph | `xno {1 status, 2 already_paired}` |
| 0x8003 AUTH_DATA | HU→ph | `xnk {1 string auth_data}` (passkey) |
| 0x8004 AUTH_RESULT | ph→HU | `xnl {1 status}` |

### 4.9 Others
- **Radio** (`jei`): roughly 0x801A–0x8023. The exact per-message names were not mapped.
- **Car control** (`jbb`, properties): 0x8001–0x8007.
- **Car local media** (`jbi`): 0x8001–0x8003.
- **Wi-Fi projection channel** (`jeu`): carries the HU BSSID on the AAP side.
- **Vendor extension** (`jsb`): matched by service name; only Google-known names get handlers.
- **GenericNotification (field 13) and MediaBrowser (field 11):** no phone-side endpoint was found in this build. The phone appears not to open those channels.

---

## 5. Car App Library hosting, app discovery, manifest permissions

### 5.1 Permissions gearhead defines
- These are the only five `<permission>` elements:

| name | protectionLevel |
|---|---|
| `com.google.android.projection.gearhead.permission.START_PROJECTED_ACTIVITY` | signature |
| `com.google.android.projection.gearhead.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | signature |
| `androidx.car.app.ACCESS_SURFACE` | normal |
| `androidx.car.app.NAVIGATION_TEMPLATES` | normal |
| `androidx.car.app.MAP_TEMPLATES` | normal |

- **`android.car.permission.TEMPLATE_RENDERER` is not defined or referenced.**
- Gearhead *does* define three `androidx.car.app.*` permissions. A second package with another signature cannot define them while gearhead is installed.
- `androidx.car.app.MEDIA_TEMPLATES` is checked, for MediaPlaybackTemplate, but is not defined here.

### 5.2 Notable uses-permission
- **Privileged:** MODIFY_AUDIO_ROUTING, BLUETOOTH_PRIVILEGED, CAPTURE_SECURE_VIDEO_OUTPUT, CREATE_VIRTUAL_DEVICE, ADD_TRUSTED_DISPLAY, ADD_ALWAYS_UNLOCKED_DISPLAY, TOGGLE_AUTOMOTIVE_PROJECTION, REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION, REQUEST_COMPANION_SELF_MANAGED, MANAGE_USB, ENTER_CAR_MODE_PRIORITIZED, MODIFY_DAY_NIGHT_MODE, MODIFY_PHONE_STATE, CONTROL_INCALL_EXPERIENCE, LOCAL_MAC_ADDRESS, START_ACTIVITIES_FROM_BACKGROUND, INTERACT_ACROSS_PROFILES, RECEIVE_SENSITIVE_NOTIFICATIONS, and others.
- **Not requested:** CAPTURE_AUDIO_OUTPUT.
- **Also requested:** QUERY_ALL_PACKAGES, RECORD_AUDIO, `com.google.android.finsky.permission.GEARHEAD_SERVICE`.

### 5.3 Discovery
- **Car service validator:** `jao` (CarProjectionValidator) runs `queryIntentServices(GET_META_DATA)` on behalf of `qng` (GH.ServiceAuthorizer). Application types: OEM, NOTIFICATION, MEDIA, NAVIGATION, SERVICE, PROJECTION, SMS, TEMPLATE, MESSAGING, NATIVE_APP.
- **Template apps:** intent `androidx.car.app.CarAppService`.
  - Categories recognised: NAVIGATION, POI, SETTINGS, CHARGING (folded into POI), PARKING (folded into POI), MESSAGING, CALLING, WEATHER, MEDIA, and `FEATURE_CLUSTER`.
  - **`androidx.car.app.category.IOT` is not recognised** in this build.
- **Media apps:** intent `android.media.browse.MediaBrowserService`. There is no media3-specific action; media3 apps are found through their legacy action.
- **`com.google.android.gms.car.application` → `automotive_app_desc` XML:** it must declare `<uses name="…">` matching the queried type:
  - `template` for CarAppService apps;
  - `media` for MediaBrowserService apps;
  - `notification` for messaging notifications.

  The XML may also contain `<head-unit-whitelist>` conditions.
- **Meta-data read by the host:** `androidx.car.app.theme`, `androidx.car.app.TintableAttributionIcon`. `minCarApiLevel` is read app-side and arrives in AppInfo.
- **Global filter:** flag `Watevra__external_component_filter`. By default it is a DENYLIST containing Zoom's auto-meeting components.

### 5.4 Trust / install-source checks (`jao.z`)
Checks are applied in this order:
1. The package must be enabled.
2. Blocklist `AppValidation__blocked_packages_by_installer` (136 default entries).
3. `uses` check, then head-unit whitelist.
4. DHU bypass (flag default true). It applies when the vehicle make/model is "Google"/"Desktop Head Unit", or the HU is a Google/Panasonic "[AAR]" reference unit. These values come from the SDP response.
5. Global bypass flag (default false).
6. Google-signed packages pass.
7. Allowed-installer list (empty by default).
8. **Play check:** passes if the installer is `com.android.vending` (Google-signed), or if Play's `IPlayGearheadService` says the package is valid.
9. **Fallback** for packages that fail the Play check:
   - a signature allowlist (two Samsung packages);
   - the developer setting **"Unknown sources"** (`carservice` prefs, key `allow_unknown_sources`). It covers MEDIA, NAVIGATION, NOTIFICATION, OEM, NATIVE_APP, SERVICE and SMS only. **It does not cover TEMPLATE/PROJECTION/MESSAGING**, and it is force-disabled for template queries unless `UnknownSources__allow_full_screen_apps` is set.

**Result: a sideloaded Car App Library app (e.g. F-Droid CoMaps) is refused by gearhead even with Unknown sources enabled. Sideloaded media and notification apps are accepted.**

Gearhead keeps no app-side HostValidator allowlist. Apps validate the host (gearhead's package and Google signature) themselves, so our host will be rejected by apps that do not use ALLOW_ALL_HOSTS.

### 5.5 Binding and API level
- Binding: `bindService` on the CarAppService, then `getAppInfo`, then `onHandshakeCompleted(HandshakeInfo(hostPkg, negotiatedApi))`, then the `onApp*` lifecycle calls.
- Negotiated level = min(app latest, host max). The host's latest level is **9**, matching assets `car-app-api.level` = 9.
- **Third-party apps are capped at API 8** (`Watevra__host_max_api_level` DEFAULT:8). The developer "beta features" setting raises the cap to 9.
- Limits:

| item | limit |
|---|---|
| list items | 6 (gearhead's own apps: 20) |
| grid items | 6 (gearhead's own apps: 24) |
| pane rows | 4 |
| route-preview routes | 3 |
| template stack depth | 5 |

- **Template permission checks:**

| template / capability | permission required |
|---|---|
| Navigation, PlaceListNavigation, RoutePreview, Map templates | NAVIGATION_TEMPLATES |
| MapWithContent | MAP_TEMPLATES or NAVIGATION_TEMPLATES |
| PlaceListMap | MAP_TEMPLATES (+ location) |
| MediaPlayback | MEDIA_TEMPLATES |
| surface access | ACCESS_SURFACE |

### 5.6 Notifications / messaging
- Notifications come in through a NotificationListenerService.
- The package must pass validation for type NOTIFICATION and also appear in the launcher list.
- **Parsing:** MessagingStyle first (it needs a sender), then BigPicture, then the legacy CarExtender (`android.car.EXTENSIONS`).
- **To count as a conversation it needs:**
  - a mark-as-read action, and
  - a reply action with RemoteInput (semantic actions or the CarExtender intents).
- Template messaging apps use the MESSAGING category and `androidx.car.app.messaging.action.HANDLE_CAR_MESSAGING`.

---

## 6. Phone audio routing

### 6.1 Capture
- Gearhead registers its **own `AudioPolicy`** (`ivh`/`ivt`/`ivu`/`ivx`, tag CAR.AUDIO.Policy). It uses `AudioMix` rules matching `USAGE` attributes, `ROUTE_FLAG_LOOP_BACK`, PCM16, and reads through `AudioPolicy.createAudioRecordSink`. **This requires MODIFY_AUDIO_ROUTING** (privileged), which gearhead holds as a privileged/preinstalled-style app.

| stream | usages captured | format |
|---|---|---|
| MEDIA | USAGE_MEDIA (+ USAGE_GAME by flag) | 48 kHz stereo |
| GUIDANCE (TTS) | USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, USAGE_ASSISTANT | mono, HU guidance rate (16 kHz) |
| SYSTEM | no mix found | source not determined |

- **"Single channel" mode** mixes everything into one 48 kHz stereo stream. It is used for some HUs (KIA/HYUNDAI, SYNC, VW 2016) or as a fallback.
- **Last-resort fallback:** `REMOTE_SUBMIX` `AudioRecord`, which needs CAPTURE_AUDIO_OUTPUT. Gearhead does not request it.
- **Bluetooth:** A2DP to the car is disabled while projecting (`disableA2dp`). Telephony stays on HFP; VOICE_COMMUNICATION and RINGTONE focus are ignored.
- **Encoding:** PCM by default. AAC-LC goes through a MediaCodec `audio/mp4a-latm` encoder when the HU asks for it, with the ADTS header added by gearhead. Resampling is done by `libresampling_jni`.
- **Focus mapping:** the Android focus stack is observed via `AudioPolicyFocusListener` and mapped to message 18:

| Android focus | car request |
|---|---|
| GAIN | GAIN |
| TRANSIENT | TRANSIENT |
| MAY_DUCK | MAY_DUCK |
| EXCLUSIVE | TRANSIENT |
| LOSS | RELEASE |

  Frames are held or dropped until the HU grants focus with message 19.

### 6.2 Car microphone to apps
- The car mic is **not injected into Android's audio input**. There is no AudioPolicy injection and no virtual audio device.
- It is exposed only through `GhMicrophoneContentProvider`:
  - URI: `content://com.google.android.projection.gearhead.provider/mic`
  - Format: a pipe of 16 kHz mono PCM16
  - Callers: Google-signed GSA or gearhead only.
- Car App Library apps get it through `IAppHost.openMicrophone`. This requires API > 4 and RECORD_AUDIO; gearhead returns a PFD from the same provider.
- Ordinary apps using `AudioRecord` get the phone's own mic.

### 6.3 Display (related)
- Projection uses a **self-managed CompanionDevice association** with role `android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION` (display name "AA-GAL").
- It runs on a **VirtualDevice** with a trusted VirtualDisplay (flag TRUSTED, plus OWN_CONTENT_ONLY on 35+) and a VirtualTouchscreen "AA-TOUCHSCREEN:<id>" and VirtualDpad "AA-DPAD:<id>" (vendorId 6880).

---

## 7. Projected UI ("Coolwalk")

### 7.1 Inputs from the HU
- The display comes from VideoConfiguration (`xvd`, §2.6) and **UiConfig `xup`**:

| field | meaning |
|---|---|
| 1 | margins Insets |
| 2 | content_insets |
| 3 | stable_insets |
| 4 | UI theme (0 AUTOMATIC, 1 LIGHT, 2 DARK) |
| 5 | repeated hidden/HU-drawn UI elements (1 CLOCK, 2 BATTERY_LEVEL, 3 PHONE_SIGNAL, 4 NATIVE_UI_AFFORDANCE, 5 NAVIGATION_TURN_DATA_AVAILABLE) |
| 6 | supported actions (1 RESIZE_TO_SMALLER, 2 RESIZE_TO_LARGER) |
| 7 | cutout rects |
| 8 | blended-UI config (corner radii + native UI elements) |

  Insets and rects share one layout: 1 top, 2 bottom, 3 left, 4 right.
- Runtime changes arrive in UPDATE_UI_CONFIG_REQUEST (0x8009), and the phone replies with 0x800A.
- **Visible area** = codec frame minus margins:
  - without `ui_config.margins`: the margins are split evenly around a centred rect (width_margin/height_margin);
  - with them: explicit per side.

  The pixel aspect ratio rescales this.
- **dpi = the HU's `density` field.** The phone has **no default dpi per resolution**; the HU must send it.
- The UI elements from field 5, or from the SDP session-config bits before protocol 4.3, decide which of clock, battery and signal the phone draws itself.

### 7.2 Layout selection (`nxj.y`, flags in `addn`)
- The phone computes dp = px × 160 / dpi on the content area (display minus content insets, falling back to stable insets), then compares it against breakpoints:

| breakpoint | value |
|---|---|
| widescreen width | 1240 dp |
| widescreen aspect ratio | 1.67 |
| semi-widescreen | 880 dp |
| portrait height | 900 dp |
| short portrait height | 680 dp |
| narrow portrait width | 730 dp |
| horizontal rail needs height | ≥ 450 dp |

- Layouts: `sys_ui_layout_canonical_{lhd,rhd}`, `canonical_vertical_rail_*`, `widescreen_*`, portrait variants, "hero" layouts when cutouts or blended UI are present, cluster and auxiliary layouts, `projected_app` single-app.
- **Canonical:**
  - Bottom **rail** of 80 dp across the full width: launcher, assistant mic, ongoing media/nav widget, 4 recent-app slots, and a status area with signal/battery/clock/notifications.
  - The map/app region sits above the rail.
  - A **dashboard** column (cards) takes about 31% of the width on the passenger-far side: the right side for LHD, mirrored for RHD.
- **Widescreen / vertical rail:**
  - 80 dp vertical rail on the driver side (92 dp when ≥ 1376 dp wide).
  - Dashboard width ≈ content width / 3.2.
- **Portrait:** map on top, 196 dp dashboard, rail at the bottom.
- **Common dimensions:** gutter 10 dp (12 dp on large), card radius 24 dp, IME height 240 dp (300 dp at ≥ 730 dp width).
- **Worked examples** at 160 dpi with no insets:

| resolution | layout |
|---|---|
| 800×480 | canonical, bottom rail |
| 1280×720 | canonical |
| 1920×1080 | canonical |
| 1920×720 | widescreen |

  The real choice depends on the HU's density and insets.
- When encoder capabilities are unknown, the phone assumes at least 800×480 @ 30 fps.

---

## 8. Open questions / not determined
- Whether the embedded key really decrypts with the scheme in §1.3. I did not verify it; it was not run.
- Exact semantics of: SDP session-config bits 1/2 (battery vs signal), the `xsc` ping-config fields, PhoneInfo fields 1/2 on the Wi-Fi path, MediaSink field 9, and the 0x800C/0x8014/0x8015 payloads.
- The phone-side handling (if any) of GenericNotification, MediaBrowser, CAR_CONNECTED_DEVICES and USER_SWITCH.
- The PCM source of the SYSTEM audio stream.
- Live Phenotype values. Only in-APK defaults are given; server values can change cert, ping, filters and API caps.
- The exact branch order of the Coolwalk layout selector; the jadx output is partly garbled.
- How strictly real HUs check the phone certificate beyond its expiry. The CERT_EXPIRED status codes suggest they check dates.

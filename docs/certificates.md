# Certificates and head-unit compatibility

Most "AARE doesn't work with my car" reports come down to certificates. This page explains what
Android Auto checks, why AARE passes on some head units and fails on others, and how to find out
which case you are in.

Sources: the reverse-engineering notes in [research/gearhead-notes.md](research/gearhead-notes.md)
(§1, Google's Android Auto app 17.7), the AAP protobuf definitions in `aap/src/main/proto/`, and
tests against Google's Desktop Head Unit (DHU), open-source head units and AARE's own desktop head
unit. What isn't verified is marked as such.

## How the handshake works

- After the version exchange, phone and head unit run **TLS 1.2** inside AAP messages (channel 0,
  message type 3, `ENCAPSULATED_SSL`).
- **The phone is the TLS server; the head unit is the TLS client.** It is **mutual TLS**: each
  side sends a certificate and may check the other's.
- Once its handshake is done, the head unit sends **`AUTH_COMPLETE`** (message type 4), an
  `AuthResponse` with a status: `0` means accepted, a negative value means refused (see
  [Status codes](#status-codes)).

So there are two independent checks:

1. the **head unit checks the phone's certificate** (what decides whether AARE works in a car);
2. the **phone checks the head unit's certificate** (what decides whether a head unit works with
   Google's Android Auto app).

## What Google's Android Auto does

### The certificate it presents (as the phone)

- Every Android Auto install presents **the same certificate and RSA-2048 key**, embedded in the
  app. It isn't generated per phone or attested.
- The chain is `[CarService leaf, Google Automotive Link root]`:

| Certificate | Subject | Issuer | Valid |
|---|---|---|---|
| root | `O=Google Automotive Link`, Mountain View, US | itself | 2014-06-06 → 2044-06-05 |
| phone leaf | `O=CarService`, Mountain View, US | Google Automotive Link | 2014-07-04 → **2026-12-31** |

- The leaf expires on **2026-12-31**. Google can replace it remotely through server-side flags,
  so Android Auto will presumably get a new one; head units that check expiry report it with the
  `CERT_EXPIRED` status (-24).

### What it requires from the head unit

- Its trust store holds **one anchor: the Google Automotive Link root**. The head unit's
  certificate must chain to it, with the usual checks (validity dates included).
- It also refuses a head unit that sends the phone's own `CarService` certificate, or the root
  itself.

**Consequence:** Google's Android Auto only talks to head units with a certificate issued by
Google. Car makers get one; hobby projects don't. That's why:

- AARE's desktop head unit (`./headunit.sh`), which uses a self-signed certificate, works with
  AARE but **not with Google's Android Auto app**: the app hangs up during the TLS handshake. The
  head unit says so ("the phone refused the head unit's certificate").
- Open-source head units that work with Google's app do so with a Google-issued head-unit
  certificate taken from somewhere, or not at all.

## What head units do with the phone's certificate

This is what matters for AARE, and it varies:

| Head unit | Checks the phone's certificate? | AARE works? |
|---|---|---|
| Google's Desktop Head Unit (DHU) | yes, against the Google Automotive Link root | **no**: TLS completes, then `AUTH_COMPLETE` with status -3 |
| Factory head units built on Google's receiver library | believed to check like the DHU | most likely **no** |
| Denso Ten E-DA2 (factory unit, 2026 Toyota Land Cruiser 79 series) | no | **yes**, with AARE's self-signed certificate ([#37](https://github.com/prototux/AARE/issues/37)) |
| openauto / Crankshaft | no | yes |
| Headunit Revived | no | yes |
| LIVI, aa-proxy-rs | no | yes |
| Some aftermarket / OEM units | varies | some do |
| AARE's desktop head unit | no | yes |

"Believed to check" because the DHU is Google's reference implementation of the receiver
library, and the status codes for expired or not-yet-valid phone certificates only make sense if
head units validate the chain. The first factory unit tested (Denso Ten, in a Toyota) doesn't
check, so not every factory head unit does. Reports from real cars are welcome (see below).

## What AARE does

- **Its own certificates:**
  - Release builds carry **AARE's built-in certificate**: one RSA-2048 certificate and key
    (`O=AARE, CN=AARE phone`), the same in every install of a release, renewed from time to time.
    Like Google's, its key ships inside the app, so it identifies AARE rather than
    a phone, and it isn't secret. It's self-signed today; it exists so that, if head units ever
    accept certificates from other apps (a vendor or CA signing AARE's), releases can carry a signed
    one without changing anything else. Builds you make yourself get it only when `AARE_PHONE_CERT`
    and `AARE_PHONE_KEY` (PEM certificate, PEM PKCS#8 key) are set in the environment.
  - On first launch AARE also **generates a self-signed certificate** and key on the phone. It's the
    last fallback, and the only one in builds without the built-in certificate.
  - Head units that don't check the phone accept either; Google-library head units refuse both.
- **Importing a certificate:** under *Advanced > Phone certificates* you can import a certificate
  chain and key (PEM, or a PKCS#12 file) that you are entitled to use. AARE doesn't ship Google's
  certificate or key. *Download certificate* fetches the ones extracted from the latest Android
  Auto app from [iscle/android-auto-certificates](https://github.com/iscle/android-auto-certificates)
  (the files under `android-auto/latest/`), only when you tap it. That repository is a third party,
  so AARE keeps the download only if its chain contains the Google Automotive Link CA (pinned by
  SHA-256 `49e52efc…c4ad17f`), the CA signed the leaf, and the key signs data the leaf's public key
  verifies. A new download replaces the previous one; certificates you imported stay.
  [extract_key.py](../extract_key.py) gets the same files from an Android Auto APK.
- **Keeping it current:** a downloaded certificate expires a few months after the Android Auto
  build it comes from. With *Update the downloaded certificate automatically* on (off by default),
  AARE asks that repository for its version at most once a day, never while a car is connected,
  and downloads the new certificate only if it lasts longer than the Google-issued one on the
  phone; it then replaces the previous download and is offered first. Whether or not it is on,
  when the longest-lasting Google-issued certificate expires within two weeks, the home screen
  warns and one notification says so (another once it has expired), with a *Download certificate*
  action. These checks run when AARE opens and after each session.
- **Fallback order:** imported certificates first, then the built-in one, then the generated one;
  AARE offers them in turn. When a head unit
  refuses one (a failed handshake, or a non-zero `AUTH_COMPLETE`), the next connection offers
  the next one, and the connection log says so.
- **The head unit's certificate:** AARE doesn't require a Google-issued head-unit certificate
  (open-source head units can't have one). Instead it keeps, for each head unit, the SHA-256 of
  the certificates you trust, and refuses any other:
  - **Wireless:** a car you paired over Bluetooth is trusted on its first connection; bonding was
    your consent. A different certificate later is refused and asks for your *Trust*, like USB.
  - **USB and the developer head unit server:** a certificate AARE hasn't seen for that head unit
    is refused, and a *New head unit* notification shows its fingerprint. AARE's home screen shows
    the same *Trust this head unit* button, for when notifications are off. Only trust a head
    unit you are connected to right now, then plug in or connect again. This includes a known head unit with a new
    certificate (a software update): the head unit chooses the USB accessory strings AARE keys
    it on, so a new certificate always needs your tap.
  - **A known head unit with a different certificate** (wireless or USB) gets the same prompt,
    titled *Head unit certificate changed*: trust it only if you know its software was updated.
    The certificates trusted before stay trusted.
  - **A head unit that sends no certificate** is refused ("head unit sent no certificate"): there
    is nothing to trust, and it is not counted as the head unit refusing AARE's certificate.

  The trusted head units are listed under *Settings > Connection > Trusted head units*; *Forget*
  one whose software was replaced. What this protects: a USB host (a charging kiosk, a rigged
  cable) or a developer-server client can't become a trusted head unit without your *Trust* tap,
  and a known head unit can't silently change its certificate. It doesn't protect against a
  device you tapped *Trust* for, and wireless trust is only as good as the Bluetooth pairing: a
  device you bonded is trusted on its first connection without asking.

  **Head units with public keys:** the open-source head units (openauto, aasdk and the ones built
  on them) and Google's Desktop Head Unit ship a built-in certificate whose private key is
  published with their source or download. Trusting one of those certificates trusts everyone who
  has that key: anyone can then pose as that head unit to AARE, over the same USB accessory strings
  or the developer server. AARE can't tell those apart and doesn't try to (it keeps no list of
  known public keys). Pinning only stops a *new*, never-trusted certificate; to be protected,
  give your head unit a certificate of its own. AARE's desktop head unit makes one on its first run and
  keeps it in `~/.config/aare/headunit` (`%APPDATA%\aare\headunit` on Windows), so you tap
  *Trust* once; delete the folder to start over.

AARE's desktop head unit can also present a certificate you supply (`--hu-cert`, `--hu-key`),
for example to compare with Google's app.

## Finding out what happens with your car

1. Connect, then open AARE on the phone: *Advanced > Connection log*. It's kept across
   reconnections, so you can read it after a drive.
2. Look for these lines:

| Log line | Meaning |
|---|---|
| `TLS handshake did not complete (head unit rejected our certificate?)` | the head unit hung up during TLS: it most likely doesn't trust AARE's certificate |
| `head unit rejected authentication (status -3)` | TLS worked, then the head unit refused the certificate (`STATUS_AUTHENTICATION_FAILURE`) |
| `status -23` / `status -24` | the certificate isn't valid yet / has expired: check the phone's date, or the imported certificate's dates |
| `head unit rejected the certificate; next connection offers "…"` | AARE will try its next certificate on the next connection |
| `head unit certificate not trusted yet: tap Trust in the notification or in AARE, then reconnect` | AARE refused a USB or developer head unit it doesn't know yet: tap *Trust* on the *New head unit* notification, or *Trust this head unit* on AARE's home screen, then reconnect |
| `head unit certificate changed: tap Trust in the notification or in AARE only if its software was updated, then reconnect` | a known head unit showed a different certificate than before: tap *Trust* on the *Head unit certificate changed* notification only if you know its software changed |
| `head unit sent no certificate: AARE only connects to head units that present one` | the head unit offered no certificate during TLS, so AARE has nothing to trust; this is not the head unit refusing AARE's certificate |
| no TLS line at all | the problem is earlier (USB, Bluetooth, Wi-Fi): see the README's compatibility section |

3. With adb, `adb logcat | grep -iE 'aare|AareAap'` shows the same, with more detail.

### Status codes

From `aap/src/main/proto/aap_protobuf/shared/MessageStatus.proto`:

| Status | Name |
|---|---|
| 0 | success |
| -3 | `STATUS_AUTHENTICATION_FAILURE` |
| -23 | `STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID` |
| -24 | `STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED` |

## Reporting a car

Open an issue with:

- the car (make, model, year) or the head unit (brand, model, software version);
- USB or wireless;
- the connection-log lines above, or the `adb logcat` output around the connection;
- whether Google's Android Auto works with the same car.

That's how the table above grows.

#!/usr/bin/env python3
"""
extract_key.py - Offline Android Auto (com.google.android.projection.gearhead)
CarService RSA private-key extractor.

Runs entirely on the desktop against a downloaded APK / APKM / APKS / XAPK.
No phone, no ADB, no Frida, no rooted device, no JVM.

What it does
------------
1. Opens the bundle (.apkm/.apks/.xapk/.zip) or a plain .apk (or a raw
   classes*.dex file) and loads every DEX container inside it.
2. Scans each DEX for:
     * the CarService certificate PEM + Google Automotive Link CA PEM
       (plain strings in the DEX string table),
     * the two byte[] array payloads used by the app's key blob:
       the KDF salt (256 bytes in known builds) and the AES-256-CBC ciphertext
       (~1712 bytes in known builds; a multiple of 16)
       (Dalvik `array-payload` structures, ident 0x0300).
   The salt and ciphertext live back-to-back in the same <clinit>, which is
   used as a strong pairing heuristic.
3. Re-implements the app's KDF (`uga.bl` / `ivq` / `jep` in various versions):
       buf = zeros(48)
       mix(carServiceCertPem); mix(galCaCertPem); mix(buf) x7   (self-mix)
       AES-256 key = buf[0:32], IV = buf[32:48]
4. AES-256-CBC-decrypts the ciphertext (PKCS#7 padding), drops the 28-byte
   prefix, strips 26 bytes off the tail and decodes the remainder with the
   AOSP android.util.Base64 decoder semantics (skip-filtering decoder).
5. Parses/validates the resulting PKCS#8 RSA key (version, n = p*q) and
   checks that its modulus matches the CarService certificate.
6. Writes carservice_key.pem, carservice_cert.pem, gal_ca.pem.

Dependencies: none (cryptography/pycryptodome are used opportunistically if
installed, otherwise a built-in pure-Python AES is used).

Usage:
    python3 tools/extract_key.py com.google.android.projection.gearhead_*.apkm
    python3 tools/extract_key.py base.apk -o out/ --dump-bins
    python3 tools/extract_key.py classes.dex --stdout > key.pem
    python3 tools/extract_key.py --self-test
"""

from __future__ import annotations

import argparse
import base64 as py_base64
import json
import datetime as _dt
import io
import os
import re
import struct
import sys
import zipfile

# ---------------------------------------------------------------------------
# AES (pure python, textbook inverse cipher). Only used when neither
# `cryptography` nor `pycryptodome` is importable; it is still verified
# against them by `--self-test`.
# ---------------------------------------------------------------------------


def _xtime(a: int) -> int:
    return ((a << 1) ^ 0x1B) & 0xFF if (a & 0x80) else ((a << 1) & 0xFF)


def _gmul(a: int, b: int) -> int:
    r = 0
    while b:
        if b & 1:
            r ^= a
        a = _xtime(a)
        b >>= 1
    return r


def _build_sboxes():
    exp = [0] * 512
    log = [0] * 256
    v = 1
    for i in range(255):
        exp[i] = v
        log[v] = i
        v = _xtime(v) ^ v  # v *= 3
    for i in range(255, 512):
        exp[i] = exp[i - 255]
    sbox = [0] * 256
    inv_sbox = [0] * 256
    for i in range(1, 256):
        # multiplicative inverse = 3^(255 - log[i])
        inv = exp[255 - log[i]]
        s = (
            inv
            ^ ((inv << 1) | (inv >> 7)) & 0xFF
            ^ ((inv << 2) | (inv >> 6)) & 0xFF
            ^ ((inv << 3) | (inv >> 5)) & 0xFF
            ^ ((inv << 4) | (inv >> 4)) & 0xFF
        ) ^ 0x63
        sbox[i] = s & 0xFF
    sbox[0] = 0x63
    for i, s in enumerate(sbox):
        inv_sbox[s] = i
    return sbox, inv_sbox


_SBOX, _INV_SBOX = _build_sboxes()


class PureAES:
    """AES-128/192/256 block cipher, encrypt and decrypt."""

    def __init__(self, key: bytes):
        if len(key) not in (16, 24, 32):
            raise ValueError("bad AES key length")
        self.Nk = len(key) // 4
        self.Nr = self.Nk + 6
        self.rk = self._expand(key)

    def _expand(self, key: bytes):
        Nk, Nr = self.Nk, self.Nr
        w = [list(key[4 * i : 4 * i + 4]) for i in range(Nk)]
        rcon = 1
        for i in range(Nk, 4 * (Nr + 1)):
            temp = list(w[i - 1])
            if i % Nk == 0:
                temp = temp[1:] + temp[:1]  # RotWord
                temp = [_SBOX[b] for b in temp]  # SubWord
                temp[0] ^= rcon
                rcon = _xtime(rcon)
            elif Nk > 6 and i % Nk == 4:
                temp = [_SBOX[b] for b in temp]
            w.append([w[i - Nk][j] ^ temp[j] for j in range(4)])
        return [bytes(b for word in w[4 * r : 4 * r + 4] for b in word) for r in range(Nr + 1)]

    # state index convention: index = 4*col + row
    @staticmethod
    def _sub(state, table):
        return bytearray(table[b] for b in state)

    @staticmethod
    def _shift_rows(state, inv=False):
        out = bytearray(16)
        for r in range(4):
            for c in range(4):
                src = (c - r) % 4 if inv else (c + r) % 4
                out[4 * c + r] = state[4 * src + r]
        return out

    @staticmethod
    def _mix_columns(state):
        out = bytearray(16)
        for c in range(4):
            a = [state[4 * c + r] for r in range(4)]
            out[4 * c + 0] = _gmul(a[0], 2) ^ _gmul(a[1], 3) ^ a[2] ^ a[3]
            out[4 * c + 1] = a[0] ^ _gmul(a[1], 2) ^ _gmul(a[2], 3) ^ a[3]
            out[4 * c + 2] = a[0] ^ a[1] ^ _gmul(a[2], 2) ^ _gmul(a[3], 3)
            out[4 * c + 3] = _gmul(a[0], 3) ^ a[1] ^ a[2] ^ _gmul(a[3], 2)
        return out

    @staticmethod
    def _inv_mix_columns(state):
        out = bytearray(16)
        for c in range(4):
            a = [state[4 * c + r] for r in range(4)]
            # InvMixColumns matrix rows: (0E 0B 0D 09) and its rotations
            out[4 * c + 0] = (
                _gmul(a[0], 0x0E) ^ _gmul(a[1], 0x0B) ^ _gmul(a[2], 0x0D) ^ _gmul(a[3], 0x09)
            )
            out[4 * c + 1] = (
                _gmul(a[0], 0x09) ^ _gmul(a[1], 0x0E) ^ _gmul(a[2], 0x0B) ^ _gmul(a[3], 0x0D)
            )
            out[4 * c + 2] = (
                _gmul(a[0], 0x0D) ^ _gmul(a[1], 0x09) ^ _gmul(a[2], 0x0E) ^ _gmul(a[3], 0x0B)
            )
            out[4 * c + 3] = (
                _gmul(a[0], 0x0B) ^ _gmul(a[1], 0x0D) ^ _gmul(a[2], 0x09) ^ _gmul(a[3], 0x0E)
            )
        return out

    @staticmethod
    def _add_round_key(state, rk):
        return bytearray(state[i] ^ rk[i] for i in range(16))

    def encrypt_block(self, block: bytes) -> bytes:
        state = bytearray(block)
        state = self._add_round_key(state, self.rk[0])
        for rnd in range(1, self.Nr):
            state = self._sub(state, _SBOX)
            state = self._shift_rows(state)
            state = self._mix_columns(state)
            state = self._add_round_key(state, self.rk[rnd])
        state = self._sub(state, _SBOX)
        state = self._shift_rows(state)
        return bytes(self._add_round_key(state, self.rk[self.Nr]))

    def decrypt_block(self, block: bytes) -> bytes:
        state = bytearray(block)
        state = self._add_round_key(state, self.rk[self.Nr])
        for rnd in range(self.Nr - 1, 0, -1):
            # FIPS-197 figure 18 (inverse cipher): AddRoundKey comes before InvMixColumns
            state = self._shift_rows(state, inv=True)
            state = self._sub(state, _INV_SBOX)
            state = self._add_round_key(state, self.rk[rnd])
            state = self._inv_mix_columns(state)

        state = self._shift_rows(state, inv=True)
        state = self._sub(state, _INV_SBOX)
        return bytes(self._add_round_key(state, self.rk[0]))


class AESProvider:
    """AES-256-CBC helper with three interchangeable backends."""

    def __init__(self):
        self.backend = "python"
        self._cache: dict[bytes, object] = {}
        # Set AAKEY_AES_BACKEND=python to force the built-in implementation.
        force = os.environ.get("AAKEY_AES_BACKEND", "auto").lower()
        if force == "python":
            return
        try:
            from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes  # noqa: F401

            self._Cipher = Cipher
            self._algorithms = algorithms
            self._modes = modes
            self.backend = "cryptography"
        except Exception:
            try:
                from Crypto.Cipher import AES as _AES  # noqa: F401

                self._AES = _AES
                self.backend = "pycryptodome"
            except Exception:
                pass

    def _mk(self, key: bytes):
        if key in self._cache:
            return self._cache[key]
        if self.backend == "cryptography":
            obj = ("cryptography", self._Cipher(self._algorithms.AES(key), self._modes.ECB()))
        elif self.backend == "pycryptodome":
            obj = ("pycryptodome", self._AES.new(key, self._AES.MODE_ECB))
        else:
            obj = ("python", PureAES(key))
        self._cache[key] = obj
        return obj

    def ecb_decrypt(self, key: bytes, data: bytes) -> bytes:
        kind, obj = self._mk(key)
        if kind == "cryptography":
            dec = obj.decryptor()
            return dec.update(data) + dec.finalize()
        if kind == "pycryptodome":
            return obj.decrypt(data)
        out = bytearray()
        for i in range(0, len(data), 16):
            out += obj.decrypt_block(data[i : i + 16])
        return bytes(out)

    def ecb_encrypt(self, key: bytes, data: bytes) -> bytes:
        kind, obj = self._mk(key)
        if kind == "cryptography":
            enc = obj.encryptor()
            return enc.update(data) + enc.finalize()
        if kind == "pycryptodome":
            return obj.encrypt(data)
        out = bytearray()
        for i in range(0, len(data), 16):
            out += obj.encrypt_block(data[i : i + 16])
        return bytes(out)

    def cbc_decrypt(self, key: bytes, iv: bytes, data: bytes) -> bytes:
        """AES-CBC decrypt; len(data) must be a multiple of 16."""
        assert len(data) % 16 == 0, "CBC input not block aligned"
        ecb = self.ecb_decrypt(key, data)
        out = bytearray(ecb)
        prev = iv
        for i in range(0, len(ecb), 16):
            blk = data[i : i + 16]
            for j in range(16):
                out[i + j] ^= prev[j]
            prev = blk
        return bytes(out)

    def cbc_encrypt(self, key: bytes, iv: bytes, data: bytes) -> bytes:
        """AES-CBC encrypt with PKCS#7-free raw blocks (len % 16 == 0)."""
        assert len(data) % 16 == 0, "CBC input not block aligned"
        out = bytearray()
        prev = iv
        for i in range(0, len(data), 16):
            blk = bytes(a ^ b for a, b in zip(data[i : i + 16], prev))
            blk = self.ecb_encrypt(key, blk)
            out += blk
            prev = blk
        return bytes(out)


# ---------------------------------------------------------------------------
# AOSP android.util.Base64 decoder (skip-filtering), ported from
# frameworks/base/core/java/android/util/Base64.java (Apache-2.0).
# Same semantics as ExtractKey.java's aospBase64Decode().
# ---------------------------------------------------------------------------

SKIP = -1
EQUALS = -2

_DECODE = [
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63,
    52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -2, -1, -1,
    -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
    15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1,
    -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
    41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
]
# The table above covers ASCII 0x00-0x7F. Android's table covers the full
# byte range; everything >= 0x80 is SKIP.


def aosp_base64_decode(data: bytes) -> bytes | None:
    out = bytearray(len(data) * 3 // 4 + 10)
    op = 0

    def emit(n):  # ensure room
        while len(out) < op + n:
            out.append(0)

    state = 0
    value = 0
    for c in data:
        d = _DECODE[c] if c < 0x80 else SKIP
        if state == 0:
            if d >= 0:
                value, state = d, 1
            elif d != SKIP:
                return None
        elif state == 1:
            if d >= 0:
                value, state = (value << 6) | d, 2
            elif d != SKIP:
                return None
        elif state == 2:
            if d >= 0:
                value, state = (value << 6) | d, 3
            elif d == EQUALS:
                emit(1)
                out[op] = value >> 4
                op += 1
                state = 4
            elif d != SKIP:
                return None
        elif state == 3:
            if d >= 0:
                value = (value << 6) | d
                emit(3)
                out[op + 2] = value & 0xFF
                out[op + 1] = (value >> 8) & 0xFF
                out[op] = (value >> 16) & 0xFF
                op += 3
                state = 0
            elif d == EQUALS:
                emit(2)
                out[op + 1] = (value >> 2) & 0xFF
                out[op] = (value >> 10) & 0xFF
                op += 2
                state = 5
            elif d != SKIP:
                return None
        elif state == 4:
            if d == EQUALS:
                state = 5
            elif d != SKIP:
                return None
        elif state == 5:
            if d != SKIP:
                return None
    if state == 1 or state == 4:
        return None
    if state == 2:
        emit(1)
        out[op] = value >> 4
        op += 1
    elif state == 3:
        emit(2)
        out[op] = (value >> 10) & 0xFF
        out[op + 1] = (value >> 2) & 0xFF
        op += 2
    return bytes(out[:op])


# ---------------------------------------------------------------------------
# The app's KDF (uga.bl / ivq / jep).  See tools/decrypt_key_from_apk.md.
# ---------------------------------------------------------------------------


def _make_tables(salt: bytes):
    """tables[j][v] == ((v>>7 | v+v) + 33) ^ salt[j % len(salt)], low 8 bits."""
    n = len(salt)
    return [
        [((((v >> 7) | (v + v)) + 33) ^ salt[j % n]) & 0xFF for v in range(256)]
        for j in range(48)
    ]


def tweak(src: bytes, buf: bytearray, tables) -> None:
    """Port of the app's `uga.bl(byte[] src, byte[] buf, byte[] salt)`: for every
    source byte, every one of the 48 accumulator slots is rotated, mixed with
    the salt and XORed with the source byte.

    NOTE: src[i] is read *inside* the inner loop.  JADX decompiles this method as
    `for (byte b : src)`, which would hoist the read out of the loop; that is a
    decompiler artefact and it changes the result when src and buf are the very
    same array (the seven self-mixing rounds do exactly that).  The original
    bytecode reads src[i] per inner iteration - verified by deriving a valid
    PKCS#8 key from a real APK, and matching tools/ExtractKey.java.
    """
    if src is buf:  # self-mixing round: source byte must be re-read per inner step
        n = len(src)
        for i in range(n):
            for j in range(48):
                buf[j] = tables[j][buf[j]] ^ (src[i] & 0xFF)
        return
    for b in src:
        for j in range(48):
            buf[j] = tables[j][buf[j]] ^ (b & 0xFF)


def derive_key_material(salt: bytes, cert1: bytes, cert2: bytes):
    """buf = 0; tweak(cert1); tweak(cert2); tweak(buf) x7 ; key = buf[0:32], IV = buf[32:48]"""
    buf = bytearray(48)
    tables = _make_tables(salt)
    tweak(cert1, buf, tables)
    tweak(cert2, buf, tables)
    for _ in range(7):
        tweak(buf, buf, tables)
    return bytes(buf[0:32]), bytes(buf[32:48])


# ---------------------------------------------------------------------------
# Minimal ASN.1 (enough for PKCS#8 keys and X.509 certificates)
# ---------------------------------------------------------------------------


class ASN1Error(ValueError):
    pass


def xor16(a: bytes, b: bytes) -> bytes:
    return bytes(x ^ y for x, y in zip(a, b))


def asn1_tlv(data: bytes, i: int = 0):
    """Return (tag:int, value:bytes, next_index:int)."""
    if i >= len(data):
        raise ASN1Error("truncated ASN.1")
    tag = data[i]
    i += 1
    if tag & 0x1F == 0x1F:  # high tag number form
        t = tag
        while True:
            if i >= len(data):
                raise ASN1Error("truncated ASN.1 tag")
            b = data[i]
            i += 1
            t = (t << 8) | b
            if not (b & 0x80):
                break
        tag = t
    ln = data[i]
    i += 1
    if ln & 0x80:
        n = ln & 0x7F
        if n == 0 or n > 4:
            raise ASN1Error("bad ASN.1 length")
        ln = int.from_bytes(data[i : i + n], "big")
        i += n
    if i + ln > len(data):
        raise ASN1Error("truncated ASN.1 value")
    return tag, data[i : i + ln], i + ln


def asn1_children(data: bytes):
    """Yield (tag, value) for each child of a constructed value."""
    i = 0
    while i < len(data):
        tag, val, i = asn1_tlv(data, i)
        yield tag, val


def asn1_parse(data: bytes, depth: int = 0):
    """Parse one TLV; constructed values become (tag, [children...])."""
    tag, val, _ = asn1_tlv(data)
    return _parse_node(tag, val, depth)


def _parse_node(tag: int, val: bytes, depth: int):
    if (tag & 0x20) and depth < 40:
        return (tag, [_parse_node(t2, v2, depth + 1) for t2, v2 in asn1_children(val)])
    return (tag, val)


def der_int(b: bytes) -> int:
    return int.from_bytes(b, "big")


def parse_pkcs8(der: bytes):
    """Return dict with n,e,d,p,q and sanity-check results."""
    seq = asn1_parse(der)
    if seq[0] != 0x30:
        raise ASN1Error("not a PKCS#8 SEQUENCE")
    fields = seq[1]
    if len(fields) < 3:
        raise ASN1Error("short PKCS#8")
    if fields[0][0] != 0x02 or der_int(fields[0][1]) != 0:
        raise ASN1Error("bad PKCS#8 version")
    rsakey = None
    for tag, val in fields:
        if tag == 0x04:  # OCTET STRING
            rsakey = asn1_parse(val)
            break
    if rsakey is None or rsakey[0] != 0x30:
        raise ASN1Error("no RSAPrivateKey inside PKCS#8")
    ints = [der_int(v) for tag, v in rsakey[1] if tag == 0x02]
    if len(ints) < 9:
        raise ASN1Error("unexpected RSAPrivateKey field count %d" % len(ints))
    version, n, e, d, p, q = ints[0], ints[1], ints[2], ints[3], ints[4], ints[5]
    info = {
        "version": version,
        "n": n,
        "e": e,
        "d": d,
        "p": p,
        "q": q,
        "n_bits": n.bit_length(),
        "consistent": (p * q == n and 0 < d < n and e > 1),
    }
    return info


RSA_ENCRYPTION_OID = "1.2.840.113549.1.1.1"


def _find_spki(tbs_children):
    """Locate the SubjectPublicKeyInfo (RSA) inside TBSCertificate children."""
    for tag, val in tbs_children:
        if tag != 0x30:                     # SEQUENCE
            continue
        inner = list(asn1_children(val))
        if len(inner) != 2:
            continue
        alg_tag, alg_val = inner[0]
        bits_tag, bits_val = inner[1]
        if alg_tag != 0x30 or bits_tag != 0x03:   # AlgorithmIdentifier, BIT STRING
            continue
        algs = list(asn1_children(alg_val))
        if not algs or algs[0][0] != 0x06:
            continue
        oid = ".".join(str(x) for x in _oid_bytes(algs[0][1]))
        if oid != RSA_ENCRYPTION_OID:
            continue
        rt_tag, rt_val, _ = asn1_tlv(bits_val[1:])   # skip unused-bits byte
        if rt_tag != 0x30:
            continue
        nums = [der_int(v) for t2, v in asn1_children(rt_val) if t2 == 0x02]
        if not nums:
            continue
        return {"oid": oid, "modulus": nums[0], "exponent": nums[1] if len(nums) > 1 else 3}
    return None


def _oid_bytes(b: bytes):
    # minimal OID decoder
    nums = []
    first = b[0]
    nums.append(first // 40)
    nums.append(first % 40)
    v = 0
    for c in b[1:]:
        v = (v << 7) | (c & 0x7F)
        if not (c & 0x80):
            nums.append(v)
            v = 0
    return nums


def parse_cert(der: bytes):
    tag, cert_body, _ = asn1_tlv(der)
    if tag != 0x30:
        raise ASN1Error("not a certificate")
    top = list(asn1_children(cert_body))
    if not top or top[0][0] != 0x30:
        raise ASN1Error("bad TBSCertificate")
    children = list(asn1_children(top[0][1]))
    names = {}
    dates = {}
    # subject is index 5 (when [0]version present) -> locate by walking
    def read_name(val):
        out = {}
        for _tag, rdn in asn1_children(val):
            for _tag2, atv in asn1_children(rdn):
                parts = list(asn1_children(atv))
                if len(parts) == 2 and parts[0][0] == 0x06:
                    oid = ".".join(str(x) for x in _oid_bytes(parts[0][1]))
                    out[oid] = parts[1][1].decode("utf-8", "replace")
        return out

    # TBSCertificate: [0]version, serial, sig, issuer, validity, subject, spki, ...
    idx = 1 if children and children[0][0] == 0xA0 else 0
    if len(children) > idx + 3:
        validity = list(asn1_children(children[idx + 3][1]))
        if len(validity) == 2:
            dates["notBefore"] = _parse_time(*validity[0])
            dates["notAfter"] = _parse_time(*validity[1])
    try:
        names["issuer"] = read_name(children[idx + 2][1])
        names["subject"] = read_name(children[idx + 4][1])
    except (ASN1Error, IndexError):
        pass
    spki = _find_spki(children)
    return {"names": names, "dates": dates, "spki": spki}


def _parse_time(tag: int, v: bytes):
    s = v.decode("ascii", "replace")
    try:
        if tag == 0x17:
            return _dt.datetime.strptime(s, "%y%m%d%H%M%SZ").replace(tzinfo=_dt.timezone.utc)
        return _dt.datetime.strptime(s, "%Y%m%d%H%M%SZ").replace(tzinfo=_dt.timezone.utc)
    except ValueError:
        return None


def pem_encode(der: bytes, label: str) -> str:
    body = py_base64.encodebytes(der).decode("ascii")
    return "-----BEGIN %s-----\n%s-----END %s-----\n" % (label, body, label)


# ---------------------------------------------------------------------------
# DEX scanning
# ---------------------------------------------------------------------------

PEM_BEGIN = b"-----BEGIN CERTIFICATE-----"
PEM_END = b"-----END CERTIFICATE-----"


def bundle_description(path: str) -> str | None:
    """APKMirror/APKPure metadata (info.json) when present, for provenance."""
    try:
        with zipfile.ZipFile(path) as zf:
            for name in ("info.json", "bundle.pb", "manifest.pb"):
                if name in zf.namelist():
                    try:
                        info = json.loads(zf.read("info.json"))
                    except Exception:
                        continue
                    parts = [info.get("app_name"), info.get("apk_title"),
                             info.get("release_version"), info.get("variant")]
                    text = " | ".join(p for p in parts if p)
                    if text:
                        return text
    except Exception:
        return None
    return None


def load_dex_blobs(path: str, max_depth: int = 3):
    """Yield (label, bytes) for every classes*.dex reachable from `path`."""
    st = os.stat(path)
    if os.path.isdir(path):
        out = []
        for name in sorted(os.listdir(path)):
            out += load_dex_blobs(os.path.join(path, name), max_depth)
        return out
    if path.lower().endswith(".dex"):
        with open(path, "rb") as fh:
            return [(os.path.basename(path), fh.read())]
    return _from_zip(path, "", max_depth)


def _from_zip(path: str, prefix: str, depth: int):
    out = []
    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        return out
    with zf:
        names = zf.namelist()
        for name in names:
            if _is_dex_name(name):
                data = zf.read(name)
                if data[:4] == b"dex\n" or data[:4] == b"cdex":
                    out.append((prefix + "/" + name if prefix else name, data))
        if depth > 0:
            for name in names:
                if re.search(r"\.(apk|apks|apkm|xapk|zip|jar)$", name, re.I) and not _is_dex_name(name):
                    blob = zf.read(name)
                    out += _from_zip(io.BytesIO(blob), prefix + "/" + name if prefix else name, depth - 1)
    return out


def _is_dex_name(name: str) -> bool:
    base = name.rsplit("/", 1)[-1]
    return bool(re.fullmatch(r"classes(\d+)?\.dex", base))


def find_cert_strings(dex: bytes):
    """All PEM certificates stored as strings in a DEX, exactly as the app
    sees them (string_data_item content, including the trailing newline)."""
    certs = []
    seen = set()
    for m in re.finditer(re.escape(PEM_BEGIN), dex):
        end = dex.find(PEM_END, m.start())
        if end < 0:
            continue
        nul = dex.find(b"\x00", end)
        if nul < 0:
            continue
        s = dex[m.start() : nul]
        if s not in seen:
            seen.add(s)
            certs.append((m.start(), s))
    return certs


def find_byte_arrays(dex: bytes, min_size: int = 64, max_size: int = 1 << 20):
    """All Dalvik `array-payload` structures holding 1-byte elements.

    Layout: u16 ident (0x0300), u16 element_size (1), u32 size, data.
    """
    out = []
    for m in re.finditer(b"\x00\x03\x01\x00", dex):
        off = m.start()
        if off & 1:  # code units are 2-byte aligned
            continue
        size = struct.unpack_from("<I", dex, off + 4)[0]
        if not (min_size <= size <= max_size):
            continue
        if off + 8 + size > len(dex):
            continue
        out.append((off, size, dex[off + 8 : off + 8 + size]))
    return out


def cert_der(pem: bytes) -> bytes | None:
    """Decode a PEM blob to DER."""
    try:
        text = pem.decode("ascii")
    except UnicodeDecodeError:
        return None
    b64 = "".join(
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.startswith("-----")
    )
    try:
        return py_base64.b64decode(b64 + "=" * (-len(b64) % 4), validate=False)
    except Exception:
        return None


def cert_subject_text(der: bytes) -> str:
    """Human-readable subject (values joined) for classification/logging."""
    try:
        info = parse_cert(der)
        subject = info["names"].get("subject", {})
        return " ".join(subject.values())
    except Exception:
        return ""


# ---------------------------------------------------------------------------
# Extraction driver
# ---------------------------------------------------------------------------


class Candidate:
    def __init__(self, dex_label, salt_off, cert_off, salt, enc):
        self.dex = dex_label
        self.salt_off = salt_off
        self.cert_off = cert_off
        self.salt = salt
        self.enc = enc

    def __repr__(self):
        return "<salt@%d(%dB)+enc@%d(%dB) in %s>" % (
            self.salt_off,
            len(self.salt),
            self.cert_off,
            len(self.enc),
            self.dex,
        )


def find_candidates(dex_label, arrays, prefer_adjacent=True):
    """Pick out the (salt, AES ciphertext) blob pair.

    In every known build the two `byte[]` initialisers are emitted back to back
    in one `array-payload` chain, so an adjacency test (the second payload starts
    right after the first one's data, +/- the 2-byte alignment padding) finds
    them without knowing the class or field names, and without assuming the salt
    is exactly 256 bytes. `salts`/`ciphers` are the looser per-size candidate
    lists used by the brute-force fallback.
    """
    salts = [a for a in arrays if a[1] == 256]
    ciphers = [a for a in arrays if a[1] >= 512 and a[1] % 16 == 0]
    pairs = []
    if prefer_adjacent:
        by_off = {off: (off, size, data) for off, size, data in arrays}
        for off, size, data in arrays:
            for delta in (8 + size, 8 + size + 2):   # 2-byte alignment padding
                nxt = by_off.get(off + delta)
                if nxt is not None and nxt[1] >= 512 and nxt[1] % 16 == 0:
                    pairs.append(Candidate(dex_label, off, nxt[0], data, nxt[2]))
    return pairs, salts, ciphers


def try_extract(aes: AESProvider, cand: Candidate, cert_a: bytes, cert_b: bytes):
    for order, (first, second) in enumerate([(cert_a, cert_b), (cert_b, cert_a)]):
        key, iv = derive_key_material(cand.salt, first, second)
        # Cheap rejection: decrypt only the final CBC block and check PKCS#7.
        enc = cand.enc
        p_last = xor16(aes.ecb_decrypt(key, enc[-16:]), enc[-32:-16])
        pad = p_last[-1]
        if not (1 <= pad <= 16):
            continue
        plain = aes.cbc_decrypt(key, iv, enc)
        if plain[-pad:] != bytes([pad]) * pad:
            continue
        plain = plain[:-pad]
        if len(plain) < 55:
            continue
        mid = plain[28 : len(plain) - 26]
        der = aosp_base64_decode(mid)
        if not der:
            continue
        try:
            info = parse_pkcs8(der)
        except Exception:
            continue
        if not info["consistent"]:
            continue
        return der, info, order
    return None


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

GAL_CA_PEM = (
    "-----BEGIN CERTIFICATE-----\n"
    "MIIDiTCCAnGgAwIBAgIJAMFO56WkVE1CMA0GCSqGSIb3DQEBBQUAMFsxCzAJBgNV\n"
    "BAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBW\n"
    "aWV3MR8wHQYDVQQKDBZHb29nbGUgQXV0b21vdGl2ZSBMaW5rMB4XDTE0MDYwNjE4\n"
    "MjgxOVoXDTQ0MDYwNTE4MjgxOVowWzELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNh\n"
    "bGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxHzAdBgNVBAoMFkdvb2ds\n"
    "ZSBBdXRvbW90aXZlIExpbmswggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIB\n"
    "AQDUH+iIbwwVb74NdI5eBv/ACFmh4ml/NOW7gUVWdYX50n8uQQsHHLCNIhk5VV2H\n"
    "hanvAZ/XXHPuVAPadE2HpnNqePKF/RDo4eJo/+rOief8gBYq/Z+OQTZeLdNm+GoI\n"
    "HBrEjU4Ms8IdLuFW0jF8LlIRgekjLHpc7duUl3QpwBlmAWQK40T/SZjprlmhyqfJ\n"
    "g1rxFdnGbrSibmCsTmb3m6WZyZUyrcwmd7t6q3pHbMABO+o02asPG/YPj/SJo4+i\n"
    "fb5/Nk56f3hH9pBiPKQXJnVUdVLKMXSRgydDBsGSBol4C0JL77MNDrMR5jdafJ4j\n"
    "mWmsa2+mnzoAv9AxEL9T0LiNAgMBAAGjUDBOMB0GA1UdDgQWBBS5dqvv8DPQiwrM\n"
    "fgn8xKR91k7wgjAfBgNVHSMEGDAWgBS5dqvv8DPQiwrMfgn8xKR91k7wgjAMBgNV\n"
    "HRMEBTADAQH/MA0GCSqGSIb3DQEBBQUAA4IBAQDKcnBsrbB0Jbz2VGJKP2lwYB6P\n"
    "dCTCCpQu7dVp61UQOX+zWfd2hnNMnLs/r1xPO+eyN0vmw7sD05phaIhbXVauKWZi\n"
    "9WqWHTaR+9s6CTyBOc1Mye0DMj+4vHt+WLmf0lYjkYUVYvR1EImX8ktXzkVmOqn+\n"
    "e30siqlZ8pQpsOgegIKfJ+pNQM8c3eXVv3KFMUgjZW33SziZL8IMsLvSO+1LtH37\n"
    "KqbTEMP6XUwVuZopgGvaHU74eT/WSRGlL7vX4OL5/UXXP4qsGH2Zp7uQlErv4H9j\n"
    "kMs37UL1vGb4M8RM7Eyu9/RulepSmqZUF+3i+3eby8iGq/3OWk9wgJf7AXnx\n"
    "-----END CERTIFICATE-----\n"
).encode("utf-8")


def self_test(aes: AESProvider) -> bool:
    """Validate the built-in pure-python AES against published FIPS-197 vectors,
    and cross-check it against the installed crypto library when available."""
    ok = True
    vectors = [
        # key, plaintext, ciphertext  (FIPS-197 appendix B/C.2 + NIST SP800-38A style)
        (bytes.fromhex("000102030405060708090a0b0c0d0e0f"),
         bytes.fromhex("00112233445566778899aabbccddeeff"),
         "69c4e0d86a7b0430d8cdb78070b4c55a"),
        (bytes.fromhex("000102030405060708090a0b0c0d0e0f1011121314151617"),
         bytes.fromhex("00112233445566778899aabbccddeeff"),
         "dda97ca4864cdfe06eaf70a0ec0d7191"),
        (bytes.fromhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
         bytes.fromhex("00112233445566778899aabbccddeeff"),
         "8ea2b7ca516745bfeafc49904b496089"),
    ]
    for key, pt, expect in vectors:
        ref = PureAES(key)
        got = ref.encrypt_block(pt)
        if got.hex() != expect:
            print("  AES-%d encrypt vector FAILED: %s (want %s)" % (len(key) * 8, got.hex(), expect))
            ok = False
        if ref.decrypt_block(bytes.fromhex(expect)) != pt:
            print("  AES-%d decrypt vector FAILED" % (len(key) * 8,))
            ok = False
    # CBC roundtrip through the selected backend
    key, iv = bytes(range(32)), bytes(range(16))
    data = bytes((i * 7) % 256 for i in range(112))
    cbc = aes.cbc_encrypt(key, iv, data)
    if aes.cbc_decrypt(key, iv, cbc) != data:
        print("  CBC roundtrip FAILED (backend=%s)" % aes.backend)
        ok = False
    # pure-python CBC must agree with the library backend
    py = AESProvider.__new__(AESProvider)
    py.backend = "python"
    py._cache = {}
    if aes.backend != "python":
        if aes.ecb_encrypt(key, data) != py.ecb_encrypt(key, data):
            print("  pure-python ECB differs from %s" % aes.backend)
            ok = False
        if py.cbc_encrypt(key, iv, data) != cbc:
            print("  pure-python CBC differs from %s" % aes.backend)
            ok = False
    print("AES self-test: %s (backend=%s)" % ("OK" if ok else "FAILED", aes.backend))
    return ok


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Extract the Android Auto CarService RSA private key from an "
        "APK / APKM / APKS / XAPK / classes*.dex - offline, no phone required.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    ap.add_argument("input", nargs="?", help="path to .apkm/.apks/.apk/classes.dex")
    ap.add_argument("-o", "--out-dir", default=".", help="directory for output files")
    ap.add_argument("--key-name", default="carservice_key.pem")
    ap.add_argument("--cert-name", default="carservice_cert.pem")
    ap.add_argument("--ca-name", default="gal_ca.pem")
    ap.add_argument("--dump-bins", action="store_true", help="also write salt.bin / enckey.bin")
    ap.add_argument("--stdout", action="store_true",
                    help="print the private key PEM to stdout (all diagnostics go to stderr)")
    ap.add_argument("--self-test", action="store_true", help="run AES self-test and exit")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)

    # With --stdout the key is the only thing allowed on stdout.
    def report(*a, **kw):
        if args.stdout:
            kw.setdefault("file", sys.stderr)
        print(*a, **kw)

    aes = AESProvider()
    if args.self_test:
        return 0 if self_test(aes) else 1
    if not args.input:
        ap.error("an input file is required (or use --self-test)")

    src = os.path.abspath(args.input)
    if not os.path.exists(src):
        report("error: no such file: %s" % src)
        return 2

    report("Reading dex blobs from %s" % os.path.basename(src))
    desc = bundle_description(src) if os.path.isfile(src) else None
    if desc:
        report("  bundle: %s" % desc)
    blobs = load_dex_blobs(src)
    if not blobs:
        report("error: no classes*.dex found in %s" % src)
        return 1
    for label, data in blobs:
        report("  %-44s %9d bytes" % (label, len(data)))

    # ---- certificates --------------------------------------------------------
    certs = []
    for label, data in blobs:
        for off, pem in find_cert_strings(data):
            der = cert_der(pem)
            subject = cert_subject_text(der) if der else ""
            certs.append({"dex": label, "off": off, "pem": pem, "der": der,
                          "subject": subject, "is_gal": "Google Automotive Link" in subject})
            if args.verbose:
                report("  cert in %s @%d %r (%d bytes PEM)" % (label, off, subject, len(pem)))
    gal = [c for c in certs if c["is_gal"] and "CarService" not in c["subject"]]
    cars = [c for c in certs if not c["is_gal"]]
    report("Found %d embedded certificate(s): %d car-service-like, %d GAL-CA-like"
           % (len(certs), len(cars), len(gal)))
    if not cars:
        report("error: no CarService-looking certificate found - is this an Android Auto APK?")
        return 1

    # ---- byte-array payloads (salt + AES blob) -------------------------------
    all_salts, all_ciphers = [], []
    seen_pair = set()
    dedup_pairs = []
    for label, data in blobs:
        arrays = find_byte_arrays(data)
        pairs, salts, ciphers = find_candidates(label, arrays)
        if args.verbose:
            report("  %s: %d array payload(s), %d salt-like, %d blob-like, %d adjacent pair(s)"
                   % (label, len(arrays), len(salts), len(ciphers), len(pairs)))
        for p in pairs:
            key = (p.salt, p.enc)
            if key not in seen_pair:
                seen_pair.add(key)
                dedup_pairs.append(p)
        all_salts.extend((label, s) for s in salts)
        all_ciphers.extend((label, c) for c in ciphers)

    gal_pem_bytes = gal[0]["pem"] if gal else GAL_CA_PEM

    def attempt(cand):
        """Try one (salt, blob) pair against every candidate CarService cert."""
        for car in cars:
            res = try_extract(aes, cand, car["pem"], gal_pem_bytes)
            if res:
                return res, car
        return None, None

    result, car, cand = None, None, None
    for p in dedup_pairs:
        result, car = attempt(p)
        if result:
            cand = p
            break
    if not cand and args.verbose:
        report("adjacency heuristic found no usable pair; %d salt(s), %d blob(s) total"
               % (len(all_salts), len(all_ciphers)))

    # Fallback: full salt x blob cross-product across all dex files (slower).
    if not result:
        report("Brute forcing %d salt(s) x %d blob(s)..." % (len(all_salts), len(all_ciphers)))
        tried = set()
        for lbl, (soff, _ss, sdata) in all_salts:
            for _lbl, (coff, csize, cdata) in all_ciphers:
                if (sdata, csize) in tried:
                    continue
                tried.add((sdata, csize))
                guess = Candidate(lbl, soff, coff, sdata, cdata)
                result, car = attempt(guess)
                if result:
                    cand = guess
                    break
            if result:
                break

    if not result:
        report("FAILED: no valid key derived. Either this file holds no key blob", file=sys.stderr)
        report("(e.g. a split/secondary dex) or the layout changed in this build -", file=sys.stderr)
        report("see 'Adapting to another build' in tools/README.md.", file=sys.stderr)
        return 1

    der, info, _order = result
    key_pem = pem_encode(der, "PRIVATE KEY")
    report("Key blob used: %r" % (cand,))

    # ---- verify key <-> certificate ------------------------------------------
    certinfo = None
    if car["der"]:
        try:
            certinfo = parse_cert(car["der"])
        except Exception as exc:
            report("  (certificate not parsed: %s)" % exc)
    cert_match = bool(certinfo and certinfo["spki"] and
                      certinfo["spki"]["modulus"] == info["n"])

    if certinfo:
        subj = certinfo["names"].get("subject", {})
        issuer = certinfo["names"].get("issuer", {})
        report("CarService certificate: subject=%s issuer=%s"
               % (list(subj.values()), list(issuer.values())))
        for k, v in certinfo["dates"].items():
            if v:
                report("  %s = %s" % (k, v.isoformat()))

    report("")
    report("RSA private key recovered: %d bits (PKCS#8, %d bytes DER)"
           % (info["n_bits"], len(der)))
    report("  RSA primes consistent (n == p*q): %s" % info["consistent"])
    report("  modulus matches CarService cert: %s" % cert_match)
    report("  AES backend: %s" % aes.backend)
    if certinfo:
        not_after = certinfo["dates"].get("notAfter")
        if not_after:
            days = (not_after - _dt.datetime.now(_dt.timezone.utc)).days
            report("  cert validity ends %s (%+d days) - head units do not enforce expiry"
                   % (not_after.date(), days))
    report("")

    os.makedirs(args.out_dir, exist_ok=True)

    def save(name, data):
        path = os.path.join(args.out_dir, name)
        with open(path, "wb") as fh:
            fh.write(data if isinstance(data, bytes) else data.encode("utf-8"))
        report("  wrote %s (%d bytes)" % (path, len(data)))

    save(args.key_name, key_pem)
    if car["der"]:
        save(args.cert_name, pem_encode(car["der"], "CERTIFICATE"))
    save(args.ca_name, gal_pem_bytes)
    if args.dump_bins:
        save("salt.bin", cand.salt)
        save("enckey.bin", cand.enc)
    if args.stdout:
        sys.stdout.write(key_pem)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

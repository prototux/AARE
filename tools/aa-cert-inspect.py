#!/usr/bin/env python3
"""
aa-cert-inspect — read the *public* X.509 certificates out of an Android Auto APK.

Android Auto's phone app (com.google.android.projection.gearhead) carries two public
certificates in its DEX: the self-signed "Google Automotive Link" root, and the
CarService leaf the phone presents during the AAP TLS handshake. This tool finds them
and prints what they are — subject, issuer, dates, serial, SHA-256 fingerprint — and
saves them as PEM, so you can:

  * see which certificate a phone is expected to present,
  * check the expiry dates (the built-in CarService leaf expires 2026-12-31, which is
    why stock Android Auto stops working against units that validate dates),
  * work out whether your head unit is one that checks the phone's certificate
    (docs/certificates.md).

**Public certificates only, by design.** This script never looks for, decrypts or writes
key material: it only matches PEM certificate blocks and skips anything that looks like
a key. The private half of the CarService certificate is Google's, and extracting it is
how you'd forge the authentication Android Auto is built on — that is out of scope here,
see docs/certificates.md ("AARE does not ship, download or extract Google's certificate
or key"). A certificate proves nothing on its own; it only helps if you hold a key you
are entitled to.

Usage:
  tools/aa-cert-inspect.py <file.apk|file.apkm> [...] [-o OUTDIR]
  tools/aa-cert-inspect.py cert.pem chain.der      (loose PEM/DER files too)

Needs: python3, unzip, openssl.
"""

import argparse
import datetime
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

PEM_START = b"-----BEGIN CERTIFICATE-----"
PEM_END = b"-----END CERTIFICATE-----"
KEY_MARKERS = (b"PRIVATE KEY", b"BEGIN RSA PRIVATE", b"ENCRYPTED PRIVATE")


def run(args, data: bytes | None = None) -> bytes:
    proc = subprocess.run(args, input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode("utf-8", "replace").strip()
                          or f"{' '.join(args)} failed")
    return proc.stdout


class Cert:
    """One certificate, held as PEM text, parsed through openssl."""

    def __init__(self, pem: bytes):
        if PEM_START not in pem:
            pem = run(["openssl", "x509", "-inform", "DER", "-outform", "PEM"],
                      data=pem)
        self.pem = pem.strip() + b"\n"
        self.der = run(["openssl", "x509", "-inform", "PEM", "-outform", "DER"],
                       data=self.pem)
        self.sha256 = hashlib.sha256(self.der).hexdigest().upper()
        self.subject = self._x509(["-subject"]).removeprefix("subject=")
        self.issuer = self._x509(["-issuer"]).removeprefix("issuer=")
        self.serial = self._x509(["-serial"]).removeprefix("serial=")
        self.not_before = self._dates()[0]
        self.not_after = self._dates()[1]
        self.root = self.subject == self.issuer

    def _x509(self, extra) -> str:
        return run(["openssl", "x509", "-noout", "-inform", "DER", *extra],
                   data=self.der).decode().strip()

    def _dates(self):
        out = self._x509(["-dates"]).split("\n")
        stamps = [line.split("=", 1)[1] for line in out if "=" in line]
        parsed = []
        for stamp in stamps:
            try:
                parsed.append(datetime.datetime.strptime(
                    stamp.strip(), "%b %d %H:%M:%S %Y %Z"
                ).replace(tzinfo=datetime.timezone.utc))
            except ValueError:
                parsed.append(None)
        while len(parsed) < 2:
            parsed.append(None)
        return parsed

    def org(self) -> str:
        """The organisation (falling back to the CN) from the subject."""
        # OpenSSL 3 prints "O=CarService", 1.1 "O = CarService"
        pairs = re.findall(r'([A-Za-z]+)\s*=\s*(?:"([^"]*)"|([^,]*))', self.subject)
        fields = {k: (v1 or v2).strip() for k, v1, v2 in pairs}
        return fields.get("O") or fields.get("CN") or "?"

    def days_left(self) -> int | None:
        if not self.not_after:
            return None
        delta = self.not_after - datetime.datetime.now(datetime.timezone.utc)
        return delta.days

    def filename(self) -> str:
        slug = re.sub(r"[^A-Za-z0-9]+", "-", self.org()).strip("-").lower() or "cert"
        # the hash keeps two certificates of the same organisation (say an old and a renewed leaf) apart
        return f"{slug}-{'root' if self.root else 'leaf'}-{self.sha256[:8].lower()}.pem"

    def report(self, origin: str) -> str:
        left = self.days_left()
        if left is None:
            flag = ""
        elif left < 0:
            flag = "  ** ALREADY EXPIRED **"
        elif left < 400:
            flag = f"  ** expires in {left} days **"
        else:
            flag = f"  ({left} days left)"
        lines = [
            f"--- {self.org()} — {'self-signed root' if self.root else 'leaf'}  [{origin}]",
            f"    subject  : {self.subject}",
            f"    issuer   : {self.issuer}",
            f"    serial   : {self.serial}",
            f"    sha256   : {':'.join(self.sha256[i:i+2] for i in range(0, 64, 2))}",
            f"    valid    : {self.not_before:%Y-%m-%d} -> {self.not_after:%Y-%m-%d}{flag}",
        ]
        return "\n".join(lines)


def apk_pems(path: str) -> list[tuple[str, bytes]]:
    """PEM certificate blocks stored inside an APK (DEX strings are NUL-terminated)."""
    found: list[tuple[str, bytes]] = []
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
        for name in names:
            if not re.fullmatch(r"classes[A-Za-z0-9_-]*\.dex", name):
                continue
            blob = zf.read(name)
            for start in re.finditer(re.escape(PEM_START), blob):
                stop = blob.find(b"\x00", start.start())
                chunk = blob[start.start(): stop + 1 if stop != -1 else len(blob)]
                end = chunk.find(PEM_END)
                if end == -1:
                    continue
                chunk = chunk[: end + len(PEM_END)]
                if any(k in chunk for k in KEY_MARKERS):
                    print(f"[skip] {name}: string looks like key material", file=sys.stderr)
                    continue
                found.append((name, chunk))
    return found


def signing_pems(path: str) -> list[tuple[str, bytes]]:
    """The certificates that signed the APK itself — separate from the AAP ones."""
    found: list[tuple[str, bytes]] = []
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            if not (name.startswith("META-INF/")
                    and name.upper().endswith((".RSA", ".DSA", ".EC"))):
                continue
            try:
                out = run(["openssl", "pkcs7", "-print", "-certs", "-inform", "DER"],
                          data=zf.read(name)).decode("utf-8", "replace")
            except RuntimeError:
                continue
            for m in re.finditer(
                    re.escape(PEM_START.decode()) + r".*?" + re.escape(PEM_END.decode()),
                    out, re.S):
                found.append((f"{os.path.basename(path)}: APK signature",
                              m.group(0).encode()))
    return found


def is_apkm(path: str) -> bool:
    try:
        with zipfile.ZipFile(path) as zf:
            return "base.apk" in zf.namelist()
    except (zipfile.BadZipFile, IsADirectoryError):
        return False


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Inspect the public certificates in an Android Auto APK.")
    ap.add_argument("input", nargs="+", help="APK, APKM, or loose PEM/DER files")
    ap.add_argument("-o", "--outdir", default=None,
                    help="where to save PEM files (default: ./aa-certs)")
    ap.add_argument("--signing", action="store_true",
                    help="also print the APK signing certificate(s)")
    args = ap.parse_args()

    outdir = args.outdir or os.path.join(os.getcwd(), "aa-certs")
    print("Public certificates only. Nothing here reads, decrypts, or writes key material.\n")

    collected: list[tuple[str, bytes]] = []
    tmp = tempfile.mkdtemp(prefix="aa-cert-inspect.")
    try:
        for path in args.input:
            if not os.path.isfile(path):
                print(f"[skip] no such file: {path}", file=sys.stderr)
                continue
            if path.lower().endswith((".pem", ".crt", ".cer", ".der")):
                with open(path, "rb") as fh:
                    collected.append((os.path.basename(path), fh.read()))
                continue
            if is_apkm(path):
                print(f"[apkm] {os.path.basename(path)}: taking base.apk")
                with zipfile.ZipFile(path) as zf:
                    inner = os.path.join(tmp, "base.apk")
                    with zf.open("base.apk") as src, open(inner, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                path = inner
            if not zipfile.is_zipfile(path):
                print(f"[skip] not an APK: {path}", file=sys.stderr)
                continue
            collected += apk_pems(path)
            if args.signing:
                collected += signing_pems(path)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if not collected:
        print("No certificate found.\n"
              "Note: a phone APK holds no *head unit* certificate. Head unit certs live "
              "in the head unit (or its update image), and Google's app only carries the "
              "root they must chain to.")
        return 1

    os.makedirs(outdir, exist_ok=True)
    seen, count = set(), 0
    for origin, pem in collected:
        try:
            cert = Cert(pem)
        except RuntimeError as e:
            print(f"[skip] unparsable certificate in {origin}: {e}", file=sys.stderr)
            continue
        if cert.sha256 in seen:
            continue
        seen.add(cert.sha256)
        count += 1
        print(cert.report(origin))
        out = os.path.join(outdir, cert.filename())
        with open(out, "wb") as fh:
            fh.write(cert.pem)
        print(f"    saved    : {out}\n")

    print(f"{count} unique certificate(s); PEM files in {outdir}")
    print("To compare with your own AARE build's signing key: "
          "apksigner verify --print-certs app.apk")
    return 0


if __name__ == "__main__":
    sys.exit(main())

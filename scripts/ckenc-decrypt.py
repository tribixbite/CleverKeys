#!/usr/bin/env python3
"""
ckenc-decrypt.py — reference decryptor for CleverKeys `CKENC1` encrypted backups.

Decrypts a `*.ckenc` file (settings/dictionary/clipboard JSON, or a clipboard/full
backup ZIP) that CleverKeys wrote after you set a Backup Password in
Settings -> Backup & Restore. Use it to open your OWN encrypted exports off-device
(e.g. to post-process the JSON in a Termux script).

DEPENDENCY:
    pip install cryptography
(Pure-stdlib AES-256-GCM is not available in Python's standard library; the
`cryptography` package provides it.)

USAGE:
    python3 ckenc-decrypt.py INPUT.ckenc [-o OUTPUT] [-p PASSPHRASE]

    -o OUTPUT      Write plaintext here. Defaults to INPUT with a trailing
                   `.ckenc` stripped (or `INPUT.decrypted` if there is none).
                   Use `-o -` to write to stdout.
    -p PASSPHRASE  Backup password. If omitted you are prompted (no echo).

WRONG PASSWORD vs TAMPER: AES-GCM cannot tell them apart — both fail the
authentication tag. The tool prints a single "wrong password or corrupted/tampered"
message in that case, matching the app.

ON-DISK FORMAT (`CKENC1`, big-endian; the 51-byte header is the GCM AAD):
    offset size field
    0      8    magic  = ASCII "CKENC1" + 0x0D 0x0A
    8      1    format_version = 0x01
    9      1    content_type   1=settings JSON 2=dict JSON 3=clipboard JSON
                               4=clipboard ZIP 5=full-backup ZIP
    10     1    kdf_id = 0x01 (PBKDF2-HMAC-SHA256)
    11     4    kdf_iterations (uint32)
    15     16   kdf_salt
    31     12   gcm_nonce
    43     8    export_timestamp_epoch_millis (informational; AAD-covered)
    51     ...  ciphertext || 16-byte GCM tag
"""

import argparse
import getpass
import struct
import sys
from datetime import datetime, timezone

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    from cryptography.exceptions import InvalidTag
except ImportError:
    sys.stderr.write(
        "error: the 'cryptography' package is required.\n"
        "       install it with:  pip install cryptography\n"
    )
    sys.exit(2)

# Must match tribixbite.cleverkeys.backup.crypto.EncryptedBackupFormat exactly.
MAGIC = b"CKENC1\r\n"                 # 8 bytes
HEADER_LEN = 51
FORMAT_VERSION = 1
KDF_PBKDF2_SHA256 = 1
MAX_KDF_ITERATIONS = 5_000_000       # DoS guard mirrors the app
SALT_LEN = 16
NONCE_LEN = 12
TAG_LEN = 16
KEY_LEN = 32                         # AES-256

CONTENT_TYPE_NAMES = {
    1: "settings JSON",
    2: "dictionaries JSON",
    3: "clipboard JSON",
    4: "clipboard ZIP (media)",
    5: "full-backup ZIP",
}


class HeaderError(Exception):
    """Structural/header problem (distinct from a decrypt/auth failure)."""


def parse_header(blob: bytes):
    """Validate the 51-byte header and return its fields as a dict."""
    if len(blob) < HEADER_LEN:
        raise HeaderError(
            f"file too short: header needs {HEADER_LEN} bytes, got {len(blob)}"
        )
    if blob[0:8] != MAGIC:
        raise HeaderError("not a CKENC1 backup (bad magic)")

    version = blob[8]
    if version > FORMAT_VERSION:
        raise HeaderError(
            f"backup is from a newer CleverKeys version (format v{version}); "
            "update this script"
        )
    if version < FORMAT_VERSION:
        raise HeaderError(f"unsupported backup format version: {version}")

    content_type = blob[9]
    kdf_id = blob[10]
    if kdf_id != KDF_PBKDF2_SHA256:
        raise HeaderError(f"unknown KDF id in header: {kdf_id}")

    (iterations,) = struct.unpack_from(">I", blob, 11)
    if iterations < 1 or iterations > MAX_KDF_ITERATIONS:
        raise HeaderError(
            f"KDF iteration count out of range (1..{MAX_KDF_ITERATIONS}): {iterations}"
        )

    salt = blob[15:15 + SALT_LEN]
    nonce = blob[31:31 + NONCE_LEN]
    (timestamp_ms,) = struct.unpack_from(">q", blob, 43)

    return {
        "version": version,
        "content_type": content_type,
        "kdf_id": kdf_id,
        "iterations": iterations,
        "salt": salt,
        "nonce": nonce,
        "timestamp_ms": timestamp_ms,
        "aad": blob[0:HEADER_LEN],  # entire header is the GCM AAD
    }


def derive_key(passphrase: str, salt: bytes, iterations: int) -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=KEY_LEN,
        salt=salt,
        iterations=iterations,
    )
    return kdf.derive(passphrase.encode("utf-8"))


def decrypt(container: bytes, passphrase: str) -> bytes:
    header = parse_header(container)
    ciphertext_and_tag = container[HEADER_LEN:]
    if len(ciphertext_and_tag) < TAG_LEN:
        raise HeaderError("truncated file: ciphertext shorter than the GCM tag")

    key = derive_key(passphrase, header["salt"], header["iterations"])
    aesgcm = AESGCM(key)
    # AESGCM.decrypt expects ciphertext||tag and verifies the tag; raises InvalidTag
    # on a wrong key OR any tamper (cryptographically indistinguishable).
    plaintext = aesgcm.decrypt(header["nonce"], ciphertext_and_tag, header["aad"])
    _print_header_info(header)
    return plaintext


def _print_header_info(header: dict) -> None:
    ct = header["content_type"]
    name = CONTENT_TYPE_NAMES.get(ct, f"unknown ({ct})")
    ts = header["timestamp_ms"]
    when = (
        datetime.fromtimestamp(ts / 1000, tz=timezone.utc).isoformat()
        if ts > 0 else "n/a"
    )
    sys.stderr.write(
        f"decrypted: content={name}, exported={when}, "
        f"pbkdf2_iterations={header['iterations']}\n"
    )


def _default_output(input_path: str) -> str:
    if input_path.endswith(".ckenc"):
        return input_path[: -len(".ckenc")]
    return input_path + ".decrypted"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Decrypt a CleverKeys CKENC1 backup file.",
    )
    parser.add_argument("input", help="path to the .ckenc file")
    parser.add_argument("-o", "--output", help="output path ('-' for stdout)")
    parser.add_argument("-p", "--passphrase", help="backup password (prompted if omitted)")
    args = parser.parse_args()

    try:
        with open(args.input, "rb") as fh:
            container = fh.read()
    except OSError as exc:
        sys.stderr.write(f"error: cannot read {args.input}: {exc}\n")
        return 2

    passphrase = args.passphrase or getpass.getpass("Backup password: ")

    try:
        plaintext = decrypt(container, passphrase)
    except HeaderError as exc:
        sys.stderr.write(f"error: {exc}\n")
        return 3
    except InvalidTag:
        sys.stderr.write(
            "error: wrong backup password, or the file is corrupted/tampered.\n"
        )
        return 4

    out_path = args.output or _default_output(args.input)
    if out_path == "-":
        sys.stdout.buffer.write(plaintext)
    else:
        with open(out_path, "wb") as fh:
            fh.write(plaintext)
        sys.stderr.write(f"wrote {len(plaintext)} bytes -> {out_path}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

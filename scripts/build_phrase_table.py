#!/usr/bin/env python3
"""
Build a CKPY v1 pinyin phrase table (`phrases.bin`) for a CleverKeys composing pack.

The table maps a normalised toneless pinyin key to ranked candidate text (汉字 / 漢字):

    ni<TAB>你<TAB>1000
    ni<TAB>尼<TAB>20
    nihao<TAB>你好<TAB>5000

Input is TSV: `pinyin<TAB>text[<TAB>weight]`. One line per candidate; lines that share a
pinyin key are grouped, sorted by weight DESC (input order breaks ties), and written in
that order with rank = position (0 = most preferred). Missing weight defaults to 0.

Key normalisation (the builder's job — the reader requires already-normalised keys):
  - lowercased; `ü`/`ǖǘǚǜ` become `v` (女 = `nv`); other tone marks are stripped (NFD
    + drop combining marks); digit tone numbers are dropped
  - `’`/`‘`/`` ` `` become `'`; anything left that is not `a-z` or `'` is an error
  - `xi'an` keeps its apostrophe, which is what disambiguates it from `xian`

Format (little-endian, see `docs/specs/pinyin-ime.md` and `CkpyPhraseTable.kt`):
  header 48 B: magic "CKPY" | version 1 | language 4 B NUL-padded | keyCount u32 |
               dataOffset u32 = 48 | 28 B reserved zeros
  per key:     keyLen u16 | key UTF-8 | candidateCount u16 |
               per candidate: textLen u16 | text UTF-8 | rank u8

Usage:
    python3 build_phrase_table.py --lang zh --input phrases.tsv --output phrases.bin

License: Apache-2.0
"""

import argparse
import struct
import sys
import unicodedata
from collections import OrderedDict
from pathlib import Path

MAGIC = 0x59504B43  # "CKPY" little-endian
VERSION = 1
HEADER_SIZE = 48
LANGUAGE_BYTES = 4

MAX_KEY_COUNT = 1_000_000
MAX_KEY_BYTES = 120
MAX_CANDIDATES_PER_KEY = 512
MAX_TEXT_BYTES = 64

# Precomposed u-umlaut forms must map to `v` BEFORE NFD strips the diaeresis, or `nv`
# (女) would silently become `nu` — a different syllable.
_UMLAUT = str.maketrans({
    "\u00fc": "v",  # ü
    "\u01d6": "v",  # ǖ
    "\u01d8": "v",  # ǘ
    "\u01da": "v",  # ǚ
    "\u01dc": "v",  # ǜ
    "\u2019": "'",  # ’
    "\u2018": "'",  # ‘
    "`": "'",
})


class BuildError(Exception):
    pass


def normalise_key(raw: str) -> str:
    key = raw.strip().translate(_UMLAUT).lower()
    key = unicodedata.normalize("NFD", key)
    key = "".join(ch for ch in key if not unicodedata.combining(ch))
    key = "".join(ch for ch in key if not ch.isdigit())
    if not key:
        raise BuildError(f"empty pinyin key after normalisation: {raw!r}")
    bad = sorted({ch for ch in key if not ("a" <= ch <= "z" or ch == "'")})
    if bad:
        raise BuildError(
            f"pinyin key {raw!r} normalises to {key!r} which contains {bad!r}; "
            "only lowercase a-z and ' are allowed"
        )
    if len(key.encode("utf-8")) > MAX_KEY_BYTES:
        raise BuildError(f"pinyin key {key!r} exceeds {MAX_KEY_BYTES} UTF-8 bytes")
    return key


def load_candidates(input_file: Path):
    """Parse the TSV into {key: [(text, weight)]} preserving first-seen order."""
    grouped: "OrderedDict[str, list[tuple[str, float]]]" = OrderedDict()
    with open(input_file, "r", encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                raise BuildError(f"{input_file}:{lineno}: expected `pinyin<TAB>text[<TAB>weight]`")
            key = normalise_key(parts[0])
            text = parts[1].strip()
            if not text:
                raise BuildError(f"{input_file}:{lineno}: empty candidate text")
            if "\x00" in text:
                raise BuildError(f"{input_file}:{lineno}: candidate text contains NUL")
            if len(text.encode("utf-8")) > MAX_TEXT_BYTES:
                raise BuildError(f"{input_file}:{lineno}: {text!r} exceeds {MAX_TEXT_BYTES} UTF-8 bytes")
            weight = 0.0
            if len(parts) >= 3 and parts[2].strip():
                try:
                    weight = float(parts[2])
                except ValueError:
                    raise BuildError(f"{input_file}:{lineno}: bad weight {parts[2]!r}") from None
            grouped.setdefault(key, []).append((text, weight))
    if not grouped:
        raise BuildError(f"{input_file}: no phrase rows found")
    if len(grouped) > MAX_KEY_COUNT:
        raise BuildError(f"{len(grouped)} keys exceeds the {MAX_KEY_COUNT} format cap")
    return grouped


def rank_candidates(candidates):
    """Stable weight-descending sort; rank = position, clamped to the uint8 range."""
    ordered = sorted(candidates, key=lambda pair: -pair[1])
    if len(ordered) > MAX_CANDIDATES_PER_KEY:
        raise BuildError(
            f"one key has {len(ordered)} candidates, over the {MAX_CANDIDATES_PER_KEY} format cap"
        )
    return [(text, min(index, 255)) for index, (text, _) in enumerate(ordered)]


def build_table(language: str, grouped) -> bytes:
    lang_bytes = language.encode("utf-8")
    if len(lang_bytes) > LANGUAGE_BYTES:
        raise BuildError(f"language tag {language!r} does not fit in {LANGUAGE_BYTES} UTF-8 bytes")
    lang_bytes = lang_bytes.ljust(LANGUAGE_BYTES, b"\x00")

    body = bytearray()
    for key in sorted(grouped):
        key_bytes = key.encode("utf-8")
        candidates = rank_candidates(grouped[key])
        body += struct.pack("<H", len(key_bytes)) + key_bytes + struct.pack("<H", len(candidates))
        for text, rank in candidates:
            text_bytes = text.encode("utf-8")
            body += struct.pack("<H", len(text_bytes)) + text_bytes + struct.pack("<B", rank)

    header = struct.pack(
        "<II4sII", MAGIC, VERSION, lang_bytes, len(grouped), HEADER_SIZE
    ) + b"\x00" * 28
    return bytes(header) + bytes(body)


def verify_table(data: bytes) -> None:
    """Parse the bytes we just wrote back out, so a writer bug fails the build, not the app."""
    if len(data) < HEADER_SIZE:
        raise BuildError("written table is shorter than the header")
    magic, version, lang, key_count, data_offset = struct.unpack_from("<II4sII", data, 0)
    if magic != MAGIC or version != VERSION:
        raise BuildError("written table header does not round-trip")
    pos = data_offset
    previous = None
    for _ in range(key_count):
        (key_len,) = struct.unpack_from("<H", data, pos)
        pos += 2
        key = data[pos:pos + key_len].decode("utf-8")
        pos += key_len
        if previous is not None and key <= previous:
            raise BuildError(f"written keys not strictly ascending at {key!r}")
        previous = key
        (candidate_count,) = struct.unpack_from("<H", data, pos)
        pos += 2
        last_rank = -1
        for _ in range(candidate_count):
            (text_len,) = struct.unpack_from("<H", data, pos)
            pos += 2 + text_len
            rank = data[pos]
            pos += 1
            if rank < last_rank:
                raise BuildError(f"written ranks out of order for {key!r}")
            last_rank = rank
    if pos != len(data):
        raise BuildError(f"written table has {len(data) - pos} trailing bytes")


def main():
    parser = argparse.ArgumentParser(
        description="Build a CKPY v1 pinyin phrase table for CleverKeys",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--lang", default="zh", help='language tag stored in the header (default: zh)')
    parser.add_argument("--input", required=True, type=Path,
                        help="TSV source: `pinyin<TAB>text[<TAB>weight]` per line")
    parser.add_argument("--output", required=True, type=Path, help="output phrases.bin path")
    args = parser.parse_args()

    try:
        grouped = load_candidates(args.input)
        data = build_table(args.lang, grouped)
        verify_table(data)
    except BuildError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1

    args.output.write_bytes(data)
    candidate_count = sum(len(v) for v in grouped.values())
    print(f"Wrote {args.output}")
    print(f"  Language:   {args.lang}")
    print(f"  Keys:       {len(grouped)}")
    print(f"  Candidates: {candidate_count}")
    print(f"  Size:       {len(data) / 1024:.1f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())

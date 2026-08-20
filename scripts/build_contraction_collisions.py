#!/usr/bin/env python3
"""Emit the cross-language contraction COLLISION sidecars the typing path demotes on.

Why this exists
---------------
A `contractions_<lang>.json` entry is REPLACE mode: the key is an alias with no reading of its
own, so the display form substitutes for it and keeps its slot. That judgement is made PER
LANGUAGE — and it is only true per language.

`ContractionManager.loadTypingMappings` merges the primary language's mappings, the secondary
language's, and the English base into ONE map with no provenance. So a key with no reading in
language L is applied to a word that IS a reading in language M, and the real word is destroyed
in its own slot. Measured on the shipped assets, before the fix:

    fr+en  a user typing French `dont` (relative pronoun) got `don't`
    de+en  a user typing German `im` (in dem) got `I'm`
    de+en  a user typing English `hats` got `hat's`, from de's curated clitic table

This is the same defect the 2026-07-23 multilingual audit fixed WITHIN English — `well`→`we'll`
destroying the word "well" — generalised to the cross-language case. That fix reclassified paired
bases out of the non-paired map; this one reclassifies cross-language collisions the same way,
into the same PAIRED bucket, so both spellings stay reachable.

What is emitted
---------------
`contraction_collisions_<lang>.json`, mapping each colliding REPLACE key to the SORTED list of
other bundled languages whose lexicon contains it:

    {"rendezvous": ["de", "en"], "dont": ["fr"], ...}

The per-key language LIST is the point — a boolean "collides with something" would demote
`cest` for an fr+es user because `cest` happens to be an obscure English lexicon entry, even
though no active language of theirs contains it. The app intersects this list with the set of
ACTIVE languages, so a monolingual user is affected by nothing.

Collision criterion
-------------------
Plain membership in the other language's bundled lexicon, lowercased. Deliberately NOT filtered
by hunspell attestation: the bundled lexicons carry some noise (`nt`, `dab`), so a few demotions
are driven by non-words — but the criterion has to be reproducible by `ContractionCollisionDataTest`
on the JVM, which has no hunspell, and the cost of a spurious demotion is one extra suggestion
slot, never a lost word. Asymmetric costs, so bias toward demoting.

Usage
-----
    python3 scripts/build_contraction_collisions.py            # write the sidecars
    python3 scripts/build_contraction_collisions.py --check    # verify, exit 1 on drift
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DICT_DIR = REPO / "src/main/assets/dictionaries"

#: Every language that bundles a lexicon. A REPLACE key is checked against all of them, because
#: any of them can be the user's other active language.
BUNDLED = ("en", "de", "es", "fr", "it", "pt", "sv")


def read_ckdt_v2(path: Path) -> set[str]:
    """Canonical surfaces of a bundled `<lang>_enhanced.bin`.

    Header (48 B, little-endian): magic `CKDT`, version 2, 4-byte language tag, word count,
    canonical/normalized/accent-map offsets, 20 reserved bytes. The canonical section is `count`
    records of `uint16 len | utf-8 bytes | uint8 rank`. Only the surfaces are needed here, so the
    rank byte is skipped rather than returned.
    """
    buf = path.read_bytes()
    magic = buf[:4]
    (version,) = struct.unpack_from("<I", buf, 4)
    if magic != b"CKDT" or version != 2:
        raise SystemExit(f"{path}: expected CKDT v2, got {magic!r} v{version}")
    (count,) = struct.unpack_from("<I", buf, 12)
    (canon_off,) = struct.unpack_from("<I", buf, 16)
    out: set[str] = set()
    off = canon_off
    for _ in range(count):
        (n,) = struct.unpack_from("<H", buf, off)
        off += 2
        out.add(buf[off:off + n].decode("utf-8", "replace").lower())
        off += n + 1  # +1 skips the rank byte
    return out


def lexicon(lang: str) -> set[str]:
    """The bundled lexicon surfaces for `lang`; English ships JSON, the rest CKDT."""
    binary = DICT_DIR / f"{lang}_enhanced.bin"
    if binary.is_file():
        return read_ckdt_v2(binary)
    js = DICT_DIR / f"{lang}_enhanced.json"
    if js.is_file():
        data = json.loads(js.read_text(encoding="utf-8"))
        keys = data.keys() if isinstance(data, dict) else data
        return {w.lower() for w in keys}
    raise SystemExit(f"no bundled lexicon for {lang!r} in {DICT_DIR}")


def replace_keys(lang: str) -> set[str]:
    """The EFFECTIVE REPLACE keys for `lang`, as `ContractionManager` ends up holding them.

    English is special and must be modelled, not read raw. Its base ships as
    `contractions_non_paired.json`, but `ContractionManager.loadEnglishBase` then removes every
    key that is also a base in `contraction_pairings.json` — the 2026-07-23 reclassification that
    stopped `well`→`we'll` destroying "well". Reading the raw file instead would credit English
    with 14 REPLACE keys it does not apply, and would emit collisions for `well`, `shell`, `were`
    and `hell` that the runtime can never hit.
    """
    if lang == "en":
        raw = json.loads((DICT_DIR / "contractions_non_paired.json").read_text(encoding="utf-8"))
        paired = json.loads((DICT_DIR / "contraction_pairings.json").read_text(encoding="utf-8"))
        return {k.lower() for k in raw if k.lower() not in {p.lower() for p in paired}}
    path = DICT_DIR / f"contractions_{lang}.json"
    if not path.is_file():
        return set()
    return {k.lower() for k in json.loads(path.read_text(encoding="utf-8"))}


def collisions_for(lang: str, lexicons: dict[str, set[str]]) -> dict[str, list[str]]:
    """key -> sorted other-language codes whose bundled lexicon contains that key."""
    out: dict[str, list[str]] = {}
    for key in sorted(replace_keys(lang)):
        hits = sorted(m for m in BUNDLED if m != lang and key in lexicons[m])
        if hits:
            out[key] = hits
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true",
                    help="verify the shipped sidecars match the data; exit 1 on drift")
    args = ap.parse_args()

    lexicons = {lang: lexicon(lang) for lang in BUNDLED}
    for lang, words in lexicons.items():
        print(f"  lexicon {lang}: {len(words):,} surfaces")

    drift = False
    for lang in BUNDLED:
        keys = replace_keys(lang)
        if not keys:
            continue
        table = collisions_for(lang, lexicons)
        target = DICT_DIR / f"contraction_collisions_{lang}.json"
        # Sorted keys + a trailing newline so regeneration produces a stable, reviewable diff.
        text = json.dumps(table, ensure_ascii=False, indent=2, sort_keys=True) + "\n"

        if args.check:
            current = target.read_text(encoding="utf-8") if target.is_file() else ""
            if current != text:
                print(f"DRIFT {target.name}: shipped file does not match the bundled data")
                drift = True
            else:
                print(f"  ok   {target.name}: {len(table)} colliding keys of {len(keys)}")
            continue

        target.write_text(text, encoding="utf-8")
        print(f"  wrote {target.name}: {len(table)} colliding keys of {len(keys)} REPLACE keys")

    if args.check and drift:
        print("\nFAIL: re-run without --check to regenerate.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

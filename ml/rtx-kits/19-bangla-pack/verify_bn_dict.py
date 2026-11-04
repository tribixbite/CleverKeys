#!/usr/bin/env python3
"""Verify a CKDT V2 Bangla dictionary against its word list and the runtime lookup key (kit 19).

`scripts/build_dictionary.py` writes, per word, a CANONICAL form (display) and a NORMALIZED
lookup key. At runtime `AccentNormalizer.normalize` builds the key of what the user typed or
swiped: lowercase -> NFD -> strip U+0300..U+036F only (`DIACRITICS_REGEX`), so Bengali signs
survive at runtime. The builder's `normalize_accents` strips EVERY category-Mn code point, which
for Bangla removes the virama (hasanta U+09CD), u / uu / vocalic-r vowel signs, nukta and
candrabindu. This script measures that, read back from the binary, so the defect (or its fix)
is a number rather than a claim:

  1. round trip     every word-list entry is a canonical word of the .bin, byte-identical NFC
  2. key parity     stored normalized key == the runtime key of its canonical word
                    (a mismatch means the runtime can never look that word up by its key)
  3. mark loss      stored keys that lost a Bengali Mn mark the canonical word has
  4. conflation     normalized keys shared by more than one canonical word (distinct spellings
                    such as ফল / ফুল / ফ্লু merged into one lookup key)

Exit 0 only when 1 and 2 are clean and 3 is zero: the gate for a "spelling-preserving" pack.

Usage: python3 verify_bn_dict.py --dict bn_enhanced.bin --wordlist bn_wordlist.tsv
"""
from __future__ import annotations

import argparse
import re
import struct
import unicodedata as ud
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Tuple

MAGIC = 0x54444B43  # "CKDT"
RUNTIME_STRIP = re.compile("[̀-ͯ]")  # AccentNormalizer.DIACRITICS_REGEX


def runtime_key(word: str) -> str:
    """AccentNormalizer.normalize for a Bengali word (SPECIAL_REPLACEMENTS are all Latin)."""
    return RUNTIME_STRIP.sub("", ud.normalize("NFD", word.lower()))


def bengali_marks(word: str) -> int:
    """Count of Bengali-block combining (Mn) code points in the NFD form of [word]."""
    return sum(1 for c in ud.normalize("NFD", word) if ud.category(c) == "Mn" and "\u0980" <= c <= "\u09ff")


def read_ckdt(path: Path) -> Tuple[List[Tuple[str, int]], Dict[str, List[int]]]:
    """(canonical words with rank, normalized key -> canonical indices) from a V2 file."""
    b = path.read_bytes()
    magic, version = struct.unpack_from("<II", b, 0)
    if magic != MAGIC or version != 2:
        raise SystemExit(f"{path}: not a CKDT V2 file (magic {magic:#x}, version {version})")
    count, c_off, n_off, m_off = struct.unpack_from("<IIII", b, 12)
    canon: List[Tuple[str, int]] = []
    p = c_off
    for _ in range(count):
        (ln,) = struct.unpack_from("<H", b, p); p += 2
        canon.append((b[p:p + ln].decode("utf-8"), b[p + ln])); p += ln + 1
    (nkeys,) = struct.unpack_from("<I", b, n_off)
    keys: List[str] = []
    p = n_off + 4
    for _ in range(nkeys):
        (ln,) = struct.unpack_from("<H", b, p); p += 2
        keys.append(b[p:p + ln].decode("utf-8")); p += ln
    amap: Dict[str, List[int]] = {}
    p = m_off
    for k in keys:
        n = b[p]; p += 1
        amap[k] = list(struct.unpack_from(f"<{n}I", b, p)); p += 4 * n
    return canon, amap


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dict", type=Path, required=True)
    ap.add_argument("--wordlist", type=Path, required=True)
    ap.add_argument("--examples", type=int, default=8)
    a = ap.parse_args()
    canon, amap = read_ckdt(a.dict)
    words = [l.split("\t")[0] for l in a.wordlist.read_text(encoding="utf-8").splitlines() if l.strip()]
    canon_set = {w for w, _ in canon}
    missing = [w for w in words if w not in canon_set]
    key_of: Dict[int, str] = {}
    for k, idx in amap.items():
        for i in idx:
            key_of[i] = k
    parity_bad = [(canon[i][0], k) for i, k in key_of.items() if runtime_key(canon[i][0]) != k]
    lost = [(canon[i][0], k) for i, k in key_of.items() if bengali_marks(k) < bengali_marks(canon[i][0])]
    groups = [sorted(canon[i][0] for i in idx) for idx in amap.values() if len(idx) > 1]
    by_size: Dict[int, int] = defaultdict(int)
    for g in groups:
        by_size[len(g)] += 1
    print(f"canonical words {len(canon)}; normalized keys {len(amap)}; word list {len(words)}")
    print(f"1 round trip: missing from .bin {len(missing)} {missing[:a.examples]}")
    print(f"2 key parity with the runtime AccentNormalizer: mismatched {len(parity_bad)} "
          f"e.g. {parity_bad[:a.examples]}")
    print(f"3 keys that lost a Bengali Mn mark: {len(lost)}")
    print(f"4 conflated keys: {len(groups)} groups, {sum(map(len, groups))} words, sizes {dict(by_size)} "
          f"e.g. {groups[:a.examples]}")
    ok = not missing and not parity_bad and not lost
    print("VERDICT:", "spelling-preserving" if ok else "NOT spelling-preserving — do not publish")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Build the Bangla word list CleverKeys would take from WM Keyboard's bn.txt (kit 19).

Source file: wmkeyboard `app/dictionaries-src/bn.txt` (repo github.com/wasi-master/wmkeyboard,
last changed at a19072cf7d871986d2c7aa123f79367a756a817d, sha256 145c1f75…6df9). It is FOUR
blocks with different provenance, told apart by its own `#` section comments:

  seed      lines 3-642    ~640 hand-written conversational words      MIT, (c) 2026 Wasi Master
  hand      "Hand additions" 30 words                                  MIT, (c) 2026 Wasi Master
  opensub   "Corpus expansion (OpenSubtitles 2018 frequency list)"    an ADAPTATION of
            19,975 words, every one an NFC form of a word of             FrequencyWords bn_50k
            hermitdave/FrequencyWords content/2018/bn/bn_50k.txt         (CC BY-SA 4.0 content)
  chat      "Chat verb forms" 120 generated colloquial forms, kept     EXCLUDED: filtered against
            only where an unnamed "romanized chat corpus" reaches them   a corpus of unstated origin

WM Keyboard's MIT licence covers its own seed/hand words and code; it cannot relicense the
FrequencyWords-derived block, which stays CC BY-SA 4.0 (the same regime CleverKeys already
ships `langpack-en-opensubtitles*.zip` under; NOTICE "Word frequencies — FrequencyWords").

What this writes (never into src/main/assets — a pack ships only through the maintainer-approved
langpacks release):
  <out>/bn_wordlist.tsv   word<TAB>count, NFC, sorted by count desc (input for build_dictionary.py)
  <out>/provenance.tsv    word, block, upstream_count, nfc_changed — one row per kept word
  <out>/report.txt        block counts, rejects by reason, normalisation facts

Frequencies: the UPSTREAM FrequencyWords count (summed over spellings that are equal after NFC,
e.g. precomposed U+09DF vs U+09AF U+09BC), not WM Keyboard's log-rescaled 1..2600 / hand-tuned
130..9000 values, so one scale covers every word. A seed/hand word absent from bn_50k gets the
smallest count of the kept upstream words (a floor; counted in the report).

Usage:
  python3 build_bn_subset.py --bn ../wmkeyboard/app/dictionaries-src/bn.txt \
      --freqwords bn_50k.txt --out OUTDIR
"""
from __future__ import annotations

import argparse
import hashlib
import unicodedata as ud
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Tuple

BN_SHA256 = "145c1f751f8244ed9f87b41a3be815c4a3a188ca3fe5ba6f78742690b31d6df9"
FREQWORDS_SHA256 = "2fd6c08f29335f26af983c26821537e5ed5e42dfdf43275a0c646a15d18d636e"
KEEP_BLOCKS = ("seed", "hand", "opensub")
#: Bengali block, plus ZWNJ/ZWJ (none occur in this source, but they are legitimate in Bangla).
ALLOWED_EXTRA = {"‌", "‍"}
VIRAMA = "্"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def blocks(path: Path) -> List[Tuple[str, str]]:
    """(block, word) for every entry, the block taken from the file's own section comments."""
    out: List[Tuple[str, str]] = []
    block = "seed"
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            if "Hand additions" in line:
                block = "hand"
            elif "Corpus expansion" in line:
                block = "opensub"
            elif "Chat verb forms" in line:
                block = "chat"
            continue
        if line.strip():
            out.append((block, line.rsplit(" ", 1)[0].strip()))
    return out


def reject_reason(w: str) -> Optional[str]:
    """Why [w] (already NFC) is not a well-formed Bengali-script word, or None.

    Structural checks only — they never judge spelling: every code point in the Bengali block
    (or ZWNJ/ZWJ), the first one a letter (category Lo, not a sign or digit), no two viramas in
    a row, and no virama directly before a vowel sign / another mark.
    """
    if any(not ("ঀ" <= c <= "৿" or c in ALLOWED_EXTRA) for c in w):
        return "non-Bengali code point"
    if ud.category(w[0]) != "Lo":
        return "starts with a sign or digit"
    if any(ud.category(c) == "Nd" for c in w):
        return "contains a digit"
    for a, b in zip(w, w[1:]):
        if a == VIRAMA and (b == VIRAMA or ud.category(b) in ("Mn", "Mc")):
            return "virama before a mark"
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bn", type=Path, required=True)
    ap.add_argument("--freqwords", type=Path, required=True, help="FrequencyWords content/2018/bn/bn_50k.txt")
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    for p, want in ((a.bn, BN_SHA256), (a.freqwords, FREQWORDS_SHA256)):
        got = sha256(p)
        if got != want:
            raise SystemExit(f"{p}: sha256 {got} != pinned {want} (re-verify provenance before using another revision)")
    a.out.mkdir(parents=True, exist_ok=True)

    upstream: Dict[str, int] = defaultdict(int)  # NFC word -> summed count
    for line in a.freqwords.read_text(encoding="utf-8").splitlines():
        parts = line.split(" ")
        if len(parts) == 2 and parts[1].isdigit():
            upstream[ud.normalize("NFC", parts[0])] += int(parts[1])

    entries = blocks(a.bn)
    per_block = Counter(b for b, _ in entries)
    rejects: Counter = Counter()
    kept: Dict[str, Tuple[str, Optional[int], bool]] = {}  # nfc word -> (block, upstream count, nfc changed)
    not_upstream_opensub = 0
    for block, raw in entries:
        if block not in KEEP_BLOCKS:
            rejects[f"block {block} excluded (provenance)"] += 1
            continue
        w = ud.normalize("NFC", raw)
        why = reject_reason(w)
        if why:
            rejects[why] += 1
            continue
        if w in kept:
            rejects["duplicate after NFC"] += 1
            continue
        cnt = upstream.get(w)
        if block == "opensub" and cnt is None:
            not_upstream_opensub += 1
        kept[w] = (block, cnt, w != raw)
    floor = min(c for _, c, _ in kept.values() if c is not None)
    rows = sorted(((w, c if c is not None else floor) for w, (_, c, _) in kept.items()), key=lambda r: (-r[1], r[0]))
    with open(a.out / "bn_wordlist.tsv", "w", encoding="utf-8") as fo:
        for w, c in rows:
            fo.write(f"{w}\t{c}\n")
    with open(a.out / "provenance.tsv", "w", encoding="utf-8") as fo:
        fo.write("word\tblock\tupstream_count\tnfc_changed\n")
        for w, (blk, up, ch) in sorted(kept.items()):
            fo.write(f"{w}\t{blk}\t{'' if up is None else up}\t{int(ch)}\n")
    kb = Counter(b for b, _, _ in kept.values())
    floored = sum(1 for _, c, _ in kept.values() if c is None)
    words = list(kept)
    report = [
        f"source bn.txt sha256 {BN_SHA256}",
        f"source bn_50k.txt sha256 {FREQWORDS_SHA256}",
        f"entries per block: {dict(per_block)}",
        f"kept per block: {dict(kb)} total {len(kept)}",
        f"rejected: {dict(rejects)}",
        f"opensub words absent from bn_50k after NFC: {not_upstream_opensub} (must be 0)",
        f"seed/hand words absent from bn_50k (count floored to {floor}): {floored}",
        f"words changed by NFC (precomposed nukta letters U+09DC/09DD/09DF -> base + U+09BC): "
        f"{sum(1 for _, _, ch in kept.values() if ch)}",
        f"words with virama U+09CD: {sum(VIRAMA in w for w in words)}; khanda ta U+09CE: "
        f"{sum(chr(0x09CE) in w for w in words)}; ZWNJ/ZWJ: {sum(any(c in ALLOWED_EXTRA for c in w) for w in words)}",
        f"words with a Bengali Mn mark (virama, u/uu/vocalic-r signs, nukta, candrabindu): "
        f"{sum(any(ud.category(c) == 'Mn' for c in w) for w in words)}",
    ]
    (a.out / "report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))
    return 0 if not_upstream_opensub == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

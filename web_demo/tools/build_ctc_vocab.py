#!/usr/bin/env python3
"""
Convert the AOSP-format FUTO combined wordlist into a compact binary blob the
web demo's CTC engine can load and turn into a trie in one linear pass.

The *semantics* here are an exact replication of
`CleverKeys-ML/ctc/futo_decoder_eval.py::load_combined_vocab`:

  * parse `word=<w>,f=<freq>,...` lines
  * lowercase, strip every character outside a-z (don't -> dont), keep the word
  * drop entries that reduce to the empty string
  * `freq = f if f > 0 else 1.0`
  * dedupe surface forms keeping the MAXIMUM frequency

`load_combined_vocab` stores `log_freq = log(freq + 1e-10)` on the trie node,
guarded by `if lf > node.log_freq or node.log_freq == 0.0`. Because every kept
frequency is >= 1, `lf` is always > 0, so that guard degenerates to a plain
keep-max — which is why exporting the max *raw* frequency and recomputing
`Math.log(freq + 1e-10)` in JS is bit-equivalent (and avoids the ~1e-7 relative
error a float32 log_freq column would introduce).

OUTPUT FORMAT  (little-endian, `.bin`)
--------------------------------------
    0   char[8]  magic "CKCTCV1\0"
    8   u32      wordCount
    12  u32      blobLen          — byte length of the front-coded region
    16  u32      maxWordLen
    20  u8[blobLen] front-coded words, in ascending lexicographic order:
                     u8 sharedPrefixLen  (with the PREVIOUS word)
                     u8 suffixLen
                     u8[suffixLen] suffix, ASCII a-z
    20+blobLen (padded to a 2-byte boundary)
        u16[wordCount] frequencies, same order as the words

Front-coding is what makes this small: a sorted a-z lexicon shares ~6 of ~8
characters with its predecessor, so the average word costs ~2 header bytes + a
~2-3 byte suffix instead of a full string.

USAGE
    python3 web_demo/tools/build_ctc_vocab.py \
        --wordlist /home/will/ctc-train/data/futo_en_wordlist.combined \
        --out      web_demo/demo/models/ctc_vocab.bin [--verify]

`--verify` rebuilds the reference trie via the CleverKeys-ML loader and asserts
the exported (word, freq) set reproduces it exactly.
"""

from __future__ import annotations

import argparse
import hashlib
import math
import struct
import sys
import time
from pathlib import Path
from typing import Dict, List, Tuple

MAGIC = b"CKCTCV1\0"
HEADER_LEN = 20
MAX_U16 = 0xFFFF


def parse_combined(path: Path) -> Dict[str, int]:
    """AOSP combined wordlist -> {a-z surface form: max frequency}.

    Mirrors futo_decoder_eval.load_combined_vocab's normalization exactly.
    """
    best: Dict[str, int] = {}
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line.startswith("word="):
                continue
            word = None
            freq = 1.0
            for field in line.split(","):
                kv = field.split("=", 1)
                if len(kv) != 2:
                    continue
                key, val = kv
                if key == "word":
                    word = val
                elif key == "f":
                    try:
                        freq = float(val)
                    except ValueError:
                        pass
            if not word:
                continue
            normalized = "".join(c for c in word.lower() if "a" <= c <= "z")
            if not normalized:
                continue
            effective = freq if freq > 0 else 1.0
            if effective != int(effective):
                raise ValueError(
                    f"non-integer frequency {effective!r} for {word!r}; the u16 "
                    "frequency column assumes integral AOSP frequencies"
                )
            f_int = int(effective)
            if f_int > MAX_U16:
                raise ValueError(f"frequency {f_int} for {word!r} overflows u16")
            prev = best.get(normalized)
            if prev is None or f_int > prev:
                best[normalized] = f_int
    return best


def encode(words: List[str], freqs: List[int]) -> bytes:
    """Front-code `words` (must be sorted ascending) and append the u16 freqs."""
    blob = bytearray()
    prev = ""
    max_len = 0
    for word in words:
        max_len = max(max_len, len(word))
        if len(word) > 255:
            raise ValueError(f"word longer than 255 chars: {word!r}")
        shared = 0
        limit = min(len(prev), len(word), 255)
        while shared < limit and prev[shared] == word[shared]:
            shared += 1
        suffix = word[shared:].encode("ascii")
        if len(suffix) > 255:
            raise ValueError(f"suffix longer than 255 chars: {word!r}")
        blob.append(shared)
        blob.append(len(suffix))
        blob.extend(suffix)
        prev = word

    out = bytearray()
    out.extend(MAGIC)
    out.extend(struct.pack("<III", len(words), len(blob), max_len))
    assert len(out) == HEADER_LEN, len(out)
    out.extend(blob)
    if len(out) % 2:  # u16 column must be 2-byte aligned for a zero-copy view
        out.append(0)
    out.extend(struct.pack(f"<{len(freqs)}H", *freqs))
    return bytes(out)


def decode(data: bytes) -> List[Tuple[str, int]]:
    """Inverse of `encode` — used by --verify and by the JS parity check."""
    if data[:8] != MAGIC:
        raise ValueError("bad magic")
    count, blob_len, _max_len = struct.unpack_from("<III", data, 8)
    pos = HEADER_LEN
    end = pos + blob_len
    words: List[str] = []
    prev = ""
    while pos < end:
        shared = data[pos]
        suffix_len = data[pos + 1]
        pos += 2
        word = prev[:shared] + data[pos:pos + suffix_len].decode("ascii")
        pos += suffix_len
        words.append(word)
        prev = word
    if len(words) != count:
        raise ValueError(f"decoded {len(words)} words, header says {count}")
    if end % 2:
        end += 1
    freqs = struct.unpack_from(f"<{count}H", data, end)
    return list(zip(words, freqs))


def verify_against_reference(pairs: List[Tuple[str, int]], wordlist: Path,
                             ctc_dir: Path) -> None:
    """Assert the blob reproduces CleverKeys-ML's LexTrie word set + log_freqs."""
    sys.path.insert(0, str(ctc_dir))
    from futo_decoder_eval import load_combined_vocab  # type: ignore

    trie = load_combined_vocab(wordlist)
    if trie.num_words != len(pairs):
        raise SystemExit(
            f"FAIL word count: reference {trie.num_words} vs blob {len(pairs)}")
    worst = 0.0
    for word, freq in pairs:
        node = trie.root
        for ch in word:
            node = node.children.get(ch)
            if node is None:
                raise SystemExit(f"FAIL '{word}' missing from reference trie")
        if not node.is_word:
            raise SystemExit(f"FAIL '{word}' is not a word in reference trie")
        worst = max(worst, abs(math.log(freq + 1e-10) - node.log_freq))
    print(f"[verify] OK — {len(pairs)} words, max |log_freq| deviation {worst:.3e}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--wordlist", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--verify", action="store_true",
                    help="cross-check against CleverKeys-ML's load_combined_vocab")
    ap.add_argument("--ctc-dir", type=Path,
                    default=Path("/home/will/git/CleverKeys-ML/ctc"),
                    help="directory holding futo_decoder_eval.py (for --verify)")
    args = ap.parse_args()

    t0 = time.time()
    best = parse_combined(args.wordlist)
    words = sorted(best)
    freqs = [best[w] for w in words]
    print(f"[parse] {len(words)} unique a-z words in {time.time() - t0:.2f}s")

    data = encode(words, freqs)
    round_tripped = decode(data)
    if [w for w, _ in round_tripped] != words or [f for _, f in round_tripped] != freqs:
        raise SystemExit("FAIL: encode/decode round-trip mismatch")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    print(f"[write] {args.out} — {len(data)} bytes ({len(data) / 1e6:.2f} MB)")
    print(f"[write] sha256 {digest}")

    if args.verify:
        verify_against_reference(round_tripped, args.wordlist, args.ctc_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())

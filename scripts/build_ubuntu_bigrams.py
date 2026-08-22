#!/usr/bin/env python3
"""Derive bigram counts from the Ubuntu Dialogue Corpus, in the app's own export shape.

Why this exists
---------------
Step 5 of `docs/specs/ctc-context-rescoring-and-tunables.md` needs learned bigrams to seed a
`ContextModel` before measuring whether context rescoring helps. The maintainer's own device
export supplies REAL data but only ~642 usable pairs — one person's typing, thin by construction
(90.3% of its 6,589 pairs are hapax and contribute zero boost). A bulk corpus arm is what makes
the measurement generalise beyond a single user.

The output is deliberately written in the SAME JSON shape the app's dictionary export uses::

    {"learned_bigrams_by_language": {"en": [{"word1","word2","frequency","probability"}, ...]}}

so `LearnedBigramCorpus` loads either source with zero extra adapter code. One loader, two
corpora, no divergence in how they are interpreted.

Licence position — read before redistributing anything
------------------------------------------------------
The Ubuntu Dialogue Corpus data carries **no stated licence**. The widely-repeated "Apache 2.0"
label belongs to IBM's `ubuntu-ranking-dataset-creator` SCRIPT, which merely downloads the data
from McGill; the McGill v1.0 page states no terms for the corpus itself. The underlying text is
publicly published Ubuntu IRC logs with a decade of research redistribution behind it.

Consequence: this corpus is for **LOCAL evaluation**. Neither the raw dialogues nor this script's
output should be committed to a public repo. The script is committed; the data is not. Anyone can
reproduce the counts by running it.

Register, measured rather than assumed (29,175 sampled utterances)
------------------------------------------------------------------
median 8 words, p90 21 — message-shaped; 60.3% all-lowercase; 18.8% carry a contraction; 20.6%
are three words or fewer. 20.7% contain a tech/support term and 2.7% a URL, which the filter
below removes; ~68% survives as usable natural language. The top-20 words are all function words,
so the tech skew does not reach the high-frequency bigrams that matter most for near-tie breaking.

Usage
-----
    python3 scripts/build_ubuntu_bigrams.py \
        --archive ~/.cache/cleverkeys-corpora/ubuntu_dialogs.tgz \
        --out ~/.cache/cleverkeys-corpora/ubuntu_bigrams.json \
        --max-files 40000
"""
from __future__ import annotations

import argparse
import collections
import json
import re
import sys
import tarfile
from pathlib import Path

#: Mirrors BigramStore.DEFAULT_MIN_FREQUENCY — reported, NOT used to filter emission. Hapax rows
#: are emitted on purpose: the store ignores them for scoring but counts them in p(w2|w1), and
#: withholding them would inflate every probability the harness measures (audit H2).
MIN_FREQUENCY = 2

#: Mirrors BigramStore.MAX_BIGRAMS_PER_WORD — the store keeps only the top continuations per
#: first word, so emitting more would be discarded on load and would inflate the reported count.
MAX_PER_WORD = 20

#: Utterances outside this word range are dropped: one-word lines carry no bigram of interest,
#: and very long ones are desktop-composed prose rather than the message register we want.
MIN_WORDS, MAX_WORDS = 2, 30

_URL = re.compile(r"https?://|www\.")
_SHELL = re.compile(r"(sudo |apt-get|/etc/|/var/|/usr/|~/|\.conf\b|\.deb\b)")
_TECH = re.compile(
    r"\b(install|kernel|boot|grub|driver|package|repo|mount|chmod|sudo|apt|ubuntu|linux"
    r"|xorg|dpkg|partition|terminal|command|sda|hda|initrd|fstab)\b",
    re.I,
)
#: Words are letters plus word-internal apostrophes/hyphens, matching NextWordPredictor's
#: tokenizer contract so the derived keys are the ones the runtime will actually look up.
_WORD = re.compile(r"[a-z]+(?:['\-][a-z]+)*")


def usable_utterance(text: str) -> bool:
    """Is this line natural language a phone user might plausibly type?"""
    if _URL.search(text) or _SHELL.search(text) or _TECH.search(text):
        return False
    n = len(text.split())
    return MIN_WORDS <= n <= MAX_WORDS


def tokenize(text: str) -> list[str]:
    """Lowercased word tokens, keeping internal apostrophes and hyphens."""
    return _WORD.findall(text.lower())


def harvest(archive: Path, max_files: int) -> collections.Counter:
    """Stream the archive and count `(w1, w2)` pairs from usable utterances."""
    pairs: collections.Counter = collections.Counter()
    files = kept = seen = 0
    with tarfile.open(archive) as tf:
        for member in tf:
            if not member.isfile() or not member.name.endswith(".tsv"):
                continue
            files += 1
            if files > max_files:
                break
            handle = tf.extractfile(member)
            if handle is None:
                continue
            try:
                raw = handle.read().decode("utf-8", "replace")
            except Exception:  # a corrupt member must not lose the whole run
                continue
            for line in raw.splitlines():
                cols = line.split("\t")
                # Ubuntu .tsv rows are: timestamp, from, to, text. A row without a text
                # column is a join/part artefact, not an utterance.
                if len(cols) < 4:
                    continue
                text = cols[3].strip()
                if not text:
                    continue
                seen += 1
                if not usable_utterance(text):
                    continue
                kept += 1
                tokens = tokenize(text)
                for a, b in zip(tokens, tokens[1:]):
                    pairs[(a, b)] += 1
            if files % 20000 == 0:
                print(f"  … {files} files, {kept}/{seen} utterances kept, "
                      f"{len(pairs)} distinct pairs", file=sys.stderr)
    print(f"scanned {files} files: {kept}/{seen} utterances kept "
          f"({100 * kept / max(1, seen):.1f}%), {len(pairs)} distinct pairs", file=sys.stderr)
    return pairs


def to_export_shape(pairs: collections.Counter, language: str) -> dict:
    """Counter -> the app's `learned_bigrams_by_language` structure.

    `probability` is the conditional P(w2 | w1) over the retained continuations, matching what
    `BigramStore` computes on device. It is emitted for parity, but note the loader re-derives it
    by replaying `recordBigram`, so a mismatch here cannot silently corrupt the seeded store.
    """
    # H2 (audit): dropping hapax BEFORE emitting inflates every probability the seeded store
    # recomputes. `BigramStore` derives p(w2|w1) from its own observed totals, so if the corpus
    # withholds the ~90% tail mass a real device carries, the denominator shrinks and every boost
    # comes out larger than any state the app can reach — flattering both fix counts and rank-1
    # promotions, and contradicting the loader's promise to replay through the real learn path.
    #
    # So the tail is EMITTED, not dropped. The store's own floor discards it for scoring while
    # still counting it in the denominator, which is exactly the device's behaviour.
    by_first: dict[str, list[tuple[str, int]]] = collections.defaultdict(list)
    for (w1, w2), count in pairs.items():
        by_first[w1].append((w2, count))

    rows = []
    for w1, continuations in by_first.items():
        continuations.sort(key=lambda kv: (-kv[1], kv[0]))
        # Probability over ALL observations of w1, including the hapax tail — matching what
        # BigramStore computes on device. The top-N cut applies to what is EMITTED, not to the
        # denominator.
        total = sum(c for _, c in continuations)
        top = continuations[:MAX_PER_WORD]
        for w2, count in top:
            rows.append({
                "word1": w1,
                "word2": w2,
                "frequency": count,
                "probability": round(count / total, 6) if total else 0.0,
            })
    rows.sort(key=lambda r: (-r["frequency"], r["word1"], r["word2"]))
    return {"learned_bigrams_by_language": {language: rows}}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--archive", type=Path, required=True, help="ubuntu_dialogs.tgz")
    ap.add_argument("--out", type=Path, required=True, help="output JSON (do NOT commit)")
    ap.add_argument("--language", default="en")
    ap.add_argument("--max-files", type=int, default=40_000,
                    help="dialogue files to scan; the corpus has ~280k (default 40k)")
    args = ap.parse_args()

    if not args.archive.is_file():
        raise SystemExit(f"no archive at {args.archive}")

    pairs = harvest(args.archive, args.max_files)
    payload = to_export_shape(pairs, args.language)
    rows = payload["learned_bigrams_by_language"][args.language]
    args.out.write_text(json.dumps(payload), encoding="utf-8")

    usable = sum(1 for r in rows if r["frequency"] >= MIN_FREQUENCY)
    print(f"wrote {len(rows)} bigrams ({usable} above the store floor, "
          f"{len(rows) - usable} tail rows kept so denominators match a device) to {args.out}")
    print("top 10:")
    for r in rows[:10]:
        print(f"   {r['word1']:>10} -> {r['word2']:<12} freq={r['frequency']:<6} "
              f"p={r['probability']:.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

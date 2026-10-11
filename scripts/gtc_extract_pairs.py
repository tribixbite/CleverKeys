#!/usr/bin/env python3
"""Extract single-word typo -> correction pairs from the GitHub Typo Corpus (GTC).

For docs/eval/2026-10-11-dropped-double-margin.md (smarter dropped-doubled-letter rule).

Source: GitHub Typo Corpus v1.0.0 (Hagiwara & Mita, LREC 2020,
https://github.com/mhagiwara/github-typo-corpus). The original S3 URL no longer resolves; the
file was recovered from the Wayback Machine:
https://web.archive.org/web/20260217103607id_/https://github-typo-corpus.s3.amazonaws.com/data/github-typo-corpus.v1.0.0.jsonl.gz
(43,769,081 bytes, sha256 98219d27...2909d). Terms (README "Terms"): the copyright and licence
of each commit/text follow the repository it came from; the corpus is published under GitHub's
Acceptable Use Policies (scraping and API usage). LOCAL EVALUATION ONLY: the corpus and the
pairs file written here are never committed or packaged.

Filter (all must hold):
  * edit has is_typo == true and src.lang == tgt.lang == "eng";
  * both lines split on whitespace into the SAME number of tokens; after stripping surrounding
    punctuation, exactly one token differs (case-insensitively);
  * both differing tokens are ASCII letters only and not camelCase (all lower, Capitalised, or
    ALL CAPS), so code identifiers like `lastName` are skipped; both are lowercased.
Dictionary filtering (target in en_enhanced, typo not in it, not a contraction-alias key) is
done by the Kotlin harness, which owns the real dictionary and alias table.

Split (decided before any measurement): by commit hash, DEV when the last hex digit of the
commit SHA is even, TEST when odd. Within a split, a (typo, target) pair is kept once per
commit hash; the number of DISTINCT commit hashes it occurs in is written as `occurrences`
(forked repositories repeat the same commit, which is counted once).

Output TSV (default ~/.cache/cleverkeys-test/misspell/gtc-pairs.tsv):
  split  typo  target  occurrences  commits(comma-separated, first 5)
Prints the extraction counts to stdout.
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
from collections import Counter, defaultdict

LETTERS = re.compile(r"^[A-Za-z]+$")
STRIP = ".,;:!?\"'()[]{}<>*_`~#|/\\=+-"


def core(token: str) -> str:
    """Token with surrounding punctuation removed (inner characters untouched)."""
    return token.strip(STRIP)


def plain_word(token: str) -> bool:
    """ASCII letters only and not camelCase: lower, Capitalised, or ALL CAPS."""
    if not LETTERS.match(token):
        return False
    return token == token.lower() or token == token.upper() or token == token.capitalize()


def single_word_edit(src: str, tgt: str) -> tuple[str, str] | None:
    """The one (typo, correction) word pair of an edit, or None when the edit is not exactly that."""
    a, b = src.split(), tgt.split()
    if len(a) != len(b):
        return None
    diff: tuple[str, str] | None = None
    for x, y in zip(a, b):
        cx, cy = core(x), core(y)
        if cx.lower() == cy.lower():
            if x != y and cx == cy:
                return None  # punctuation-only change on another token: not a pure word fix
            continue
        if diff is not None:
            return None  # a second differing token
        diff = (cx, cy)
    if diff is None:
        return None
    typo, target = diff
    if not (plain_word(typo) and plain_word(target)):
        return None
    return typo.lower(), target.lower()


def split_of(commit: str) -> str:
    """Deterministic DEV/TEST assignment by commit hash (last hex digit parity)."""
    return "DEV" if int(commit[-1], 16) % 2 == 0 else "TEST"


def main() -> None:
    default_dir = os.path.expanduser("~/.cache/cleverkeys-test/misspell")
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--corpus", default=os.path.join(default_dir, "github-typo-corpus.v1.0.0.jsonl.gz"))
    ap.add_argument("--out", default=os.path.join(default_dir, "gtc-pairs.tsv"))
    args = ap.parse_args()

    stats: Counter[str] = Counter()
    # (split, typo, target) -> list of commits
    pairs: dict[tuple[str, str, str], list[str]] = defaultdict(list)
    commits_by_split: Counter[str] = Counter()
    with gzip.open(args.corpus, "rt", encoding="utf-8") as fh:
        for line in fh:
            rec = json.loads(line)
            stats["commits"] += 1
            commit = rec["commit"]
            sp = split_of(commit)
            commits_by_split[sp] += 1
            seen: set[tuple[str, str]] = set()
            for e in rec.get("edits", []):
                stats["edits"] += 1
                if e.get("src", {}).get("lang") != "eng" or e.get("tgt", {}).get("lang") != "eng":
                    continue
                stats["edits_eng"] += 1
                if e.get("is_typo") is not True:
                    continue
                stats["edits_eng_typo"] += 1
                p = single_word_edit(e["src"]["text"], e["tgt"]["text"])
                if p is None or p[0] == p[1]:
                    continue
                stats["single_word_edits"] += 1
                if p in seen:
                    continue
                seen.add(p)
                cs = pairs[(sp, p[0], p[1])]
                if commit not in cs:  # forks repeat a commit hash across repo records
                    cs.append(commit)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as out:
        out.write("split\ttypo\ttarget\toccurrences\tcommits\n")
        for (sp, typo, target), cs in sorted(pairs.items()):
            out.write(f"{sp}\t{typo}\t{target}\t{len(cs)}\t{','.join(cs[:5])}\n")

    for k in ("commits", "edits", "edits_eng", "edits_eng_typo", "single_word_edits"):
        print(f"{k}: {stats[k]}")
    for sp in ("DEV", "TEST"):
        ps = [(t, g) for (s, t, g) in pairs if s == sp]
        occ = sum(len(cs) for (s, _, _), cs in pairs.items() if s == sp)
        print(f"{sp}: commits={commits_by_split[sp]} pairs={len(ps)} "
              f"targets={len({g for _, g in ps})} occurrences={occ}")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()

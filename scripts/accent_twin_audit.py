#!/usr/bin/env python3
"""Accent-twin audit for a language word list (the 2026-11-19 Italian method, for any language).

wordfreq counts the web's unaccented typing (`francais`, `tambien`, `nao`) as words of their own,
and the word-list classifier can keep such a spelling beside its accented form. Since the CTC
collision alternates (2026-11-18) a swipe then offers the misspelling as a second form, and a
typed misspelling is a dictionary word that nothing corrects.

This lists the candidates; it decides nothing. For every group of list words that share an
accent-stripped form, a member is a candidate when no hunspell oracle accepts it, neither in
lowercase nor Capitalised, while one accepts another member of the group in either form. The
Capitalised probe keeps names (the Italian `sara`/`Sara` case) and German nouns (the lexicon is
lowercase-only, so `bär` stands for `Bär`) out of the candidates. The reviewed
drops go to `scripts/dictionaries/<lang>/<lang>_blocklist.txt`, each with its standard spelling
after `#`; the keep decisions and their reasons go to the language's eval note and drift test.

Usage:
  python3 scripts/accent_twin_audit.py --words scripts/dictionaries/fr/fr_words.txt \
      --oracle <dir>/fr_TV --out fr-candidates.tsv
`--oracle` is a hunspell dictionary path without extension; repeat it for a union (pt_BR + pt_PT).
Output columns: candidate, accepted twins (`/`-joined), BLOCKED when the language's blocklist
already drops it.
"""
from __future__ import annotations

import argparse
import collections
import subprocess
import sys
import unicodedata
from pathlib import Path


def strip_accents(word: str) -> str:
    """The word with every combining mark removed (NFD), the CTC a–z surface for Latin."""
    return "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn")


def read_words(path: Path) -> list[str]:
    """A word list: one word per line, `#` comment lines skipped."""
    with path.open(encoding="utf-8") as f:
        return [s for s in (line.strip() for line in f) if s and not s.startswith("#")]


def read_blocklist(path: Path) -> set[str]:
    """The words a `<lang>_blocklist.txt` drops (text before any `#`)."""
    if not path.exists():
        return set()
    with path.open(encoding="utf-8") as f:
        return {w for w in (line.split("#", 1)[0].strip() for line in f) if w}


def accepted(tokens: list[str], oracles: list[str]) -> set[str]:
    """The tokens at least one hunspell oracle accepts (`hunspell -G` echoes accepted words).

    Fails loudly when hunspell cannot run: a silent empty set would mark every word a candidate.
    """
    ok: set[str] = set()
    for oracle in oracles:
        run = subprocess.run(
            ["hunspell", "-d", oracle, "-G"], input="\n".join(tokens),
            capture_output=True, text=True, check=True,
        )
        ok.update(line for line in run.stdout.split("\n") if line)
    if not ok:
        sys.exit(f"hunspell accepted nothing with {oracles}: check the dictionary paths")
    return ok


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--words", type=Path, required=True, help="<lang>_words.txt")
    ap.add_argument("--oracle", action="append", required=True, help="hunspell dictionary path without extension")
    ap.add_argument("--out", type=Path, required=True, help="candidate TSV")
    args = ap.parse_args()

    words = read_words(args.words)
    lang = args.words.stem.split("_", 1)[0]
    blocked = read_blocklist(args.words.parent / f"{lang}_blocklist.txt")

    groups: dict[str, list[str]] = collections.defaultdict(list)
    for w in words:
        groups[strip_accents(w)].append(w)
    collisions = [g for g in groups.values() if len(g) > 1]
    members = sorted({w for g in collisions for w in g})
    lower_ok = accepted(members, args.oracle)
    cap_ok = accepted([m[:1].upper() + m[1:] for m in members], args.oracle)

    def valid(w: str) -> bool:
        return w in lower_ok or (w[:1].upper() + w[1:]) in cap_ok

    rows: list[tuple[str, str, str]] = []
    for group in collisions:
        twins = [w for w in group if valid(w)]
        if not twins:
            continue
        for w in group:
            if not valid(w):
                rows.append((w, "/".join(twins), "BLOCKED" if w in blocked else ""))
    rows.sort()
    with args.out.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write("\t".join(row) + "\n")
    print(
        f"{lang}: {len(words)} words, {len(collisions)} accent groups, {len(rows)} candidates "
        f"({sum(1 for r in rows if r[2])} already blocked) -> {args.out}"
    )


if __name__ == "__main__":
    main()

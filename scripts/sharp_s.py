#!/usr/bin/env python3
"""
German ß for `build_wordlist.py` (2026-11-25).

wordfreq CASEFOLDS its corpus, and Python's casefold turns `ß` into `ss`: its German stream spells
`strasse`, `grösse`, `weiss` and holds no `ß` at all, so the German dictionary had none either
(found on the Saga: a tapped `groß` + space committed `gros`). This module puts `ß` back where
German as written in Germany and Austria has it, using two pieces of evidence per stream word that
contains `ss`:

- an ORACLE: the set of spellings a German spellchecker accepts (the builder passes LibreOffice's
  de_DE_frami, which knows compounds and the 1996 reform: `Kreisstraße`, `Flusskilometer`, `dass`
  are accepted, `Flußkilometer`, `daß`, `Strasse` are not);
- the COUNTS of each spelling in a corpus that keeps `ß` (Leipzig's German web 2021, lowercased).

The decision for a stream word `w` and its readings (every `ss` may be `ß`):

| oracle accepts            | result                                                     |
|---------------------------|------------------------------------------------------------|
| `w` only                  | `w` (`dass`, `muss`, `wissen`)                               |
| one or more ß readings    | the reading the corpus shows most (`straße`, `größe`)        |
| `w` and ß readings        | both real words (`Masse`/`Maße`): the stream frequency is    |
|                           | split by their corpus counts; a reading the corpus never     |
|                           | shows is left out; no counts at all keeps `w`                |
| ß readings, not `w`, but  | a name spelled both ways (`Hess` 5 : `Heß` 3): split as      |
| the corpus writes `w` a   | above                                                        |
| third of the time or more |                                                              |
| none                      | a name or a word the oracle lacks: the ß reading only on a   |
|                           | clear corpus majority (`Gauß` 9 : 1), else `w`               |

The Swiss spelling (no `ß`) is not served by this list: a typed `strasse` is a correction to
`straße`, the way a typed `fur` is one to `für`.

License: GPL-3.0 (part of CleverKeys)
"""

from __future__ import annotations

import io
import tarfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Collection, Mapping

#: A ß reading the oracle does not know needs this many corpus occurrences…
CORPUS_MIN_COUNT = 3
#: …and more than this many times the stream spelling's own count.
CORPUS_MAJORITY = 2
#: A spelling the oracle rejects stays beside its ß reading (both, split by count) when the corpus
#: writes it at least CORPUS_MIN_COUNT times and at least this share of the time: a name spelled
#: both ways (Hess 5 : Heß 3), never the Swiss or typed-without-ß minority (strasse 4 of 462).
NAME_TWIN_SHARE = 1 / 3


def sharp_s_variants(word: str) -> "list[str]":
    """Every spelling of [word] in which some `ss` is read as `ß`, [word] itself excluded.

    `sss` (Schlossstraße) yields both readings of its overlapping pairs; spellings shorter than
    two letters are never returned. Sorted, so the result is deterministic.
    """
    out: "set[str]" = set()

    def walk(i: int, acc: str) -> None:
        if i >= len(word):
            out.add(acc)
            return
        if word.startswith("ss", i):
            walk(i + 2, acc + "ß")
        walk(i + 1, acc + word[i])

    if "ss" in word:
        walk(0, "")
    out.discard(word)
    return sorted(v for v in out if len(v) >= 2)


@dataclass(frozen=True)
class Respelling:
    """What one stream word becomes: (spelling, share of its frequency) pairs, most common first."""
    spellings: "tuple[tuple[str, float], ...]"
    reason: str  # plain | ss | ß | split | corpus-ß


def _by_count(words: "Collection[str]", counts: "Mapping[str, int]") -> "list[str]":
    return sorted(words, key=lambda v: (-counts.get(v, 0), v))


def respell(word: str, accepted: "Collection[str]", counts: "Mapping[str, int]") -> Respelling:
    """The spelling(s) [word] (a casefolded stream word) stands for; see the module table.

    [accepted] holds the lowercase spellings the oracle accepts (in lowercase or capitalised);
    [counts] the corpus count of each lowercase spelling.
    """
    variants = sharp_s_variants(word)
    if not variants:
        return Respelling(((word, 1.0),), "plain")
    valid = [v for v in variants if v in accepted]
    if word in accepted:
        if not valid:
            return Respelling(((word, 1.0),), "ss")
        members = [m for m in _by_count([word, *valid], counts) if counts.get(m, 0) > 0]
        total = sum(counts.get(m, 0) for m in members)
        if not members:
            return Respelling(((word, 1.0),), "ss")
        if len(members) == 1:
            only = members[0]
            return Respelling(((only, 1.0),), "ss" if only == word else "ß")
        return Respelling(tuple((m, counts[m] / total) for m in members), "split")
    if valid:
        chosen = _by_count(valid, counts)[0]
        own, theirs = counts.get(word, 0), counts.get(chosen, 0)
        if own >= CORPUS_MIN_COUNT and theirs > 0 and own / (own + theirs) >= NAME_TWIN_SHARE:
            members = _by_count([word, chosen], counts)
            return Respelling(tuple((m, counts[m] / (own + theirs)) for m in members), "split")
        return Respelling(((chosen, 1.0),), "ß")
    best = _by_count(variants, counts)[0]
    seen = counts.get(best, 0)
    if seen >= CORPUS_MIN_COUNT and seen > CORPUS_MAJORITY * counts.get(word, 0):
        return Respelling(((best, 1.0),), "corpus-ß")
    return Respelling(((word, 1.0),), "ss")


@dataclass
class RespelledStream:
    """A ranked stream after respelling."""
    ranked: "list[str]"                       # spellings in stream order (a split adds one)
    rank_of: "dict[str, int]"                 # spelling -> its stream word's rank
    share: "dict[str, float]" = field(default_factory=dict)  # split spellings only
    respelled: "dict[str, tuple[str, ...]]" = field(default_factory=dict)  # stream word -> spellings


def respell_ranked(ranked: "list[str]", decisions: "Mapping[str, Respelling]") -> RespelledStream:
    """Apply [decisions] (stream word -> Respelling; absent = unchanged) to a ranked stream.

    Each spelling takes its stream word's rank, so a split pair shares one rank and no other
    word's rank moves. A spelling reached twice keeps its first (best) rank.
    """
    out = RespelledStream(ranked=[], rank_of={})
    for rank, w in enumerate(ranked):
        d = decisions.get(w)
        spellings = d.spellings if d is not None else ((w, 1.0),)
        if d is not None and [s for s, _ in spellings] != [w]:
            out.respelled[w] = tuple(s for s, _ in spellings)
        for s, share in spellings:
            if s in out.rank_of:
                continue
            out.ranked.append(s)
            out.rank_of[s] = rank
            if len(spellings) > 1:
                out.share[s] = share
    return out


def leipzig_word_counts(tarball: "Path") -> "Counter[str]":
    """Lowercased token counts from a Leipzig corpus tarball's `*-words.txt` (id, word, count).

    The counts keep `ß` (Leipzig does not casefold); `Straße` and `straße` add up.
    """
    counts: "Counter[str]" = Counter()
    with tarfile.open(tarball, "r:gz") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("-words.txt"))
        fh = tar.extractfile(member)
        assert fh is not None
        for raw in io.TextIOWrapper(fh, encoding="utf-8", errors="replace"):
            cols = raw.rstrip("\n").split("\t")
            if len(cols) == 3 and cols[2].isdigit():
                counts[cols[1].lower()] += int(cols[2])
    return counts

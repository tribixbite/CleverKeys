#!/usr/bin/env python3
"""
Corpus-blend candidate source for `build_wordlist.py` (first user: Persian `fa`, 2026-10-23).

`build_wordlist.py` normally takes its candidate stream and its frequencies from wordfreq. That
does not work for Persian: wordfreq's `fa` list is 31,389 words, 2,024 of them are Arabic-letter
duplicates (ي/ك) and 48 carry a half-space (ZWNJ, U+200C) at the word edge
(docs/research/2026-10-21-persian-zwnj.md, "The data"). A configured language with
`corpus=<name>` instead gets its candidates from a BLEND of pinned Leipzig sentence corpora plus
wordfreq, all normalised by one per-script profile. Everything else in the classifier (oracles,
typo and foreign negatives, bands, size cap, guards, review artifacts, CKDT write) is shared.

Pipeline (deterministic; the same pinned inputs give the same counts):

 1. fetch     : Leipzig tarballs pinned by sha256 (`build_static_lm.ensure_source`, cache
                ~/.cache/cleverkeys-corpora/leipzig/). Never committed.
 2. tokenize  : a raw token is a maximal run of letters, marks, digits and the invisible format
                characters; everything else (spaces, punctuation, «», ، ؛ ؟) separates. A token
                that mixes in a digit, a Latin letter or any non-alphabet character is REJECTED
                WHOLE, never split, so a corpus-side mid-word break cannot leak a fragment.
 3. normalise : NFC; drop invisible direction/BOM/soft-hyphen marks; the profile's letter fold
                (Persian: ي→ی ك→ک ى→ی ۀ→ه ة→ه ە→ه — never آ or the hamza forms); strip combining
                marks (harakat, ezafe hamza, superscript alef) WITHOUT decomposing, so آ/أ/ؤ/ئ/إ
                survive; collapse ZWNJ runs and strip edge ZWNJ (a bare ZWNJ token is dropped);
                reject tatweel, repeated-letter noise (a letter 3+ times in a row) and one-letter
                tokens other than the profile's one-letter words (Persian: و).
 4. split     : 10 % of sentences are HELD OUT by sentence hash (`build_static_lm.is_heldout`;
                identical sentences in two corpora land on the same side) and never counted;
                they measure token coverage of the final list.
 5. attest    : a corpus source attests a word with >= `min_count` training occurrences; the
                wordfreq source attests a word it lists. A word needs `min_sources` attesting
                sources, so a single-corpus artefact cannot enter.
 6. blend     : each source's relative frequency, averaged over ALL sources (absent = 0), is
                the word's frequency; zipf = log10(freq) + 9, the wordfreq convention.

Counting ~3M sentences takes minutes, so the counts are cached in the corpus cache keyed by the
source hashes and `PROFILE_VERSION`; bump the version whenever normalisation changes.

Usage (normally driven by build_wordlist.py; the CLI prints the counts and the coverage curve):
    python3 scripts/corpus_blend.py --corpus fa

License: GPL-3.0 (part of CleverKeys)
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import re
import shutil
import subprocess
import sys
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
from build_static_lm import (  # noqa: E402
    CACHE, Source, ensure_source, is_heldout, leipzig_lines, sentence_key,
)

ZWNJ = "‌"

# Bump when tokenisation/normalisation changes: it is part of the count-cache key.
PROFILE_VERSION = "fa-2026-10-23.1"


# ── script profile ──────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class ScriptProfile:
    """How one script's corpus tokens become dictionary words."""
    name: str
    letters: frozenset            # the alphabet a word may use (plus `joiner` inside a word)
    fold: dict                    # str.maketrans table applied before mark stripping
    joiner: "str | None"          # a word-internal joiner kept in words (Persian: ZWNJ)
    one_letter_words: frozenset   # one-letter tokens that are real words
    reject_chars: frozenset       # characters whose presence rejects the whole token

    def is_word(self, w: str) -> bool:
        """A normalised word: alphabet letters, optional single internal joiners, no edge joiner."""
        if not w:
            return False
        j = self.joiner
        if j is not None:
            if w[0] == j or w[-1] == j or (j + j) in w:
                return False
        return all(c in self.letters or c == j for c in w)


# The Persian alphabet: 32 letters, آ, and the hamza forms ء أ إ ؤ ئ. Never folded (CLDR keeps آ a
# primary letter; آب/اب are different words — research §4, §7c).
PERSIAN_LETTERS = frozenset("ابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی" "آ" "ءأإؤئ")

# The exact fold the app's normaliser uses (coordinated with the Kotlin normalisation work,
# 2026-10-23): ي→ی, ك→ک, ى→ی, U+06C0 ۀ→ه, ة→ه, U+06D5 ە→ه. Never آ or hamza.
PERSIAN_FOLD = str.maketrans({
    "ي": "ی",  # ي ARABIC LETTER YEH → ی FARSI YEH
    "ك": "ک",  # ك ARABIC LETTER KAF → ک KEHEH
    "ى": "ی",  # ى ALEF MAKSURA → ی FARSI YEH
    "ۀ": "ه",  # ۀ HEH WITH YEH ABOVE → ه HEH
    "ة": "ه",  # ة TEH MARBUTA → ه HEH
    "ە": "ه",  # ە AE → ه HEH
})

PERSIAN = ScriptProfile(
    name="persian",
    letters=PERSIAN_LETTERS,
    fold=PERSIAN_FOLD,
    joiner=ZWNJ,
    one_letter_words=frozenset({"و"}),  # "and"; no other one-letter Persian word is in use
    reject_chars=frozenset({"ـ", "‍"}),  # tatweel; ZWJ (never part of a Persian word)
)

PROFILES = {"persian": PERSIAN}

# Persian ی/ک are written ي/ك in Arabic, and a word-final ی is often ى there (علی / على).
_TO_ARABIC = str.maketrans({"ی": "ي", "ک": "ك"})


def foreign_spellings(lang: str, foreign: str, w: str) -> "list[str]":
    """How `w` (a `lang` dictionary word) is spelled in the `foreign` wordfreq list.

    The foreign-dominance filter compares zipf in the other language; for fa against ar the
    folded Persian spelling would never match an Arabic entry. Every other pair compares as is.
    """
    if lang == "fa" and foreign == "ar":
        a = w.translate(_TO_ARABIC)
        forms = [a]
        if a.endswith("ي"):
            forms.append(a[:-1] + "ى")
        return forms
    return [w]

# Invisible marks that are never part of a word: LRM, RLM, LRE..RLO, LRI..PDI, BOM, soft hyphen.
INVISIBLE = dict.fromkeys(
    [0x200E, 0x200F, *range(0x202A, 0x202F), *range(0x2066, 0x206A), 0xFEFF, 0x00AD])

# A raw token: letters/digits (\w minus "_"), combining marks of the Arabic and Latin blocks,
# and the zero-width / bidi / tatweel format characters. Everything else separates tokens.
RAW_TOKEN = re.compile(
    "(?:[^\\W_]|[̀-ͯؐ-ًؚ-ٰٟۖ-ۭ"
    "‌‍‎‏‪-‮⁦-⁩﻿­ـ])+")
REPEAT3 = re.compile(r"(.)\1\1")
MULTI_JOINER = re.compile(ZWNJ + "{2,}")


def normalise(raw: str, profile: ScriptProfile) -> "tuple[str | None, str]":
    """(word, reason) for one raw token; word is None when the token is rejected.

    Reasons: ok | edge-joiner (kept, edge ZWNJ stripped) | bare-joiner | digit | latin | reject-char
    | foreign-char | repeat | one-letter.
    """
    t = unicodedata.normalize("NFC", raw).translate(INVISIBLE).translate(profile.fold)
    # Strip combining marks without decomposing: آ أ إ ؤ ئ are precomposed letters and stay.
    t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    if any(c in profile.reject_chars for c in t):
        return None, "reject-char"
    reason = "ok"
    j = profile.joiner
    if j is not None and j in t:
        t = MULTI_JOINER.sub(j, t)
        stripped = t.strip(j)
        if not stripped:
            return None, "bare-joiner"
        if stripped != t:
            reason = "edge-joiner"
        t = stripped
    if any(c.isdigit() for c in t):
        return None, "digit"
    if any("a" <= c.lower() <= "z" or "LATIN" in unicodedata.name(c, "") for c in t):
        return None, "latin"
    if not profile.is_word(t):
        return None, "foreign-char"
    if REPEAT3.search(t):
        return None, "repeat"
    if len(t) == 1 and t not in profile.one_letter_words:
        return None, "one-letter"
    return t, reason


# ── blend configuration ─────────────────────────────────────────────────────────────────────


def _leipzig(corpus: str, sha256: str, note: str) -> Source:
    return Source(
        key=corpus,
        filename=f"leipzig/{corpus}.tar.gz",
        url=f"https://downloads.wortschatz-leipzig.de/corpora/{corpus}.tar.gz",
        sha256=sha256,
        license="CC BY 4.0 (Leipzig Corpora Collection download terms)",
        shipped=True,
        note=note,
    )


@dataclass(frozen=True)
class CorpusSource:
    """One attesting source: one or more Leipzig tarballs of the same register."""
    key: str
    files: "tuple[Source, ...]"


@dataclass(frozen=True)
class BlendConfig:
    """Everything a corpus-blend language reads."""
    lang: str
    profile: ScriptProfile
    corpora: "tuple[CorpusSource, ...]"
    wordfreq_lang: "str | None"   # also blended as one source (None = corpora only)
    min_count: int = 3            # training occurrences for a corpus source to attest a word
    min_sources: int = 2          # attesting sources a word needs
    coverage_points: "tuple[int, ...]" = (20_000, 30_000, 50_000, 80_000)
    # Hunspell oracle built from a pinned upstream (see `ensure_hunspell`); None = no oracle.
    hunspell: "str | None" = None


# Persian sources (2026-10-23). Leipzig's Persian news corpora from 2020 on, and web-public 2019,
# have every ZWNJ STRIPPED (0 of 50,000 sentences carry one; "شدهاند", "پالایشگاههای") — measured
# per corpus in docs/eval/2026-10-23-fa-wordlist.md — so only the ZWNJ-preserving corpora are
# used: news 2016-2018, news crawl 2019, Wikipedia 2014.
FA_BLEND = BlendConfig(
    lang="fa",
    profile=PERSIAN,
    corpora=(
        CorpusSource("leipzig-news", (
            _leipzig("fas_news_2016_300K", "48fc256187c35a5b4a016d655ea962511f7b29c502af121ae5e6a940835a64e5",
                     "Persian news 2016, 300K sentences"),
            _leipzig("fas_news_2017_300K", "9c0b57241c698ede3eb1e8645b741bea27815e51d0520aa99c1be2fff612fa09",
                     "Persian news 2017, 300K sentences"),
            _leipzig("fas_news_2018_300K", "2ab1164e210fbcd21743779f9392cc4a0e2a3143eaa403f2011d74368ee01d6e",
                     "Persian news 2018, 300K sentences"),
        )),
        CorpusSource("leipzig-newscrawl", (
            _leipzig("fas_newscrawl_2019_1M", "86f200ae416fa1842711fe3585058a2d0a2763e49b11e5e8c6b75688a37c91b3",
                     "Persian news crawl 2019, 1M sentences"),
        )),
        CorpusSource("leipzig-wikipedia", (
            _leipzig("fas_wikipedia_2014_1M", "123da05baca67a11df0780bba699af3094f6a14a3c68a0caab3517c1f07cd3ab",
                     "Persian Wikipedia 2014, 1M sentences"),
        )),
    ),
    wordfreq_lang="fa",
    hunspell="lilak",
)

BLENDS = {"fa": FA_BLEND}


# ── hunspell oracle from a pinned upstream ──────────────────────────────────────────────────

# Lilak (b00f/lilak, Apache-2.0): a Persian lexicon with POS tags plus Hunspell affix rules that
# carry ZWNJ (prefix بی‌, suffixes ‌ها ‌ام ‌ات …). Built here into a hunspell dictionary; WORDCHARS
# adds ZWNJ so hunspell checks `می‌شود` as ONE word instead of splitting it at the half-space.
LILAK_REPO = "https://github.com/b00f/lilak.git"
LILAK_COMMIT = "4f1f426113e764ea099865232fd5e0fa34563c60"  # master, 2026-07-04


def ensure_hunspell(name: str, cache: Path = CACHE) -> str:
    """Path prefix of a hunspell dictionary (`<prefix>.dic`/`.aff`) for `hunspell -d`.

    Clones the pinned commit and runs its own build into the corpus cache once. Fails loud: a
    configured oracle that cannot be built must never silently become "no oracle".
    """
    if name != "lilak":
        sys.exit(f"unknown hunspell provider {name!r}")
    root = cache / "lilak"
    out = root / f"fa-IR-{LILAK_COMMIT[:12]}"
    if out.with_suffix(".dic").exists() and out.with_suffix(".aff").exists():
        return str(out)
    repo = root / f"repo-{LILAK_COMMIT[:12]}"
    if not repo.exists():
        root.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "-q", LILAK_REPO, str(repo)], check=True)
        subprocess.run(["git", "-C", str(repo), "checkout", "-q", LILAK_COMMIT], check=True)
    subprocess.run([sys.executable, "lilak.py"], cwd=repo / "src", check=True,
                   capture_output=True, text=True)
    built = repo / "build"
    shutil.copyfile(built / "fa-IR.dic", out.with_suffix(".dic"))
    aff = (built / "fa-IR.aff").read_text(encoding="utf-8")
    out.with_suffix(".aff").write_text(aff.rstrip("\n") + f"\nWORDCHARS {ZWNJ}\n", encoding="utf-8")
    return str(out)


# ── counting ────────────────────────────────────────────────────────────────────────────────


@dataclass
class SourceCounts:
    """Training counts of one source, plus what was rejected and why."""
    key: str
    counts: Counter = field(default_factory=Counter)   # normalised word -> count
    total: int = 0                                     # valid (normalised) training tokens
    reasons: Counter = field(default_factory=Counter)  # normalise() reason -> raw-token count
    sentences: int = 0
    heldout_sentences: int = 0


@dataclass
class Blend:
    """The blend result consumed by build_wordlist.py."""
    config: BlendConfig
    sources: "list[SourceCounts]"
    heldout: Counter            # normalised held-out word -> count (corpus sources only)
    heldout_total: int          # valid held-out tokens
    freq: "dict[str, float]"    # attested word -> blended relative frequency
    attest: "dict[str, int]"    # attested word -> number of attesting sources
    single_source: int          # words seen in some source but attested by fewer than min_sources

    def zipf(self, w: str) -> float:
        f = self.freq.get(w, 0.0)
        return math.log10(f) + 9.0 if f > 0 else 0.0

    def ranked(self) -> "list[str]":
        """Attested words, most frequent first (ties by word, so the order is deterministic)."""
        return sorted(self.freq, key=lambda w: (-self.freq[w], w))


def _cache_key(cfg: BlendConfig, hashes: "list[str]") -> str:
    h = hashlib.sha256()
    for part in [PROFILE_VERSION, cfg.lang, *hashes]:
        h.update(part.encode() + b"\0")
    return h.hexdigest()[:16]


def _count_corpus(src: CorpusSource, cfg: BlendConfig, cache: Path,
                  heldout_raw: Counter) -> "tuple[SourceCounts, Counter]":
    """Raw-token training Counter of one source; held-out raw tokens go to `heldout_raw`."""
    sc = SourceCounts(src.key)
    raw = Counter()
    for f in src.files:
        path, _ = ensure_source(f, cache, allow_unpinned=False, offline=False)
        for text, _who in leipzig_lines(path):
            toks = RAW_TOKEN.findall(text)
            if not toks:
                continue
            if is_heldout(sentence_key(text)):
                sc.heldout_sentences += 1
                heldout_raw.update(toks)
            else:
                sc.sentences += 1
                raw.update(toks)
    return sc, raw


def _normalise_counter(raw: Counter, profile: ScriptProfile, memo: dict,
                       reasons: "Counter | None") -> "tuple[Counter, int]":
    out: Counter = Counter()
    total = 0
    for tok, n in raw.items():
        hit = memo.get(tok)
        if hit is None:
            hit = memo[tok] = normalise(tok, profile)
        w, why = hit
        if reasons is not None:
            reasons[why] += n
        if w is not None:
            out[w] += n
            total += n
    return out, total


def build_blend(cfg: BlendConfig, cache: Path = CACHE, verbose: bool = True) -> Blend:
    """Count (or load cached counts), attest and blend. Deterministic for pinned inputs."""
    hashes = [f.sha256 for c in cfg.corpora for f in c.files]
    ck = cache / f"blend-{cfg.lang}-{_cache_key(cfg, hashes)}.json.gz"
    sources: "list[SourceCounts]" = []
    if ck.exists():
        data = json.loads(gzip.decompress(ck.read_bytes()))
        for s in data["sources"]:
            sources.append(SourceCounts(s["key"], Counter(s["counts"]), s["total"],
                                        Counter(s["reasons"]), s["sentences"], s["heldout_sentences"]))
        heldout = Counter(data["heldout"])
        heldout_total = data["heldout_total"]
        if verbose:
            print(f"corpus-blend: counts from cache {ck}")
    else:
        memo: dict = {}
        heldout_raw: Counter = Counter()
        for src in cfg.corpora:
            sc, raw = _count_corpus(src, cfg, cache, heldout_raw)
            sc.counts, sc.total = _normalise_counter(raw, cfg.profile, memo, sc.reasons)
            sources.append(sc)
            if verbose:
                print(f"corpus-blend: {src.key}: {sc.sentences:,} train / {sc.heldout_sentences:,} "
                      f"held-out sentences, {sc.total:,} valid tokens, {len(sc.counts):,} types")
        heldout, heldout_total = _normalise_counter(heldout_raw, cfg.profile, memo, None)
        ck.parent.mkdir(parents=True, exist_ok=True)
        ck.write_bytes(gzip.compress(json.dumps({
            "profile": PROFILE_VERSION, "hashes": hashes,
            "sources": [{"key": s.key, "counts": s.counts, "total": s.total,
                         "reasons": s.reasons, "sentences": s.sentences,
                         "heldout_sentences": s.heldout_sentences} for s in sources],
            "heldout": heldout, "heldout_total": heldout_total,
        }, ensure_ascii=False).encode("utf-8"), mtime=0))
    if cfg.wordfreq_lang:
        sources.append(_wordfreq_source(cfg))

    # Attestation and blend: relative frequency per source, mean over ALL sources.
    attest: Counter = Counter()
    seen: set = set()
    for s in sources:
        floor = 1 if s.key == "wordfreq" else cfg.min_count
        for w, n in s.counts.items():
            seen.add(w)
            if n >= floor:
                attest[w] += 1
    attested = {w for w, k in attest.items() if k >= cfg.min_sources}
    freq = {w: sum(s.counts.get(w, 0) / s.total for s in sources if s.total) / len(sources)
            for w in attested}
    return Blend(cfg, sources, heldout, heldout_total, freq,
                 {w: attest[w] for w in attested}, len(seen) - len(attested))


# wordfreq's list carries frequencies, not counts; scaled to this many pseudo-tokens so the
# source behaves like a corpus in the blend (relative frequency is what is averaged).
WORDFREQ_PSEUDO_TOKENS = 10**9


def _wordfreq_source(cfg: BlendConfig) -> SourceCounts:
    from wordfreq import get_frequency_dict
    sc = SourceCounts("wordfreq")
    for tok, f in get_frequency_dict(cfg.wordfreq_lang, wordlist="best").items():
        w, why = normalise(tok, cfg.profile)
        n = max(1, round(f * WORDFREQ_PSEUDO_TOKENS))
        sc.reasons[why] += 1
        if w is not None:
            sc.counts[w] += n  # the ي/ك twins fold into one word and their frequencies merge
    sc.total = sum(sc.counts.values())
    return sc


def coverage(ranked: "list[str]", blend: Blend, points: "tuple[int, ...]") -> "list[tuple[int, float]]":
    """Share of valid held-out tokens whose word is among the first N of `ranked`."""
    out = []
    covered, i = 0, 0
    for n in sorted(points):
        while i < min(n, len(ranked)):
            covered += blend.heldout.get(ranked[i], 0)
            i += 1
        out.append((n, covered / blend.heldout_total if blend.heldout_total else 0.0))
    return out


def report(blend: Blend) -> None:
    """Print the per-source counts, rejection reasons and the raw attested-list coverage."""
    for s in blend.sources:
        rej = ", ".join(f"{k}={v:,}" for k, v in s.reasons.most_common())
        print(f"  source {s.key:18s} tokens={s.total:,} types={len(s.counts):,}  [{rej}]")
    print(f"  attested (>= {blend.config.min_sources} sources): {len(blend.freq):,}; "
          f"seen but under-attested: {blend.single_source:,}")
    print(f"  held-out: {blend.heldout_total:,} valid tokens, {len(blend.heldout):,} types")
    ranked = blend.ranked()
    pts = (*blend.config.coverage_points, len(ranked))
    for n, c in coverage(ranked, blend, pts):
        print(f"  coverage of the attested list @ {n:>7,}: {100 * c:.2f}%")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", required=True, choices=sorted(BLENDS))
    ap.add_argument("--cache", type=Path, default=CACHE)
    args = ap.parse_args()
    report(build_blend(BLENDS[args.corpus], args.cache))


if __name__ == "__main__":
    main()

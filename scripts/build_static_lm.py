#!/usr/bin/env python3
"""
Build the shipped static context language model (`CKLM` v1) for CleverKeys.

The model is a pruned word BIGRAM table — for each previous word, its top continuations with a
quantised conditional log-probability — plus a quantised log-unigram per vocabulary word. It is
the per-language general prior behind `StaticContextLm` (tap-prediction context multiplier and
the next-word cold-start seed; NEVER swipe — the S3 swipe gate failed). Every language-specific
input lives in one `LangConfig` in `CONFIGS`; a language without a configuration cannot be built. See `docs/specs/context-learning-and-next-word.md` and
`scripts/data/PROVENANCE.md` for the sources and why they were chosen.

Pipeline (every step deterministic — the same inputs give byte-identical output):

 1. fetch     : sources are PINNED by sha256 per language in `CONFIGS`; a missing file is downloaded, a hash
                mismatch is refused unless --allow-unpinned (Tatoeba's export rotates weekly, so
                a fresh download will not match the snapshot this model was built from).
 2. tokenize  : the on-device context contract (`NextWordPredictor.contextFromEditorText`) —
                runs of Unicode letters (accents, ñ, ç, å, ß kept) plus word-internal
                apostrophes/hyphens (so `j'ai`, `l'eau`, `c'est` stay one token), lowercased, edge
                '/- trimmed; sentence-final `.` `?` `!` and line breaks end a sentence. Typographic
                apostrophes are normalised to ASCII first (the keyboard types `'`); non-English
                configs also NFC-compose (the keyboard emits precomposed letters). A digit run
                BREAKS the bigram chain (on-device the digits are not a word either, but pairing
                the words either side of "3" would teach "have cats" from "have 3 cats").
 3. filter    : dedupe sentences (normalised text), keep 2..30-token sentences, split 90/10
                train/held-out by sentence hash (the held-out 10% is written for the pure-JVM
                eval and NEVER counted). Non-English configs also drop any corpus sentence that
                is also an OOD dev/test sentence (train/eval leakage; counted in the sidecar).
 4. artefacts : per corpus, drop words whose share exceeds `ARTEFACT_RATIO` x their wordfreq
                share (Tatoeba's "Tom"/"Mary" register: 452x / 174x) — they become chain breaks
                for that corpus only.
 5. vocab     : the language's shipped lexicon (`dictionaries/en_enhanced.json`, or the CKDT
                `dictionaries/<lang>_enhanced.bin` canonical section) UNION its contraction
                display forms (REPLACE + PAIRED files, e.g. fr `c'est`, it `l'acqua`), so every
                word the model can name is a word the app can show.
 6. combine   : weighted count sum, Leipzig x 1 + Tatoeba x W, W (among weights whose model fits
                the size cap) selected on the OUT-OF-DOMAIN
                DEV set (a UD treebank dev split per language; eval-only, never shipped) by
                next-word top-3 of the pruned model; ties go to the lower weight. A config may
                instead MIX a second Leipzig corpus (Leipzig x (1-m) + Leipzig2 x m + Tatoeba x W)
                with m and W fixed by the dev-split GATE metric in Kotlin: `--emit-grid DIR`
                writes every (candidate corpus, m, W) point for `StaticLmTapEvalTest`
                (STATIC_LM_EVAL_CANDIDATES + STATIC_LM_EVAL_SPLIT=dev) to choose from.
 7. prune     : weighted count >= MIN_COUNT, top TOP_K continuations per previous word.
 8. quantise  : -ln P in 1/16-nat steps, one byte (0..255 => P >= 1.2e-7).
 9. write     : `<out>.cklm` + `<out>.json` sidecar (counts, sha256, sources, weights) + the
                Tatoeba contributor list the CC BY 2.0 FR licence requires.

## CKLM v1 binary layout (little-endian)

    0   4  magic "CKLM"
    4   2  u16 version (1)
    6   2  u16 flags (0)
    8   4  language, ASCII, NUL-padded
   12   4  u32 vocabCount
   16   4  u32 prevCount
   20   4  u32 pairCount
   24   4  u32 prevIndexOffset      (absolute)
   28   4  u32 continuationsOffset (absolute)
   32   .. vocab, sorted by UTF-8 bytes, front-coded, one entry per word:
             u8 shared-prefix bytes, u8 suffix bytes, suffix (UTF-8), u8 -ln P(w) x 16
   prevIndexOffset: prevCount x 10 bytes, ascending word id:
             u32 word id, u32 continuation offset (relative to continuationsOffset), u16 count
   continuationsOffset: per previous word, continuations in ascending word id:
             varint id delta (the first is the absolute id), u8 -ln P(next|prev) x 16

P(next|prev) is c(prev,next) / c(prev,*) where c(prev,*) counts EVERY token that followed prev
inside a sentence (in-vocabulary or not), and P(w) is c(w) / N over every token — the same token
space on both sides, so the runtime's P(w|prev)/P(w) ratio is a genuine association measure and
the probability mass left after the listed continuations is an honest backoff.

Usage:
    python3 scripts/build_static_lm.py                       # build en from the pinned sources
    python3 scripts/build_static_lm.py --lang es             # any language in CONFIGS
    python3 scripts/build_static_lm.py --select-weight-only  # print the weight grid, write nothing

Requirements: Python 3.10+ standard library; `wordfreq` for the artefact filter (without it the
filter falls back to comparing each corpus against the OTHER corpus, recorded in the sidecar).

License: GPL-3.0 (part of CleverKeys)
"""

from __future__ import annotations

import argparse
import bz2
import hashlib
import io
import json
import math
import re
import struct
import sys
import tarfile
import unicodedata
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Iterator, Union

REPO = Path(__file__).resolve().parent.parent
CACHE = Path.home() / ".cache" / "cleverkeys-corpora"

FORMAT_MAGIC = b"CKLM"
FORMAT_VERSION = 1
HEADER_BYTES = 32

MIN_TOKENS, MAX_TOKENS = 2, 30
HELDOUT_FRACTION = 0.10
MIN_COUNT = 3.0
TOP_K = 20
QUANT_STEPS_PER_NAT = 16
ARTEFACT_RATIO = 20.0
# A word must be seen this often in a corpus before its share is judged: below it the ratio is
# dominated by sampling noise (and a rare word cannot distort the model much anyway).
ARTEFACT_MIN_COUNT = 50
WEIGHT_GRID = (0.0, 0.05, 0.1, 0.25, 0.5, 1.0)
SIZE_CAP_BYTES = 512 * 1024

# Apostrophe look-alikes the keyboard never types; normalised to ASCII before tokenizing.
APOSTROPHES = str.maketrans({"’": "'", "‘": "'", "ʼ": "'", "′": "'"})
SENTENCE_END = re.compile(r"[.?!\n]")
# A run of letters/apostrophes/hyphens (a candidate token), or one digit (a chain break).
RUN = re.compile(r"(?:[^\W\d_]|['\-])+|\d")


@dataclass(frozen=True)
class Source:
    """One pinned input file."""

    key: str
    filename: str
    url: str
    sha256: str
    license: str
    shipped: bool
    note: str


# Contraction display-form files: a JSON object whose values are the display form (str), a list
# of display forms (the PAIRED files), or a list of {"contraction": form} objects (en pairings).
ContractionValue = Union[str, list[Union[str, dict[str, object]]]]


@dataclass(frozen=True)
class LangConfig:
    """Everything language-specific the builder reads. Adding a language = adding one of these
    (with pinned hashes) plus a measured gate run — never widening `--lang` on its own."""

    code: str  # asset code: lm/<code>.cklm, CKLM header language
    name: str  # English name, for generated headers ("Spanish")
    leipzig: Source
    tatoeba: Source
    ood_dev: Source
    ood_test: Source
    ood_label: str  # short treebank name for the sidecar, e.g. "UD EWT"
    lexicon: str  # file under dictionaries/: en_enhanced.json (JSON object keys) or *.bin (CKDT)
    contraction_files: tuple[str, ...]  # display-form files under dictionaries/ (see above)
    wordfreq_lang: str  # artefact-filter reference language
    # NFC-compose corpus text before tokenizing. False only for en, whose shipped model predates
    # the flag (its corpus is already composed; kept off so en.cklm stays byte-identical).
    nfc: bool = True
    # Drop corpus sentences that also occur in the OOD dev/test files. Off for en only for the
    # same byte-reproducibility reason (the en overlap count is reported by --check-overlap).
    exclude_eval_overlap: bool = True
    # Optional SECOND Leipzig corpus mixed into the first: Leipzig weights become
    # leipzig x (1 - mix) + leipzig2 x mix (they sum to 1, so the count mass — and how many pairs
    # clear MIN_COUNT — stays near a single-corpus model's). Chosen on the OOD dev split by the
    # gate metric (docs/eval/2026-09-29-static-lm-multilingual.md, es/pt/sv retry); None = one corpus.
    leipzig2: Source | None = None
    mix: float = 0.0
    # Tatoeba weight fixed by that dev selection. None = select here by the next-word proxy
    # (`WEIGHT_GRID`, the rule every model shipped before the retry was built with).
    tatoeba_weight: float | None = None
    # Second-corpus CANDIDATES for `--emit-grid` (pinned; evaluation-stage only — a candidate
    # that is not chosen never reaches a shipped model).
    candidates: tuple[Source, ...] = ()

    @property
    def sources(self) -> tuple[Source, ...]:
        extra = (self.leipzig2,) if self.leipzig2 is not None else ()
        return (self.leipzig, self.tatoeba, *extra, self.ood_dev, self.ood_test)

    @property
    def vocabulary_note(self) -> str:
        return f"dictionaries/{self.lexicon} UNION contraction display forms"


def _leipzig(corpus: str, sha256: str, note: str, key: str = "leipzig") -> Source:
    return Source(
        key=key,
        filename=f"leipzig/{corpus}.tar.gz",
        url=f"https://downloads.wortschatz-leipzig.de/corpora/{corpus}.tar.gz",
        sha256=sha256,
        license="CC BY 4.0 (Leipzig Corpora Collection download terms)",
        shipped=True,
        note=note,
    )


def _tatoeba(iso3: str, sha256: str, fetched: str) -> Source:
    return Source(
        key="tatoeba",
        filename=f"tatoeba/{iso3}_sentences_detailed.tsv.bz2",
        url=f"https://downloads.tatoeba.org/exports/per_language/{iso3}/{iso3}_sentences_detailed.tsv.bz2",
        sha256=sha256,
        license="CC BY 2.0 FR",
        shipped=True,
        note=f"weekly-rotating export; snapshot fetched {fetched}",
    )


def _ud(repo: str, commit: str, stem: str, split: str, sha256: str, license: str = "CC BY-SA 4.0") -> Source:
    dev = split == "dev"
    return Source(
        key="ood_dev" if dev else "ood_test",
        filename=f"ud/{stem}-ud-{split}.conllu",
        url=f"https://raw.githubusercontent.com/UniversalDependencies/{repo}/{commit}/{stem}-ud-{split}.conllu",
        sha256=sha256,
        license=f"{license} (eval-only, never shipped)",
        shipped=False,
        note="weight selection only" if dev else "gate evaluation only (read by the Kotlin eval)",
    )


CONFIGS: dict[str, LangConfig] = {
    c.code: c
    for c in (
        LangConfig(
            code="en",
            name="English",
            leipzig=_leipzig(
                "eng-com_web-public_2018_300K",
                "cc6a36b245e68523bc92b9d8131ac93592116b0167b3785418fcee9eb9c0dad7",
                "English web (.com) 2018, 300K sentences",
            ),
            tatoeba=_tatoeba("eng", "353d48de7905952cf6f1500f6a3158516ecf9e10cd844ba051982cfa4a11c111", "2026-09-26"),
            ood_dev=_ud("UD_English-EWT", "4a4d77f599ea53cc405f85d0cec4b2f14f81d42b", "en_ewt", "dev",
                        "39239e0a60db3ae68f4b7036189f11b6692741d10ff8240dd91f74f2760d90f8"),
            ood_test=_ud("UD_English-EWT", "4a4d77f599ea53cc405f85d0cec4b2f14f81d42b", "en_ewt", "test",
                         "fa024f43dc5da3c5ac02563bc9bd0e974f46cbb1560823976a8f342a37dc494a"),
            ood_label="UD EWT",
            lexicon="en_enhanced.json",
            contraction_files=("contractions_en.json", "contractions_non_paired.json", "contraction_pairings.json"),
            wordfreq_lang="en",
            nfc=False,
            exclude_eval_overlap=False,
        ),
        LangConfig(
            code="es",
            name="Spanish",
            leipzig=_leipzig(
                "spa_web_2016_300K",
                "e7f921d53d542fc9997c8d8b9c198add96961624b274e50289ee9936bcfd362d",
                "Spanish web 2016, 300K sentences",
            ),
            tatoeba=_tatoeba("spa", "76425b39fcfba2e0acb5348fb172bb5eeead611876f1b1daf4f3f00a7ae9ac9d", "2026-09-29"),
            ood_dev=_ud("UD_Spanish-GSD", "267f3530d4f122ee85d1891800211a06dfb79347", "es_gsd", "dev",
                        "704fb19afc0a34cff476e1a70351b7026c4364f862e8e8388b02873852567c22"),
            ood_test=_ud("UD_Spanish-GSD", "267f3530d4f122ee85d1891800211a06dfb79347", "es_gsd", "test",
                         "ecce253f44bffaa9803ae7ec0c10911c13e4f1fc3cb439dbd1db2cebe0a12741"),
            ood_label="UD Spanish-GSD",
            lexicon="es_enhanced.bin",
            contraction_files=("contractions_es.json",),
            wordfreq_lang="es",
            # es/pt/sv retry (pre-registered 2026-09-29): register candidates for GSD's
            # encyclopedic/news-style prose, mixed into the web corpus by a dev-chosen weight.
            candidates=(
                _leipzig("spa_news_2023_300K",
                         "668ee9fbb6ee70aaff0164b2fa2f6acff54950cf7b444f8f2021997684b06d6f",
                         "Spanish news 2023, 300K sentences", key="leipzig2"),
                _leipzig("spa_wikipedia_2021_300K",
                         "8f6d62de098a7615c5b8d40d4e449e883888252fa599102747c5eec91b10fa46",
                         "Spanish Wikipedia 2021, 300K sentences", key="leipzig2"),
            ),
        ),
        LangConfig(
            code="de",
            name="German",
            leipzig=_leipzig(
                "deu-de_web_2021_300K",
                "3fed1175ee2c76fd169d00ff28b402e139082ae9bf02b9c72a04f0ce163185bc",
                "German web (.de) 2021, 300K sentences",
            ),
            tatoeba=_tatoeba("deu", "eb80b6a8d6b938a48fc33d65848d6efaf53d264c96e458ba6e4deec2deaaab59", "2026-09-29"),
            ood_dev=_ud("UD_German-GSD", "ce54dbe9c6a5640c93e9952f069f582f6cd1f9fc", "de_gsd", "dev",
                        "01e8e674973592747ffe9a8c77fcf9d2f5936a8484e731ad4f76254318a8952c"),
            ood_test=_ud("UD_German-GSD", "ce54dbe9c6a5640c93e9952f069f582f6cd1f9fc", "de_gsd", "test",
                         "595070aa50b706a91dc66f17c296f7a9a25cbc75269f177c27680fb1c21528ab"),
            ood_label="UD German-GSD",
            lexicon="de_enhanced.bin",
            contraction_files=("contractions_de.json",),
            wordfreq_lang="de",
        ),
        LangConfig(
            code="fr",
            name="French",
            leipzig=_leipzig(
                "fra-fr_web_2013_300K",
                "4c917a4929a12d6e7c8abf1c90b3fc41e6839a90d88f18a834180bdc2dbc7593",
                "French web (.fr) 2013, 300K sentences (newest France web corpus offered)",
            ),
            tatoeba=_tatoeba("fra", "c54845fd6a01649d4cbb17d1af736ad24afce93eabd045d63db2f1f0d6c5492a", "2026-09-29"),
            ood_dev=_ud("UD_French-GSD", "94d5b68e185fc22a9ef292040e84f476d36d9b0e", "fr_gsd", "dev",
                        "9221e5084cc6a1b1540671cfbd2fc0456efc223b2fe8dd2146c2bad921be02d4"),
            ood_test=_ud("UD_French-GSD", "94d5b68e185fc22a9ef292040e84f476d36d9b0e", "fr_gsd", "test",
                         "eee5a599b429658b6ee8582fae9993eb07161247fc64bad57d16b2050ed4eb1a"),
            ood_label="UD French-GSD",
            lexicon="fr_enhanced.bin",
            # REPLACE (c'est, j'ai, aujourd'hui …) + PAIRED (l'une, est-elle …) display forms.
            contraction_files=("contractions_fr.json", "contraction_pairs_fr.json"),
            wordfreq_lang="fr",
        ),
        LangConfig(
            code="it",
            name="Italian",
            leipzig=_leipzig(
                "ita-it_web-public_2019_300K",
                "e8be0d4f3a49a627a8bcf5cb93160419ba170ac794f561d16ea89de413def42e",
                "Italian public web (.it) 2019, 300K sentences",
            ),
            tatoeba=_tatoeba("ita", "a07bbd0f64f226edb7109f7f955a6456518863fef50d64f83573ed5efc99a399", "2026-09-29"),
            # Not ISDT/VIT/ParTUT/PoSTWITA: those are CC BY-NC-SA ("research purposes only").
            ood_dev=_ud("UD_Italian-TWITTIRO", "ff4c607327db615e219e1613b6e804ec55a4cffc", "it_twittiro", "dev",
                        "1f16040ae17395d2910784742e6b3344620a67937524d47ed96fd56f811a8c99"),
            ood_test=_ud("UD_Italian-PUD", "71d932aa096368331ba2fd9bc906ec8dd853da8e", "it_pud", "test",
                         "ad5d302bdfd05194155d9ad43dc54c3cb915076d66b3eb6ef33eca9a11f1372a", "CC BY-SA 3.0"),
            ood_label="UD Italian-TWITTIRO",
            lexicon="it_enhanced.bin",
            contraction_files=("contractions_it.json", "contraction_pairs_it.json"),
            wordfreq_lang="it",
        ),
        LangConfig(
            code="pt",
            name="Portuguese",
            leipzig=_leipzig(
                "por-pt_web_2015_300K",
                "8ba1f5e84cbcc3924277396962b2b6851a06472aad640a80f8837694b9d64695",
                "Portuguese web (.pt) 2015, 300K sentences (no Brazilian web corpus is offered)",
            ),
            tatoeba=_tatoeba("por", "3c76d939d12528971264728fa48f4de52170d13eb2a36340b82fde311c3ad8f4", "2026-09-29"),
            ood_dev=_ud("UD_Portuguese-Bosque", "884288537f7e8e02e50f125791cf279d905d1043", "pt_bosque", "dev",
                        "f8a67abae12fbab85a3995204a6459d1958c11065062d4ea5349d6b914e78f81"),
            ood_test=_ud("UD_Portuguese-Bosque", "884288537f7e8e02e50f125791cf279d905d1043", "pt_bosque", "test",
                         "9a824650b7a02cf411f6e09b39e8fb423c85e1a5fb3a55b87e7f4c0c2ea5b3bb"),
            ood_label="UD Portuguese-Bosque",
            lexicon="pt_enhanced.bin",
            contraction_files=("contractions_pt.json",),
            wordfreq_lang="pt",
            # Retry candidates: Bosque dev is 45% Brazilian news (CETENFolha) + 55% European news
            # (CETEMPúblico); the web corpus is Portugal-only. (a) supplies variety AND register.
            candidates=(
                _leipzig("por-br_newscrawl_2011_300K",
                         "20b8dad08d98ef1d9a4aa17b959532ba0c7b0e6f8fee7da6324cfd1e818452ce",
                         "Brazilian Portuguese news crawl 2011, 300K sentences", key="leipzig2"),
                _leipzig("por_news_2023_300K",
                         "ffd654ebb29e8afdd802fbd6a1cdc3258f927f15339f368e55529ed058bb5a70",
                         "Portuguese news 2023, 300K sentences", key="leipzig2"),
            ),
        ),
        LangConfig(
            code="sv",
            name="Swedish",
            leipzig=_leipzig(
                "swe-se_web_2023_300K",
                "c5a86fb055db346076055a64b8a4537e5a8787e61a4131bf9cf81f11ab4a0773",
                "Swedish web (.se) 2023, 300K sentences",
            ),
            tatoeba=_tatoeba("swe", "63fa7da77b5a9f72d0d30777bb1868b9ae871a020c79e8b8d0db213d3efbe71a", "2026-09-29"),
            ood_dev=_ud("UD_Swedish-Talbanken", "c434778d9511be5c35a6a11531f0107a960fb5d6", "sv_talbanken", "dev",
                        "e1c14ae088f575d9f5f2d870d456b9e3911a04725f141752bb8c972a81cb6c6c"),
            ood_test=_ud("UD_Swedish-Talbanken", "c434778d9511be5c35a6a11531f0107a960fb5d6", "sv_talbanken", "test",
                         "f7bc84ce37cd6a71e95b8d8684801da0bb6d2be4e419ba2e865eb109d6cd1bc6"),
            ood_label="UD Swedish-Talbanken",
            lexicon="sv_enhanced.bin",
            contraction_files=("contractions_sv.json",),
            wordfreq_lang="sv",
            # Retry candidates: Talbanken is professional prose (register mismatch with web text).
            candidates=(
                _leipzig("swe_news_2023_300K",
                         "e7c777651a432df0e9f108c9b7f452e22c6cfe9d6572ba00d1891a9baa69db85",
                         "Swedish news 2023, 300K sentences", key="leipzig2"),
                _leipzig("swe_wikipedia_2021_300K",
                         "e4a0725749bef237b530493d2119d863d264a8de295f945ffb2c8185abd84f98",
                         "Swedish Wikipedia 2021, 300K sentences", key="leipzig2"),
            ),
        ),
    )
}


# ── fetch ─────────────────────────────────────────────────────────────────────────────────────


def sha256_of(path: Path) -> str:
    """Streaming sha256 of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def ensure_source(src: Source, cache: Path, allow_unpinned: bool, offline: bool) -> tuple[Path, str]:
    """Return the local path of [src] and its actual sha256, downloading it when absent.

    Refuses (SystemExit) a file whose hash differs from the pin unless [allow_unpinned] — the
    output of such a build is a different model, and the sidecar records the hash it really used.
    """
    path = cache / src.filename
    if not path.exists():
        if offline:
            raise SystemExit(f"missing {path} and --offline given")
        path.parent.mkdir(parents=True, exist_ok=True)
        print(f"[fetch] {src.url}")
        tmp = path.with_suffix(path.suffix + ".part")
        with urllib.request.urlopen(src.url, timeout=120) as resp, open(tmp, "wb") as out:
            while chunk := resp.read(1 << 20):
                out.write(chunk)
        tmp.rename(path)
    actual = sha256_of(path)
    if actual != src.sha256:
        msg = f"{path}: sha256 {actual} != pinned {src.sha256}"
        if not allow_unpinned:
            raise SystemExit(msg + " (refusing; pass --allow-unpinned to build from it anyway)")
        print(f"[warn] {msg} — building UNPINNED")
    return path, actual


# ── tokenization (the on-device contract) ──────────────────────────────────────────────────────


def split_sentences(text: str, nfc: bool = False) -> list[str]:
    """Split on sentence-final punctuation / line breaks, exactly where the device resets context.

    [nfc] composes decomposed accents first (`e` + U+0301 → `é`): the keyboard commits precomposed
    letters, and Kotlin's `Char.isLetter` would otherwise split a word at the combining mark.
    """
    if nfc:
        text = unicodedata.normalize("NFC", text)
    return [s for s in SENTENCE_END.split(text.translate(APOSTROPHES)) if s.strip()]


def tokenize(segment: str) -> list[str | None]:
    """Tokens of one sentence segment; `None` marks a chain break (a digit run).

    Mirrors `NextWordPredictor.contextFromEditorText`: a token is a maximal run of letters,
    apostrophes and hyphens with edge apostrophes/hyphens trimmed, kept only if it contains a
    letter, lowercased. Consecutive breaks collapse to one.
    """
    out: list[str | None] = []
    for m in RUN.finditer(segment):
        run = m.group(0)
        if run.isdigit():
            if out and out[-1] is not None:
                out.append(None)
            continue
        tok = run.strip("'-")
        if tok and any(c.isalpha() for c in tok):
            out.append(sys.intern(tok.lower()))
    while out and out[-1] is None:
        out.pop()
    while out and out[0] is None:
        out.pop(0)
    return out


def word_count(tokens: list[str | None]) -> int:
    return sum(1 for t in tokens if t is not None)


def sentence_key(segment: str) -> str:
    """Dedup + split key: lowercased, whitespace-collapsed segment text."""
    return " ".join(segment.lower().split())


def is_heldout(key: str) -> bool:
    """Deterministic 90/10 split by sentence hash — the Kotlin eval reads what this writes."""
    return int(hashlib.sha1(key.encode("utf-8")).hexdigest()[:8], 16) / 0xFFFFFFFF < HELDOUT_FRACTION


# ── corpus readers ─────────────────────────────────────────────────────────────────────────────


def leipzig_lines(path: Path) -> Iterator[tuple[str, str]]:
    """(sentence, contributor) from a Leipzig `*-sentences.txt` inside the tarball."""
    with tarfile.open(path, "r:gz") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("-sentences.txt"))
        fh = tar.extractfile(member)
        assert fh is not None
        for raw in io.TextIOWrapper(fh, encoding="utf-8", errors="replace"):
            cols = raw.rstrip("\n").split("\t", 1)
            if len(cols) == 2:
                yield cols[1], ""


def tatoeba_lines(path: Path) -> Iterator[tuple[str, str]]:
    """(sentence, username) from `eng_sentences_detailed.tsv.bz2` (id, lang, text, user, ...)."""
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            cols = raw.rstrip("\n").split("\t")
            if len(cols) >= 4:
                user = cols[3] if cols[3] not in ("", "\\N") else ""
                yield cols[2], user


def conllu_texts(path: Path) -> Iterator[str]:
    """Raw `# text = ...` lines of a UD .conllu — the surface string, not UD's split tokens
    (UD splits `don't` into `do` + `n't`, which is not what a keyboard sees)."""
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("# text = "):
                yield line[len("# text = "):].rstrip("\n")


# ── counting ──────────────────────────────────────────────────────────────────────────────────


@dataclass
class CorpusCounts:
    """Train-split counts for one corpus."""

    name: str
    unigrams: Counter[str] = field(default_factory=Counter)  # every token
    successors: Counter[str] = field(default_factory=Counter)  # c(prev, *) over every following token
    bigrams: Counter[tuple[str, str]] = field(default_factory=Counter)  # (prev, next), both in vocab
    tokens: int = 0
    sentences: int = 0
    heldout: int = 0
    duplicates: int = 0
    eval_overlap: int = 0  # segments dropped because an OOD dev/test sentence has the same key
    contributors: set[str] = field(default_factory=set)


def iter_segments(
    lines: Iterable[tuple[str, str]], seen: set[str], nfc: bool
) -> Iterator[tuple[str, str, list[str | None], bool]]:
    """(segment text, contributor, tokens, heldout) for every kept, non-duplicate segment.

    A key already in [seen] yields the duplicate marker (empty tokens). Callers that exclude the
    evaluation sentences pre-seed [seen] with their keys; `count_corpus` tells the two apart.
    """
    for text, who in lines:
        for seg in split_sentences(text, nfc):
            toks = tokenize(seg)
            if not MIN_TOKENS <= word_count(toks) <= MAX_TOKENS:
                continue
            key = sentence_key(seg)
            if key in seen:
                yield seg, who, [], False  # duplicate marker (empty tokens)
                continue
            seen.add(key)
            yield seg, who, toks, is_heldout(key)


def count_corpus(
    name: str,
    lines: Iterable[tuple[str, str]],
    vocab: set[str],
    seen: set[str],
    heldout_sink: Callable[[str], None],
    nfc: bool = False,
    eval_keys: frozenset[str] = frozenset(),
) -> tuple[CorpusCounts, list[list[str | None]]]:
    """Count one corpus; return counts plus its train sentences (kept for the artefact re-count).

    [eval_keys] must already be in [seen]; a hit on one is counted as eval overlap, not a duplicate.
    """
    c = CorpusCounts(name)
    train: list[list[str | None]] = []
    for seg, who, toks, held in iter_segments(lines, seen, nfc):
        if not toks:
            if sentence_key(seg) in eval_keys:
                c.eval_overlap += 1
            else:
                c.duplicates += 1
            continue
        if held:
            c.heldout += 1
            heldout_sink(" ".join(seg.split()))
            continue
        c.sentences += 1
        if who:
            c.contributors.add(who)
        train.append(tuple(toks))  # type: ignore[arg-type]
    recount(c, train, vocab, frozenset())
    return c, train


def recount(c: CorpusCounts, train: list[list[str | None]], vocab: set[str], artefacts: frozenset[str]) -> None:
    """(Re)build [c]'s counters from its train sentences, treating [artefacts] as chain breaks."""
    c.unigrams.clear()
    c.successors.clear()
    c.bigrams.clear()
    c.tokens = 0
    uni, succ, bi = c.unigrams, c.successors, c.bigrams
    for toks in train:
        prev: str | None = None
        for t in toks:
            if t is None or t in artefacts:
                prev = None
                continue
            uni[t] += 1
            c.tokens += 1
            if prev is not None:
                succ[prev] += 1
                if prev in vocab and t in vocab:
                    bi[(prev, t)] += 1
            prev = t


def find_artefacts(c: CorpusCounts, reference: Callable[[str], float]) -> dict[str, float]:
    """Words whose share in [c] exceeds ARTEFACT_RATIO x the reference share → ratio."""
    out: dict[str, float] = {}
    total = max(1, c.tokens)
    for w, n in c.unigrams.items():
        if n < ARTEFACT_MIN_COUNT:
            continue
        ref = reference(w)
        share = n / total
        ratio = share / ref if ref > 0 else math.inf
        if ratio > ARTEFACT_RATIO:
            out[w] = ratio
    return out


# ── model ─────────────────────────────────────────────────────────────────────────────────────


@dataclass
class Model:
    """A pruned bigram table over a vocabulary, with weighted counts."""

    table: dict[str, list[tuple[str, float]]]  # prev → [(next, count)] top-K, count-desc
    successors: dict[str, float]
    unigrams: dict[str, float]
    total_tokens: float


def combine(corpora: list[tuple[CorpusCounts, float]]) -> tuple[
    dict[str, float], dict[str, float], dict[tuple[str, str], float], float
]:
    """Combine integer observations into fractional weighted counts without rounding."""
    uni: defaultdict[str, float] = defaultdict(float)
    succ: defaultdict[str, float] = defaultdict(float)
    bi: defaultdict[tuple[str, str], float] = defaultdict(float)
    n = 0.0
    for c, w in corpora:
        if w == 0.0:
            continue
        for k, v in c.unigrams.items():
            uni[k] += v * w
        for k, v in c.successors.items():
            succ[k] += v * w
        for pair, v in c.bigrams.items():
            bi[pair] += v * w
        n += c.tokens * w
    return uni, succ, bi, n


def prune(
    uni: dict[str, float], succ: dict[str, float],
    bi: dict[tuple[str, str], float], n: float,
) -> Model:
    by_prev: dict[str, list[tuple[str, float]]] = {}
    for (a, b), cnt in bi.items():
        if cnt >= MIN_COUNT - 1e-9:
            by_prev.setdefault(a, []).append((b, cnt))
    table = {a: sorted(v, key=lambda kv: (-kv[1], kv[0]))[:TOP_K] for a, v in by_prev.items()}
    return Model(table=table, successors=dict(succ), unigrams=dict(uni), total_tokens=n)


def quantise(p: float) -> int:
    """-ln p in 1/16-nat steps, clamped to a byte."""
    if p <= 0.0:
        return 255
    return max(0, min(255, int(round(-math.log(p) * QUANT_STEPS_PER_NAT))))


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def encode(model: Model, lang: str) -> tuple[bytes, dict[str, int]]:
    """Serialise [model] as CKLM v1. Returns (bytes, stats)."""
    words = sorted(
        {a for a in model.table} | {b for v in model.table.values() for b, _ in v},
        key=lambda w: w.encode("utf-8"),
    )
    wid = {w: i for i, w in enumerate(words)}
    body = bytearray()
    prev_bytes = b""
    for w in words:
        wb = w.encode("utf-8")
        common = 0
        limit = min(len(prev_bytes), len(wb), 255)
        while common < limit and prev_bytes[common] == wb[common]:
            common += 1
        suffix = wb[common:]
        if len(suffix) > 255:
            raise ValueError(f"word too long for CKLM v1: {w!r}")
        body += bytes([common, len(suffix)]) + suffix
        body.append(quantise(model.unigrams.get(w, 0.0) / model.total_tokens))
        prev_bytes = wb

    prev_ids = sorted(wid[a] for a in model.table)
    conts = bytearray()
    index = bytearray()
    pairs = 0
    for pid in prev_ids:
        a = words[pid]
        entries = sorted(model.table[a], key=lambda kv: wid[kv[0]])
        total = model.successors[a]
        index += struct.pack("<IIH", pid, len(conts), len(entries))
        last = 0
        for b, cnt in entries:
            q = quantise(cnt / total)
            conts += varint(wid[b] - last) + bytes([q])
            last = wid[b]
            pairs += 1

    prev_index_offset = HEADER_BYTES + len(body)
    conts_offset = prev_index_offset + len(index)
    lang_bytes = lang.encode("ascii")[:4].ljust(4, b"\0")
    header = FORMAT_MAGIC + struct.pack(
        "<HH4sIIIII",
        FORMAT_VERSION,
        0,
        lang_bytes,
        len(words),
        len(prev_ids),
        pairs,
        prev_index_offset,
        conts_offset,
    )
    assert len(header) == HEADER_BYTES
    blob = bytes(header + body + index + conts)
    return blob, {"vocab": len(words), "prevs": len(prev_ids), "pairs": pairs}


# ── eval (weight selection only — the gate is measured in Kotlin through UnifiedScore) ────────


def next_word_topk(model: Model, sentences: list[list[str | None]], vocab: set[str]) -> tuple[float, float, int]:
    """Next-word top-1 / top-3 of the PRUNED model over in-vocab targets with a previous word."""
    n = hit1 = hit3 = 0
    for toks in sentences:
        prev: str | None = None
        for t in toks:
            if t is None:
                prev = None
                continue
            if prev is not None and t in vocab:
                n += 1
                cands = model.table.get(prev, [])
                if cands and cands[0][0] == t:
                    hit1 += 1
                if any(b == t for b, _ in cands[:3]):
                    hit3 += 1
            prev = t
    return (100.0 * hit1 / max(1, n), 100.0 * hit3 / max(1, n), n)


# ── vocabulary ─────────────────────────────────────────────────────────────────────────────────


CKDT_MAGIC = 0x54444B43  # "CKDT"


def ckdt_words(path: Path) -> list[str]:
    """Canonical words of a CKDT v2 dictionary (`<lang>_enhanced.bin`), lowercased.

    Header (little-endian u32s): magic, version, (reserved), word count, canonical-section offset.
    Each canonical entry: u16 UTF-8 length, the word, u8 frequency rank — the same walk as the
    Kotlin readers (`StaticLmTapEvalTest.loadLexicon`, `CkdtDictionaryReader`).
    """
    b = path.read_bytes()
    magic, version, _reserved, count, offset = struct.unpack_from("<IIIII", b, 0)
    if magic != CKDT_MAGIC or version != 2:
        raise SystemExit(f"{path}: not a CKDT v2 dictionary (magic {magic:#x}, version {version})")
    words: list[str] = []
    pos = offset
    for _ in range(count):
        (n,) = struct.unpack_from("<H", b, pos)
        words.append(b[pos + 2:pos + 2 + n].decode("utf-8").lower())
        pos += 3 + n  # length, word, rank byte
    return words


def contraction_forms(values: Iterable[ContractionValue]) -> Iterator[str]:
    """Display forms in a contraction file's values (str | [str] | [{"contraction": str}])."""
    for v in values:
        if isinstance(v, str):
            yield v.lower()
            continue
        for e in v:
            yield str(e["contraction"] if isinstance(e, dict) else e).lower()


def load_vocab(dict_dir: Path, cfg: LangConfig) -> tuple[set[str], set[str]]:
    """(lexicon, contraction display forms) — the model may only name words from their union."""
    lex_path = dict_dir / cfg.lexicon
    if cfg.lexicon.endswith(".json"):
        lexicon = {w.lower() for w in json.loads(lex_path.read_text("utf-8"))}
    else:
        lexicon = set(ckdt_words(lex_path))
    forms: set[str] = set()
    for name in cfg.contraction_files:
        forms.update(contraction_forms(json.loads((dict_dir / name).read_text("utf-8")).values()))
    return lexicon, forms


def wordfreq_reference(lang: str) -> tuple[Callable[[str], float] | None, str]:
    try:
        import wordfreq  # type: ignore[import-not-found,unused-ignore]
    except ImportError:
        return None, "unavailable"
    from importlib.metadata import version

    return (lambda w: float(wordfreq.word_frequency(w, lang))), f"wordfreq {version('wordfreq')}"


def corpus_reference(other: CorpusCounts) -> Callable[[str], float]:
    total = max(1, other.tokens)
    return lambda w: other.unigrams.get(w, 0) / total


# ── main ─────────────────────────────────────────────────────────────────────────────────────

# Retry grid (pre-registered 2026-09-29): second-corpus mix and Tatoeba weights for --emit-grid.
MIX_GRID = (0.0, 0.25, 0.5, 0.75, 1.0)
GRID_WEIGHTS = (0.0, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0)


def filter_artefacts(
    corpora: list[tuple[CorpusCounts, list[list[str | None]], CorpusCounts]],
    ref: Callable[[str], float] | None,
    ref_name: str,
    vocab: set[str],
) -> dict[str, dict[str, float]]:
    """Per corpus (counts, train sentences, fallback reference corpus): find its artefacts
    against wordfreq (or the fallback corpus when wordfreq is absent), print them and recount
    with them as chain breaks. Returns corpus name -> {word: ratio}."""
    artefacts: dict[str, dict[str, float]] = {}
    for c, train, other in corpora:
        reference = ref if ref is not None else corpus_reference(other)
        found = find_artefacts(c, reference)
        artefacts[c.name] = found
        top = sorted(found.items(), key=lambda kv: -c.unigrams[kv[0]])[:15]
        print(f"[artefacts:{c.name}] {len(found)} words dropped (reference: "
              f"{ref_name if ref else 'other corpus'}); most frequent: "
              + ", ".join(f"{w} {r:.0f}x" for w, r in top))
        recount(c, train, vocab, frozenset(found))
    return artefacts


def mixture(leipzig: CorpusCounts, tatoeba: CorpusCounts, weight: float,
            second: CorpusCounts | None, mix: float) -> list[tuple[CorpusCounts, float]]:
    """The weighted corpus list, in the ONE order both the grid and the final build use (float
    sums depend on it, and the shipped model must be byte-identical to the grid point chosen).
    Without a second corpus this is exactly the pre-retry `[(leipzig, 1), (tatoeba, W)]`."""
    out = [(leipzig, 1.0 - mix), (tatoeba, weight)]
    if second is not None:
        out.append((second, mix))
    return out


def emit_grid(
    args: argparse.Namespace,
    cfg: LangConfig,
    leipzig: CorpusCounts,
    tatoeba: CorpusCounts,
    seen_after_tatoeba: set[str],
    paths: dict[str, Path],
    vocab: set[str],
    excluded: frozenset[str],
    ref: Callable[[str], float] | None,
    ref_name: str,
) -> int:
    """Write every (second corpus, mix, Tatoeba weight) grid point within the size cap to
    `<emit_grid>/<tag>/<lang>.cklm` + a sidecar, for the Kotlin dev-split selection
    (`StaticLmTapEvalTest`, STATIC_LM_EVAL_CANDIDATES). Nothing here is shipped; mix 0 is the
    single-corpus model and is written once, as `base_*`."""
    root: Path = args.emit_grid
    root.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, object]] = []

    def write(tag: str, corpora: list[tuple[CorpusCounts, float]], meta: dict[str, object]) -> None:
        model = prune(*combine(corpora))
        blob, stats = encode(model, cfg.code)
        row = {"tag": tag, **meta, "bytes": len(blob), **stats}
        rows.append(row)
        over = len(blob) > SIZE_CAP_BYTES
        print(f"[grid] {tag}: {len(blob)} bytes{' OVER CAP (not written)' if over else ''}", flush=True)
        if over:
            return
        out = root / tag
        out.mkdir(parents=True, exist_ok=True)
        (out / f"{cfg.code}.cklm").write_bytes(blob)
        side = {
            "format": "CKLM", "version": FORMAT_VERSION, "language": cfg.code,
            **stats, "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest(),
            "model": {"lexicon": cfg.lexicon, "contractionFiles": list(cfg.contraction_files)},
            "grid": meta,
        }
        (out / f"{cfg.code}.json").write_text(json.dumps(side, indent=2, ensure_ascii=False) + "\n", "utf-8")

    for w in GRID_WEIGHTS:
        write(f"base_m0_w{w}", mixture(leipzig, tatoeba, w, None, 0.0),
              {"second": None, "mix": 0.0, "tatoeba": w})
    for cand in cfg.candidates:
        path, _ = ensure_source(cand, args.cache, args.allow_unpinned, args.offline)
        name = cand.filename.split("/")[-1].removesuffix(".tar.gz")
        # The second corpus is counted after Leipzig + Tatoeba, exactly as the final build does.
        second, second_train = count_corpus(
            "leipzig2", leipzig_lines(path), vocab, set(seen_after_tatoeba), lambda _s: None, cfg.nfc, excluded)
        print(f"[{name}] train sentences {second.sentences}, held-out {second.heldout}, "
              f"duplicates {second.duplicates}, eval-overlap dropped {second.eval_overlap}, tokens {second.tokens}")
        filter_artefacts([(second, second_train, leipzig)], ref, ref_name, vocab)
        del second_train
        for mix in MIX_GRID[1:]:
            for w in GRID_WEIGHTS:
                write(f"{name}_m{mix}_w{w}", mixture(leipzig, tatoeba, w, second, mix),
                      {"second": name, "mix": mix, "tatoeba": w})
        del second
    (root / f"grid_{cfg.code}.json").write_text(json.dumps(rows, indent=2) + "\n", "utf-8")
    print(f"[grid] {len(rows)} points -> {root}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    # Only languages with a pinned, evaluated configuration: a free-form code would relabel another
    # language's data and overwrite that language's asset (the pre-2026-09-27 --lang bug).
    ap.add_argument("--lang", choices=tuple(CONFIGS), default="en",
                    help="model language (each has pinned corpora, vocabulary and evaluation in CONFIGS)")
    ap.add_argument("--cache", type=Path, default=CACHE)
    ap.add_argument("--out-dir", type=Path, default=REPO / "src/main/assets/lm")
    ap.add_argument("--contributors", type=Path, default=None,
                    help="Tatoeba contributor list (default scripts/data/tatoeba-contributors-<lang>.txt)")
    ap.add_argument("--eval-dir", type=Path, default=CACHE / "static-lm-eval",
                    help="local-only held-out + OOD sentence files for the Kotlin eval")
    ap.add_argument("--weight", type=float, default=None, help="skip selection, use this Tatoeba weight")
    ap.add_argument("--select-weight-only", action="store_true")
    ap.add_argument("--emit-grid", type=Path, default=None,
                    help="write every (second corpus, mix, Tatoeba weight) grid point under this directory "
                         "for the Kotlin dev-split selection, and nothing else")
    ap.add_argument("--allow-unpinned", action="store_true")
    ap.add_argument("--offline", action="store_true")
    args = ap.parse_args()
    cfg = CONFIGS[args.lang]
    if args.emit_grid is not None and cfg.leipzig2 is not None:
        raise SystemExit("--emit-grid starts from the single-corpus configuration; this one already mixes")
    contributors_path: Path = args.contributors or REPO / f"scripts/data/tatoeba-contributors-{cfg.code}.txt"

    paths: dict[str, Path] = {}
    hashes: dict[str, str] = {}
    for src in cfg.sources:
        paths[src.key], hashes[src.key] = ensure_source(src, args.cache, args.allow_unpinned, args.offline)

    lexicon, forms = load_vocab(REPO / "src/main/assets/dictionaries", cfg)
    vocab = lexicon | forms
    print(f"[vocab] {cfg.lexicon}: lexicon {len(lexicon)} + contraction forms {len(forms)} -> {len(vocab)}")

    # OOD files — surface text, same sentence splitting, never counted. Read first so their keys
    # can be excluded from training (cfg.exclude_eval_overlap).
    args.eval_dir.mkdir(parents=True, exist_ok=True)
    ood: dict[str, list[list[str | None]]] = {}
    eval_keys: set[str] = set()
    for key in ("ood_dev", "ood_test"):
        out = args.eval_dir / f"{key}_{cfg.code}.txt"
        sents: list[list[str | None]] = []
        with open(out, "w", encoding="utf-8") as fh:
            for text in conllu_texts(paths[key]):
                for seg in split_sentences(text, cfg.nfc):
                    toks = tokenize(seg)
                    if MIN_TOKENS <= word_count(toks) <= MAX_TOKENS:
                        fh.write(" ".join(seg.split()) + "\n")
                        sents.append(toks)
                        eval_keys.add(sentence_key(seg))
        ood[key] = sents
        print(f"[{key}] {len(sents)} sentences -> {out}")

    heldout_path = args.eval_dir / f"heldout_{cfg.code}.txt"
    excluded = frozenset(eval_keys) if cfg.exclude_eval_overlap else frozenset()
    seen: set[str] = set(excluded)
    second: CorpusCounts | None = None
    second_train: list[list[str | None]] = []
    with open(heldout_path, "w", encoding="utf-8") as held:
        def sink(sentence: str) -> None:
            held.write(sentence + "\n")
        leipzig, leipzig_train = count_corpus(
            "leipzig", leipzig_lines(paths["leipzig"]), vocab, seen, sink, cfg.nfc, excluded)
        tatoeba, tatoeba_train = count_corpus(
            "tatoeba", tatoeba_lines(paths["tatoeba"]), vocab, seen, sink, cfg.nfc, excluded)
        seen_after_tatoeba = set(seen) if args.emit_grid is not None else set()
        if cfg.leipzig2 is not None:
            second, second_train = count_corpus(
                "leipzig2", leipzig_lines(paths["leipzig2"]), vocab, seen, sink, cfg.nfc, excluded)
    del seen
    counted = [c for c in (leipzig, tatoeba, second) if c is not None]
    for c in counted:
        print(f"[{c.name}] train sentences {c.sentences}, held-out {c.heldout}, "
              f"duplicates {c.duplicates}, eval-overlap dropped {c.eval_overlap}, tokens {c.tokens}")

    # Artefact filter, per corpus (the fallback reference, without wordfreq, is the other corpus).
    ref, ref_name = wordfreq_reference(cfg.wordfreq_lang)
    jobs = [(leipzig, leipzig_train, tatoeba), (tatoeba, tatoeba_train, leipzig)]
    if second is not None:
        jobs.append((second, second_train, leipzig))
    artefacts = filter_artefacts(jobs, ref, ref_name, vocab)
    del leipzig_train, tatoeba_train, second_train, jobs

    if args.emit_grid is not None:
        return emit_grid(args, cfg, leipzig, tatoeba, seen_after_tatoeba, paths, vocab, excluded, ref, ref_name)

    # Tatoeba weight: fixed by the dev-split gate-metric selection (cfg.tatoeba_weight), given on
    # the command line, or selected here on OOD dev by the next-word proxy (ties -> lower weight).
    grid: list[dict[str, float]] = []
    if args.weight is not None:
        weight = args.weight
    elif cfg.tatoeba_weight is not None:
        weight = cfg.tatoeba_weight
    else:
        for w in WEIGHT_GRID:
            m = prune(*combine(mixture(leipzig, tatoeba, w, second, cfg.mix)))
            t1, t3, n = next_word_topk(m, ood["ood_dev"], vocab)
            pairs = sum(len(v) for v in m.table.values())
            size = len(encode(m, cfg.code)[0])
            grid.append({"weight": w, "top1": round(t1, 3), "top3": round(t3, 3), "n": n, "pairs": pairs,
                         "bytes": size})
            print(f"[select] tatoeba weight {w:<5} OOD-dev next-word top1 {t1:6.2f}%  top3 {t3:6.2f}%  "
                  f"(n={n}, pairs={pairs}, bytes={size}{'' if size <= SIZE_CAP_BYTES else ' OVER CAP'})")
        # Only weights whose model fits the size cap are eligible (a larger Tatoeba weight adds
        # pairs; Spanish's dev optimum sat at the grid edge, over the cap).
        eligible = [g for g in grid if g["bytes"] <= SIZE_CAP_BYTES]
        if not eligible:
            raise SystemExit(f"no Tatoeba weight gives a model within {SIZE_CAP_BYTES} bytes")
        best = max(eligible, key=lambda g: (g["top3"], -g["weight"]))
        weight = best["weight"]
        print(f"[select] chosen weight {weight}")
    if args.select_weight_only:
        return 0

    model = prune(*combine(mixture(leipzig, tatoeba, weight, second, cfg.mix)))
    blob, stats = encode(model, cfg.code)
    if len(blob) > SIZE_CAP_BYTES:
        raise SystemExit(f"model is {len(blob)} bytes, over the {SIZE_CAP_BYTES}-byte cap")
    t1, t3, n = next_word_topk(model, ood["ood_test"], vocab)
    print(f"[model] {stats} {len(blob)} bytes; OOD-test next-word top1 {t1:.2f}% top3 {t3:.2f}% (n={n})")

    args.out_dir.mkdir(parents=True, exist_ok=True)
    cklm = args.out_dir / f"{cfg.code}.cklm"
    cklm.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()

    # Tatoeba contributors whose sentences were counted (CC BY 2.0 FR attribution).
    names = sorted(tatoeba.contributors, key=lambda s: (s.lower(), s)) if weight > 0 else []
    contributors_path.parent.mkdir(parents=True, exist_ok=True)
    with open(contributors_path, "w", encoding="utf-8") as fh:
        fh.write(f"# Tatoeba contributors whose {cfg.name} sentences were counted into\n")
        fh.write(f"# src/main/assets/lm/{cfg.code}.cklm (CC BY 2.0 FR, https://tatoeba.org).\n")
        fh.write(f"# Snapshot: {cfg.tatoeba.filename.split('/')[-1]} sha256 {hashes['tatoeba']}\n")
        fh.write("# Generated by scripts/build_static_lm.py — do not edit by hand.\n")
        for name in names:
            fh.write(name + "\n")

    weights: dict[str, float] = {"leipzig": 1.0 - cfg.mix, "tatoeba": weight}
    if second is not None:
        weights["leipzig2"] = cfg.mix
    if cfg.tatoeba_weight is not None and args.weight is None:
        rule = (f"Tatoeba weight{' and leipzig2 mix' if second is not None else ''} chosen on the OOD dev split "
                f"({cfg.ood_label} dev) by the S1 gate metric — StaticLmTapEvalTest prefix-1 top-3 delta, "
                f"STATIC_LM_EVAL_SPLIT=dev — over the pre-registered grid within the {SIZE_CAP_BYTES}-byte cap "
                f"(docs/eval/2026-09-29-static-lm-multilingual.md, es/pt/sv retry)")
    else:
        rule = (f"max OOD-dev ({cfg.ood_label} dev) next-word top-3 of the pruned model among weights "
                f"whose model fits the {SIZE_CAP_BYTES}-byte cap; ties -> lower weight")
    corpus: dict[str, object] = {
        "sentenceTokens": [MIN_TOKENS, MAX_TOKENS],
        "heldoutFraction": HELDOUT_FRACTION,
        "split": "sha1(normalised sentence)[:8] / 0xFFFFFFFF < heldoutFraction",
        "trainSentences": {c.name: c.sentences for c in counted},
        "trainTokens": {c.name: c.tokens for c in counted},
        "weights": weights,
        "weightGrid": grid,
        "weightRule": rule,
    }
    # Recorded only where the step ran, so the shipped en sidecar stays byte-identical.
    if cfg.nfc:
        corpus["normalisation"] = "NFC"
    if cfg.exclude_eval_overlap:
        corpus["evalOverlapDropped"] = {c.name: c.eval_overlap for c in counted}
    sidecar = {
        "format": "CKLM",
        "version": FORMAT_VERSION,
        "language": cfg.code,
        "vocab": stats["vocab"],
        "prevs": stats["prevs"],
        "pairs": stats["pairs"],
        "bytes": len(blob),
        "sha256": digest,
        "model": {
            "order": 2,
            "minCount": MIN_COUNT,
            "topK": TOP_K,
            "quantStepsPerNat": QUANT_STEPS_PER_NAT,
            "vocabulary": cfg.vocabulary_note,
            # Machine-readable form of the above: StaticLmAssetDriftTest rebuilds the allowed set
            # from these files, so the pin cannot drift from the builder's configuration.
            "lexicon": cfg.lexicon,
            "contractionFiles": list(cfg.contraction_files),
        },
        "corpus": corpus,
        "artefactFilter": {
            "ratio": ARTEFACT_RATIO,
            "minCount": ARTEFACT_MIN_COUNT,
            "reference": ref_name if ref else "the other corpus",
            "dropped": {k: sorted(v) for k, v in artefacts.items()},
        },
        "sources": [
            {
                "key": s.key,
                "url": s.url,
                "sha256": hashes[s.key],
                "pinned": hashes[s.key] == s.sha256,
                "license": s.license,
                "shipped": s.shipped,
                "note": s.note,
            }
            for s in cfg.sources
        ],
        "tatoebaContributors": len(names),
    }
    (args.out_dir / f"{cfg.code}.json").write_text(json.dumps(sidecar, indent=2, ensure_ascii=False) + "\n", "utf-8")
    print(f"[write] {cklm} sha256 {digest}; {len(names)} Tatoeba contributors -> {contributors_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
CleverKeys word-list builder — one-pass evidence classifier (all languages).

Generates the "casual but large" dictionary source
(`scripts/dictionaries/<lang>/<lang>_words.txt`) from wordfreq's frequency-ranked
candidates, classified by POSITIVE ORACLES (spellcheckers, the AOSP LatinIME
mobile-keyboard wordlists, NLTK names [en], contraction keys, a curated
allowlist) and NEGATIVE evidence (edit-distance-1 typo patterns with tiered
zipf gaps, foreign-language dominance, a curated blocklist).

Generalized from the English-only `build_en_wordlist.py` (2026-07): the band
architecture, carryover guarantee, 1-/2-char rules and review-artifact loop are
shared verbatim; per-language differences live in `LANG_CONFIG`.

Band structure (per maintainer spec, 2026-06-25):
  band 1  rank <  --band  : conservative — keep unless a negative fires with
                            no positive oracle to rescue it
  band 2  rank <  --top   : aggressive — keep ONLY with a positive oracle
                            (name-only evidence is NOT sufficient here; obscure
                            proper nouns make bad autocorrect targets)
  carryover (shipped dict): keep unless confirmed junk — no silent regressions
  For oracle-less languages (id/ms/tl) the config pins band == top: band 2's
  "positive oracle required" is meaningless with zero oracles and would empty
  the tail, so the whole stream is classified conservatively (negatives-only).

Oracle-availability tiers (see LANG_CONFIG):
  Tier A (spellcheckers + AOSP): en fr de es nl ru
  Tier B (pyspellchecker + AOSP): it pt
  Tier C (AOSP only): sv el tr — AOSP LatinIME is the sole band-2 oracle
  Tier D (no positive oracles): id ms tl — band == top, negatives-only
  sw is NOT ported (no wordfreq data; corpus-file pipeline unchanged).

English-only elements with no non-EN analog (documented omissions):
  BRITISH_RULES suffix mapping, NLTK words/names, VALID_SEED_KEEP, the
  held-out --eval user-export coverage measurement, and the flat-JSON
  asset fallback (non-EN assets ship bin-only).

Resources (all offline; see scripts/data/PROVENANCE.md):
  scripts/data/aosp_<lang>_wordlist.txt.gz         AOSP LatinIME headwords (Apache-2.0)
  scripts/dictionaries/<lang>/<lang>_allowlist.txt force-keep (curated, delete-to-exclude)
  scripts/dictionaries/<lang>/<lang>_blocklist.txt force-drop (overrides everything)
  src/main/assets/dictionaries/contractions_*.json functional keys
  shipped dict (assets bin / langpack zip)         carryover basis

Usage:
  python3 scripts/build_wordlist.py                       # en, report mode
  python3 scripts/build_wordlist.py --lang fr             # fr, report mode
  python3 scripts/build_wordlist.py --lang fr --write     # regenerate fr artifacts
  python3 scripts/build_wordlist.py --top 100000 --band 65000

AOSP snapshot refresh (documented, not automated):
  URL: https://android.googlesource.com/platform/packages/inputmethods/LatinIME/
       +/refs/heads/main/dictionaries/<code>_wordlist.combined.gz?format=TEXT (base64)
  Extract `word=…` headwords + flags to `headword\\tflags` lines, gzip -9, update
  PROVENANCE.md.
"""

import argparse
import gzip
import json
import re
import struct
import subprocess
import sys
import time
import zipfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
ASSETS = PROJECT_ROOT / "src" / "main" / "assets" / "dictionaries"
DICT_DIR = SCRIPT_DIR / "dictionaries"
EN_DIR = DICT_DIR / "en"
DATA_DIR = SCRIPT_DIR / "data"

sys.path.insert(0, str(SCRIPT_DIR))
from detect_misspellings import BRITISH_RULES, FOREIGN_LANGS, find_misspellings  # noqa: E402
from generate_binary_dict import _is_latin_word  # noqa: E402
from build_dictionary import frequency_to_rank, get_word_frequency  # noqa: E402

try:
    from wordfreq import iter_wordlist, zipf_frequency
except ImportError:
    sys.exit("Missing: pip install wordfreq")
try:
    from nltk.corpus import words as nltk_words, names as nltk_names
except ImportError:
    sys.exit("Missing: pip install nltk && python3 -c \"import nltk; nltk.download('words'); nltk.download('names')\"")
try:
    from spellchecker import SpellChecker
except ImportError:
    sys.exit("Missing: pip install pyspellchecker")

ASCII_LOWER = "abcdefghijklmnopqrstuvwxyz"

# Curation-file word patterns per script (allow/blocklist parsing).
WORD_RE_BY_SCRIPT = {
    "latin": re.compile(r"[a-z][a-z'-]*"),
    "greek": re.compile(r"[Ͱ-Ͽἀ-῿][Ͱ-Ͽἀ-῿'-]*"),
    "cyrillic": re.compile(r"[Ѐ-ӿ][Ѐ-ӿ'-]*"),
}
WORD_RE = WORD_RE_BY_SCRIPT["latin"]  # back-compat alias (en behaviour)


@dataclass(frozen=True)
class LangConfig:
    """Per-language knobs for the evidence classifier.

    A configured-but-missing oracle is a HARD BUILD FAILURE (extends the
    fail-loud discipline of the oracle runners): `hunspell`/`aspell` name the
    system dictionary, `pyspell` the pyspellchecker language, `aosp` the
    snapshot file under scripts/data/. `None` means "this language has no such
    oracle" — never silently degrade a configured one to None.
    """
    name: str                       # display name (langpack manifest)
    alphabet: str                   # ed1 substitution/insertion charset
    script: str = "latin"           # latin | greek | cyrillic (candidate gate)
    hunspell: "str | None" = None   # hunspell -d <dict>
    aspell: "str | None" = None     # aspell -l <lang>
    pyspell: "str | None" = None    # SpellChecker(language=...)
    aosp: "str | None" = None       # scripts/data/aosp_<lang>_wordlist.txt.gz
    case_policy: str = "plain"      # en | de_nouns | plain
    foreign: tuple = ()             # foreign-dominance comparison languages
    top: int = 80_000               # wordfreq candidate depth
    band: int = 40_000              # conservative/aggressive boundary
    limit: "int | None" = None      # final size cap (None = ship all survivors)
    bundle: bool = False            # also write src/main/assets/dictionaries bin
    contractions: "str | None" = None  # assets contraction-map filename
    must_include: tuple = ()        # per-language guard words (hard exit 1)


# NOTE: the EN entry reproduces build_en_wordlist.py's constants exactly —
# `--lang en` report output is bit-identical to the pre-rename script (G1
# parity gate). EN's MUST_INCLUDE deliberately contains NO words from the
# user's dictionary exports — those form the held-out --eval set.
LANG_CONFIG: "dict[str, LangConfig]" = {
    "en": LangConfig(
        name="English", alphabet=ASCII_LOWER, hunspell="en_US", aspell="en_GB",
        pyspell="en", aosp="aosp_en_wordlist.txt.gz", case_policy="en",
        foreign=tuple(FOREIGN_LANGS), top=150_000, band=65_000, bundle=True,
        contractions="contractions_non_paired.json",
        must_include=(
            "the", "of", "and", "you",                    # core
            "gonna", "wanna", "yall", "lol", "idk",       # casual register
            "dont", "cant", "im", "theyre",               # contraction keys (FUNC)
            "json",                                       # tech register (allowlist)
            "a", "i",                                     # single letters (carryover)
        ),
    ),
    # ── Tier A: spellchecker(s) + AOSP ────────────────────────────────────────
    "es": LangConfig(
        name="Spanish", alphabet=ASCII_LOWER + "áéíóúüñ", aspell="es",
        pyspell="es", aosp="aosp_es_wordlist.txt.gz",
        foreign=("en", "fr", "pt", "it", "de", "nl", "ca", "ro"),
        top=90_000, band=45_000, limit=50_000, bundle=True,
        contractions="contractions_es.json",
        must_include=("que", "años", "niño", "también"),
    ),
    "fr": LangConfig(
        name="French", alphabet=ASCII_LOWER + "àâæçéèêëîïôùûüÿœ",
        hunspell="fr_FR", aspell="fr", pyspell="fr", aosp="aosp_fr_wordlist.txt.gz",
        foreign=("en", "es", "pt", "it", "de", "nl", "ro"),
        top=80_000, band=40_000, limit=40_000, bundle=True,
        contractions="contractions_fr.json",
        must_include=("être", "été", "français", "même"),
    ),
    "de": LangConfig(
        # NOTE no ß in the alphabet and no ß must-include forms: wordfreq
        # casefolds ß→ss, so the candidate stream (and thus the dict — same as
        # the previous 25k build) carries "strasse"/"grösse" spellings only.
        name="German", alphabet=ASCII_LOWER + "äöü", aspell="de",
        pyspell="de", aosp="aosp_de_wordlist.txt.gz", case_policy="de_nouns",
        foreign=("en", "fr", "es", "it", "pt", "nl", "sv", "da", "nb", "pl", "cs"),
        top=80_000, band=40_000, limit=40_000, bundle=True,
        contractions="contractions_de.json",
        must_include=("über", "grösse", "strasse", "nicht"),
    ),
    "nl": LangConfig(
        name="Dutch", alphabet=ASCII_LOWER + "éëïö", hunspell="nl_NL",
        pyspell="nl", aosp="aosp_nl_wordlist.txt.gz",
        foreign=("en", "de", "fr", "da", "sv"),
        top=80_000, band=40_000, limit=40_000,
        contractions="contractions_nl.json",
        must_include=("één", "zijn", "niet"),
    ),
    "ru": LangConfig(
        name="Russian", alphabet="абвгдежзийклмнопрстуфхцчшщъыьэюяё",
        script="cyrillic", hunspell="ru_RU", pyspell="ru",
        aosp="aosp_ru_wordlist.txt.gz",
        foreign=("uk", "bg", "mk", "sr"),
        top=90_000, band=50_000, limit=50_000,
        must_include=("что", "ещё", "который"),
    ),
    # ── Tier B: pyspellchecker + AOSP ─────────────────────────────────────────
    "it": LangConfig(
        name="Italian", alphabet=ASCII_LOWER + "àèéìòóù", pyspell="it",
        aosp="aosp_it_wordlist.txt.gz",
        foreign=("en", "es", "fr", "pt", "de", "ro"),
        top=80_000, band=40_000, limit=40_000, bundle=True,
        contractions="contractions_it.json",
        must_include=("perché", "più", "città", "della"),
    ),
    "pt": LangConfig(
        name="Portuguese", alphabet=ASCII_LOWER + "áâãàçéêíóôõú", pyspell="pt",
        aosp="aosp_pt_wordlist.txt.gz",  # union of AOSP pt_BR + pt_PT
        foreign=("en", "es", "fr", "it", "de", "ro"),
        top=80_000, band=40_000, limit=40_000, bundle=True,
        contractions="contractions_pt.json",
        must_include=("não", "você", "coração", "ações"),
    ),
    # ── Tier C: AOSP is the sole band-2 oracle ────────────────────────────────
    "sv": LangConfig(
        name="Swedish", alphabet=ASCII_LOWER + "åäö",
        aosp="aosp_sv_wordlist.txt.gz",
        foreign=("en", "de", "nl", "da", "nb", "fi"),
        top=80_000, band=40_000, limit=40_000, bundle=True,
        contractions="contractions_sv.json",
        must_include=("på", "över", "många", "går"),
    ),
    "el": LangConfig(
        name="Greek",
        alphabet="αβγδεζηθικλμνξοπρστυφχψωάέήίόύώϊϋΐΰς", script="greek",
        aosp="aosp_el_wordlist.txt.gz",
        foreign=(),  # no same-script wordfreq neighbour — filter inert
        top=50_000, band=25_000,  # stream is 46,306 deep: band 2 curates the tail via AOSP
        must_include=("και", "είναι", "ελληνικά"),
    ),
    "tr": LangConfig(
        name="Turkish", alphabet=ASCII_LOWER + "çğıöşü",
        aosp="aosp_tr_wordlist.txt.gz",
        foreign=("en", "de"),
        top=65_000, band=40_000, limit=40_000,  # stream is 61,076 deep (full small list)
        must_include=("için", "değil", "büyük"),
    ),
    # ── Tier D: no positive oracles — band == top, negatives-only ─────────────
    "id": LangConfig(
        name="Indonesian", alphabet=ASCII_LOWER,
        foreign=("en", "ms", "nl"), top=35_000, band=35_000,
        must_include=("yang", "tidak"),
    ),
    "ms": LangConfig(
        name="Malay", alphabet=ASCII_LOWER,
        foreign=("en", "id", "nl"), top=30_000, band=30_000,
        must_include=("yang", "tidak"),
    ),
    "tl": LangConfig(
        name="Tagalog", alphabet=ASCII_LOWER,
        foreign=("en", "es"), top=30_000, band=30_000,
        must_include=("ang", "hindi"),
    ),
}

# Contraction maps larger than this are elision EXPANSION tables (fr: ~26k,
# it: ~22k apostrophe-stripped full forms), not the small functional-key sets
# the EN force-keep path was designed for (en: 119, nl: 118, de: 4).
# Force-keeping 20k+ mechanical elision forms would crowd out organic
# vocabulary under --limit, so large maps demote to POSITIVE-EVIDENCE-ONLY:
# a func word in the candidate stream is rescued (band 2 / negatives), but
# absent ones are not injected. Matches shipped behaviour — the current fr/it
# dicts contain none of these keys and runtime elision lives in
# ContractionManager, not the dictionary.
FUNC_FORCEKEEP_MAX = 1_000

# Valid-seed words (typo-drop-rescue spec, ENGLISH-ONLY): must never be
# silently lost when present in the candidate stream AND carrying positive
# evidence. These assert the oracle layering, not raw membership.
VALID_SEED_KEEP = ["mgmt", "govt", "abt", "tbh", "ngl"]  # allowlisted → must be kept


def _is_script_word(word: str, script: str) -> bool:
    """Script gate for the candidate stream (replaces the EN-only Latin gate)."""
    if script == "latin":
        return _is_latin_word(word)
    if script == "greek":
        return all(0x0370 <= ord(c) <= 0x03FF or 0x1F00 <= ord(c) <= 0x1FFF for c in word)
    if script == "cyrillic":
        return all(0x0400 <= ord(c) <= 0x04FF for c in word)
    raise ValueError(f"unknown script {script!r}")


def read_list_file(path: Path, script: str = "latin") -> "set[str]":
    """Load a '#'-commented one-word-per-line curation file."""
    out: "set[str]" = set()
    if not path.exists():
        return out
    word_re = WORD_RE_BY_SCRIPT[script]
    for line in path.read_text(encoding="utf-8").splitlines():
        w = line.split("#", 1)[0].strip().lower()
        if w and word_re.fullmatch(w):
            out.add(w)
    return out


def hunspell_accepted(tokens: "list[str]", transform, dic: str = "en_US") -> "set[str]":
    """Run hunspell -G (echo ACCEPTED) over transformed tokens; return the
    lowercase originals that were accepted.

    FAIL LOUD like aspell_accepted: hunspell feeds the primary `hun_lower`
    oracle, so a silently-empty result (missing binary / dictionary) would
    mark nothing spell-valid and cascade into over-aggressive drops. Abort the
    build if hunspell can't run rather than shipping a mis-filtered dict.
    """
    if not tokens:
        return set()
    mapped = [transform(t) for t in tokens]
    # -i UTF-8 pins the input encoding for accented/Cyrillic dictionaries;
    # the en_US invocation is kept flag-identical to the original EN builder.
    cmd = ["hunspell", "-d", dic, "-G"] + ([] if dic == "en_US" else ["-i", "UTF-8"])
    try:
        proc = subprocess.run(
            cmd,
            input="\n".join(mapped), capture_output=True, text=True,
            timeout=600,
        )
    except FileNotFoundError as exc:
        sys.exit(f"hunspell oracle: binary not found ({exc}). Install hunspell + "
                 f"the {dic} dictionary (pacman -S hunspell-{dic.split('_')[0]}) "
                 f"before building the dict.")
    except subprocess.TimeoutExpired:
        sys.exit(f"hunspell oracle: timed out on {len(tokens)} tokens. Aborting.")
    if proc.returncode != 0:
        sys.exit(f"hunspell oracle ({dic}): exited {proc.returncode}. "
                 f"stderr: {proc.stderr.strip()!r}. Aborting.")
    accepted = {line.strip().lower() for line in proc.stdout.splitlines() if line.strip()}
    # -G echoes every accepted word, so a large candidate stream that yields
    # ZERO acceptances means hunspell did not run / had no dictionary loaded.
    if not accepted and len(tokens) >= 100:
        sys.exit(f"hunspell oracle ({dic}): accepted 0 of {len(tokens)} tokens (no "
                 f"parseable output). hunspell likely ran without a dictionary; "
                 f"aborting rather than under-filtering.")
    return {t for t, m in zip(tokens, mapped) if m.lower() in accepted}


def aspell_accepted(tokens: "list[str]", lang: str = "en_GB", transform=None) -> "set[str]":
    """aspell list prints MISSPELLED tokens; accepted = complement.

    `transform` maps each token before checking (case probes for languages
    without a hunspell dictionary); None preserves the original EN behaviour
    byte-for-byte.

    FAIL LOUD, never fail OPEN: the caller unions this oracle into `spell`,
    which gates the typo/foreign negative-evidence filters. If aspell fails to
    run (missing binary, missing dictionary, timeout) it emits no output → a
    silently-empty `bad` set would mark EVERY token spell-valid, disabling the
    filter and poisoning the shipped dict with corpus noise. So we distinguish
    "aspell ran and found no misspellings" (valid, only plausible for tiny/clean
    inputs) from "aspell did not run", and abort the build on the latter.
    """
    if not tokens:
        return set()
    mapped = [transform(t) for t in tokens] if transform else list(tokens)
    cmd = ["aspell", "-l", lang, "list"] + ([] if lang == "en_GB" else ["--encoding=utf-8"])
    try:
        proc = subprocess.run(
            cmd,
            input="\n".join(mapped), capture_output=True, text=True,
            timeout=600,
        )
    except FileNotFoundError as exc:
        sys.exit(f"aspell oracle: binary not found ({exc}). Install aspell + "
                 f"aspell-{lang} dictionary, or the spell filter would fail open "
                 f"and poison the dict.")
    except subprocess.TimeoutExpired:
        sys.exit(f"aspell oracle: timed out on {len(tokens)} tokens. Aborting "
                 f"rather than shipping an unfiltered dict.")
    if proc.returncode != 0:
        sys.exit(f"aspell oracle ({lang}): exited {proc.returncode}. "
                 f"stderr: {proc.stderr.strip()!r}. Aborting rather than "
                 f"treating every token as correctly spelled.")
    bad = {line.strip() for line in proc.stdout.splitlines() if line.strip()}
    # aspell echoes nothing for a correctly-spelled token AND nothing when it
    # never ran. A run over a large candidate stream that flags ZERO
    # misspellings is not credible — it means aspell produced no parseable
    # output despite non-empty input, so fail loud instead of trusting it.
    if not bad and len(tokens) >= 100:
        sys.exit(f"aspell oracle ({lang}): flagged 0 misspellings across {len(tokens)} "
                 f"tokens (no parseable output). This indicates aspell did not "
                 f"run correctly; aborting rather than failing open.")
    return {t for t, m in zip(tokens, mapped) if m not in bad}


def load_aosp(path: Path, script: str) -> "set[str]":
    """AOSP LatinIME headwords (lowercase, alpha only, `nonword` flag excluded).

    Greek: wordfreq CASEFOLDS its corpus, and Python casefolding maps final
    sigma ς→σ — so the entire el candidate stream (and the shipped el pack)
    carries word-final σ. AOSP headwords use proper ς orthography; without
    remapping, the sole el oracle would be blind to every sigma-final
    candidate (measured 2026-07-20: 4,670 shipped words mis-dropped as
    band2-no-oracle purely from the ς/σ mismatch). Word-final ς is therefore
    normalized to the stream's σ convention here. The σ-final display forms
    are a documented wordfreq-status-quo caveat, not a fix target this round.
    """
    out: "set[str]" = set()
    with gzip.open(path, "rt", encoding="utf-8") as fp:
        for line in fp:
            parts = line.rstrip("\n").split("\t")
            w, flags = parts[0], (parts[1] if len(parts) > 1 else "")
            if "nonword" in flags:
                continue
            lw = w.lower()
            if script == "greek" and lw.endswith("ς"):
                lw = lw[:-1] + "σ"
            if lw.isalpha() and _is_script_word(lw, script):
                out.add(lw)
    return out


def read_ckdt_words(data: bytes, origin: str) -> "set[str]":
    """Parse the canonical-word section of a CKDT V2 binary → lowercase word set.

    Correct V2 parse (uint16 word length + uint8 rank, canonical offset from
    the 48-byte header). Fails loud on magic/version mismatch — the carryover
    basis feeding the no-silent-regression guarantee must never be garbage.
    """
    magic, version = struct.unpack_from("<II", data, 0)
    if magic != 0x54444B43 or version != 2:
        sys.exit(f"carryover basis {origin}: bad CKDT header "
                 f"(magic={hex(magic)}, version={version})")
    wc = struct.unpack_from("<I", data, 12)[0]
    can_off = struct.unpack_from("<I", data, 16)[0]
    p, words = can_off, set()
    for _ in range(wc):
        (ln,) = struct.unpack_from("<H", data, p); p += 2
        words.add(data[p:p + ln].decode("utf-8").lower()); p += ln + 1
    return words


def load_shipped(lang: str, cfg: LangConfig) -> "set[str]":
    """Currently-shipped word set (the carryover basis).

    en        → assets en_enhanced.json (the original EN builder's basis)
    bundled   → assets <lang>_enhanced.bin
    pack-only → scripts/dictionaries/langpack-<lang>.zip :: dictionary.bin
    """
    if lang == "en":
        return {w.lower() for w in json.loads((ASSETS / "en_enhanced.json").read_text()).keys()}
    if cfg.bundle:
        path = ASSETS / f"{lang}_enhanced.bin"
        if not path.exists():
            sys.exit(f"carryover basis missing: {path}")
        return read_ckdt_words(path.read_bytes(), str(path))
    zpath = DICT_DIR / f"langpack-{lang}.zip"
    if not zpath.exists():
        sys.exit(f"carryover basis missing: {zpath}")
    with zipfile.ZipFile(zpath) as zf:
        return read_ckdt_words(zf.read("dictionary.bin"), f"{zpath}::dictionary.bin")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", default="en", choices=sorted(LANG_CONFIG),
                    help="language to build (default en)")
    ap.add_argument("--top", type=int, default=None, help="wordfreq candidate depth "
                    "(default per-language; band 2 is oracle-gated so extra depth adds only oracle-approved words)")
    ap.add_argument("--band", type=int, default=None,
                    help="conservative/aggressive band boundary (default per-language)")
    ap.add_argument("--limit", type=int, default=None,
                    help="final size cap: keep the N best-ranked survivors (allow/func/must-include "
                         "protected). Default per-language; 0 disables the cap.")
    ap.add_argument("--write", action="store_true",
                    help="regenerate <lang>_words.txt + binary dict (en: + json)")
    ap.add_argument("--review-dir", type=Path, default=Path.home() / "git" / "swype",
                    help="where the annotated review artifacts are written")
    ap.add_argument("--eval", type=Path, default=None,
                    help="held-out eval wordlist (user dictionary exports); coverage is "
                         "reported after classification. Defaults to the EN export file "
                         "for --lang en; no non-EN eval data exists.")
    ap.add_argument("--eval-blind", action="store_true",
                    help="ALSO exclude eval words from the allowlist force-keep path, so "
                         "the coverage measurement is fully organic (the 2026-07-03 "
                         "experiment mode). Off by default: the production allowlist "
                         "carries merit-restored words that ARE in the eval set.")
    args = ap.parse_args()
    t0 = time.time()

    lang = args.lang
    cfg = LANG_CONFIG[lang]
    top = args.top if args.top is not None else cfg.top
    band = args.band if args.band is not None else cfg.band
    limit = cfg.limit if args.limit is None else (args.limit or None)
    if args.eval is None and lang == "en":
        args.eval = EN_DIR / "en_user_export_eval.txt"
    lang_dir = DICT_DIR / lang
    is_en = lang == "en"
    script = cfg.script
    print(f"language: {lang} ({cfg.name})  top={top} band={band} limit={limit} "
          f"case={cfg.case_policy} script={script}")

    # ---------------- Stage A: resources ----------------
    allow = read_list_file(lang_dir / f"{lang}_allowlist.txt", script)
    block = read_list_file(lang_dir / f"{lang}_blocklist.txt", script)
    eval_set = read_list_file(args.eval, script) if args.eval and args.eval.exists() else set()
    if args.eval_blind:
        contaminated = allow & eval_set
        if contaminated:
            print(f"eval-blind: {len(contaminated)} eval words removed from allowlist "
                  f"({', '.join(sorted(contaminated)[:12])}{'…' if len(contaminated) > 12 else ''})")
            allow -= contaminated
    if cfg.contractions:
        cpath = ASSETS / cfg.contractions
        ctext = cpath.read_text() if cpath.exists() else ""
        func = {k.lower() for k in json.loads(ctext).keys() if k.isalpha()} \
            if ctext.strip() not in ("", "{}") else set()
    else:
        func = set()
    # Large elision-expansion maps rescue but do not force-keep (see constant doc).
    func_forcekeep = len(func) <= FUNC_FORCEKEEP_MAX
    if cfg.aosp:
        aosp_path = DATA_DIR / cfg.aosp
        if not aosp_path.exists():
            sys.exit(f"AOSP oracle configured but snapshot missing: {aosp_path} "
                     f"(refresh procedure in the module docstring)")
        aosp = load_aosp(aosp_path, script)
    else:
        aosp = set()
    nltk_set = {w.lower() for w in nltk_words.words()} if is_en else set()
    name_set = {w.lower() for w in nltk_names.words()} if is_en else set()
    if cfg.pyspell:
        try:
            pyspell_set = set((SpellChecker() if is_en else SpellChecker(language=cfg.pyspell))
                              .word_frequency.words())
        except Exception as exc:
            sys.exit(f"pyspellchecker oracle configured for {cfg.pyspell!r} but failed to "
                     f"load: {exc}. Never silently degrade a configured oracle.")
    else:
        pyspell_set = set()
    shipped = load_shipped(lang, cfg)
    print(f"resources: allow={len(allow)} block={len(block)} func={len(func)}"
          f"{'' if func_forcekeep else '(evidence-only)'} aosp={len(aosp)} "
          f"nltk={len(nltk_set)} names={len(name_set)} pyspell={len(pyspell_set)} shipped={len(shipped)}")

    # ---------------- Stage B: candidates ----------------
    ranked: "list[str]" = []
    seen: "set[str]" = set()
    for w in iter_wordlist(lang):
        if 2 <= len(w) <= 25 and w.isalpha() and _is_script_word(w, script) and w not in seen:
            seen.add(w)
            ranked.append(w)
            if len(ranked) >= top:
                break
    rank_of = {w: i for i, w in enumerate(ranked)}
    func_extras = func if func_forcekeep else set()
    extras = sorted((allow | shipped | func_extras) - seen)   # beyond top-N or non-candidates
    universe = ranked + [w for w in extras if 1 <= len(w) <= 25 and _is_script_word(w, script)]
    zc = {w: zipf_frequency(w, lang) for w in universe}
    print(f"candidates: ranked={len(ranked)} extras={len(extras)} universe={len(universe)}")

    # ---------------- Stage C: positive oracles ----------------
    alpha_tokens = [w for w in universe if w.isalpha()]
    universe_set = set(universe)
    lower_ok: "set[str]" = set()
    cap_ok: "set[str]" = set()
    upper_ok: "set[str]" = set()
    if cfg.hunspell:
        lower_ok = hunspell_accepted(alpha_tokens, lambda w: w, cfg.hunspell)
        cap_ok = hunspell_accepted(alpha_tokens, lambda w: w.capitalize(), cfg.hunspell)
        upper_ok = hunspell_accepted(alpha_tokens, lambda w: w.upper(), cfg.hunspell)
    elif cfg.aspell:  # aspell as the case-probing checker (de/es have no hunspell dict)
        lower_ok = aspell_accepted(alpha_tokens, cfg.aspell, lambda w: w)
        cap_ok = aspell_accepted(alpha_tokens, cfg.aspell, lambda w: w.capitalize())
        upper_ok = aspell_accepted(alpha_tokens, cfg.aspell, lambda w: w.upper())
    if is_en:
        # EN keeps its original composition exactly: hunspell 3-case + aspell
        # en_GB + BRITISH_RULES + NLTK + pyspellchecker (G1 parity).
        gb_ok = aspell_accepted(alpha_tokens)
        check_set = lower_ok | nltk_set | pyspell_set
        british = {w for w in alpha_tokens
                   for sfx, us in BRITISH_RULES.items()
                   if w.endswith(sfx) and (w[: -len(sfx)] + us) in check_set}
        spell = lower_ok | gb_ok | british | (nltk_set & universe_set) | (pyspell_set & universe_set)
        name = cap_ok | (name_set & universe_set)
    else:
        spell = lower_ok | (pyspell_set & universe_set)
        if cfg.case_policy == "de_nouns":
            # German nouns are capitalized: cap-acceptance IS spell-validity
            # (lower-only would reject the entire noun class). No name rescue.
            spell |= cap_ok
            name = set()
        else:  # plain
            # Cap-only acceptance = proper noun: rescue-only, never band-2-sufficient.
            name = cap_ok - lower_ok
    # True initialisms only: a checker accepts ALL-CAPS of ANY dic entry
    # ("JESSICA"), so raw UPPER-acceptance would leak the whole proper-noun
    # class into band 2. A word valid ONLY in its all-caps form (ISP, NASA) is
    # a real acronym.
    acro = upper_ok - cap_ok - lower_ok
    print(f"oracles: spell={len(spell)} name(+cap)={len(name)} acro={len(acro)} "
          f"aosp∩universe={len(aosp & universe_set)} ({time.time()-t0:.0f}s)")

    # ---------------- Stage D: negative evidence ----------------
    positive = lambda w: (w in spell or w in aosp or w in acro or w in allow or w in func)  # noqa: E731

    # Expressive-elongation exemption: "sooo"/"ahhh"/"aaah" are casual language
    # (they also appear in de/nl/id corpora, which made the foreign filter fire
    # on them). A word containing a letter-run of length >= 3 whose fully
    # collapsed form is spell-valid is an elongation, not foreign/typo.
    # Runs of exactly 2 are NOT exempt — that would shield real typos
    # ("tripple" -> "triple").
    def is_elongation(w: str) -> bool:
        if not re.search(r"(.)\1\1", w):
            return False
        collapsed = re.sub(r"(.)\1+", r"\1", w)
        return len(collapsed) >= 1 and (collapsed in hf_valid or collapsed in spell)

    at_risk = [w for w in universe
               if len(w) >= 3 and zc[w] < 3.5 and not positive(w) and w.isalpha()]
    # Known-good set for the ed1 typo detector: spell-valid high-frequency
    # words. Tier C languages substitute the AOSP oracle (their only positive
    # source); Tier D has no oracle at all, so high-zipf (>= 3.5) corpus words
    # stand in — at that frequency wordfreq tokens are overwhelmingly real.
    if spell:
        hf_valid = {w for w in spell if zc.get(w, 0) >= 3.5}
    elif aosp:
        hf_valid = {w for w in (aosp & universe_set) if zc.get(w, 0) >= 3.5}
    else:
        hf_valid = {w for w in universe if zc[w] >= 3.5}
    at_risk = [w for w in at_risk if not is_elongation(w)]
    typo: "dict[str, tuple[str, str, float]]" = {}
    for lo, hi, gap in ((6, 99, 2.0), (4, 5, 2.5), (3, 3, 3.0)):
        part = [w for w in at_risk if lo <= len(w) <= hi]
        for w, match, rule, g, _ in find_misspellings(part, hf_valid, gap,
                                                      alphabet=cfg.alphabet, lang=lang):
            typo[w] = (match, rule, g)
    # Foreign-language DOMINANCE (not mere presence): shared tokens (elongations,
    # anime/sports entities) score similarly in several languages — require the
    # foreign zipf to exceed the target language's by a full point before
    # calling it foreign. (For non-EN targets the #1 contaminant is English.)
    avail_foreign = []
    for fl in cfg.foreign:
        try:
            zipf_frequency("test", fl)
            avail_foreign.append(fl)
        except LookupError:
            print(f"foreign-language filter: wordfreq has no data for {fl!r} — skipped")
    foreign: "dict[str, tuple[str, float]]" = {}
    for w in at_risk:
        best_l, best_z = "", 0.0
        for fl in avail_foreign:
            fz = zipf_frequency(w, fl)
            if fz > best_z:
                best_l, best_z = fl, fz
        if best_z > 3.0 and best_z > zc[w] + 1.0:
            foreign[w] = (best_l, best_z)
    print(f"negatives: at_risk={len(at_risk)} typo={len(typo)} foreign={len(foreign)} ({time.time()-t0:.0f}s)")

    # ---------------- Stage E: decision ----------------
    keep: "dict[str, str]" = {}     # word -> keep-reason
    drop: "dict[str, str]" = {}     # word -> drop-reason
    # Band-2 sufficiency: spell/AOSP/acro, plus contraction keys when the map
    # is evidence-only (a large map's keys can't force-keep, but a key that IS
    # in the candidate stream is real typed vocabulary — rescue it).
    band_pos = lambda w: (w in spell or w in aosp or w in acro                      # noqa: E731
                          or (not func_forcekeep and w in func))
    for w in universe:
        if w in block or not _is_script_word(w, script):
            drop[w] = "block"
            continue
        if func_forcekeep and w in func:
            keep[w] = "func"
            continue
        if w in allow:
            keep[w] = "allow"
            continue
        pos = band_pos(w)
        rank = rank_of.get(w)
        if len(w) == 1:
            (keep if w in shipped else drop)[w] = "carryover-1char" if w in shipped else "1char"
            continue
        if len(w) == 2:
            # Casual 2-char tokens (gf, jk, ew, ol, tf) rarely have spell-oracle
            # coverage; grandfather shipped ones that are genuinely typed
            # (zipf >= 3.0) while still shedding the shipped bigram noise
            # (xc, zx, qp all sit below 3.0).
            ok2 = pos or (w in shipped and zc[w] >= 3.0)
            (keep if ok2 else drop)[w] = "2char" if ok2 else "2char-no-evidence"
            continue
        if rank is not None and rank < band:                  # band 1: conservative
            if w in typo and not (pos or w in name):
                drop[w] = f"typo→{typo[w][0]}"
            elif w in foreign and not pos:
                drop[w] = f"foreign:{foreign[w][0]}"
            elif len(w) == 3 and not (pos or w in name) and zc[w] < 3.0:
                drop[w] = "3char-noise"
            else:
                keep[w] = "band1"
        elif rank is not None:                                # band 2: aggressive
            (keep if pos else drop)[w] = "band2-oracle" if pos else "band2-no-oracle"
        elif w in shipped:                                    # carryover
            if w in typo and not (pos or w in name):
                drop[w] = f"carryover-typo→{typo[w][0]}"
            elif w in foreign and not pos:
                drop[w] = f"carryover-foreign:{foreign[w][0]}"
            else:
                keep[w] = "carryover"
        else:
            drop[w] = "extra-no-path"

    # ---------------- size cap (--limit) ----------------
    # Deterministic truncation to the N best-ranked survivors. Curated
    # keep-classes (func/allow) and the guard words are protected; everything
    # else competes on wordfreq rank (extras/carryover without a rank sort
    # last, best zipf first). Cut words land in the shipped-lost/review files
    # with the explicit `limit-cut` reason — a deliberate size cap, never a
    # silent regression.
    if limit is not None and len(keep) > limit:
        protected = {w for w, r in keep.items() if r in ("func", "allow")}
        protected |= set(cfg.must_include) & keep.keys()
        rest = sorted((w for w in keep if w not in protected),
                      key=lambda w: (rank_of.get(w, 10**9), -zc.get(w, 0.0), w))
        room = max(0, limit - len(protected))
        cut = rest[room:]
        for w in cut:
            drop[w] = "limit-cut"
            del keep[w]
        print(f"limit: capped to {len(keep):,} (protected={len(protected)}, limit-cut={len(cut)})")

    # ---------------- Stage F: guards ----------------
    failures = []
    guard_words = list(cfg.must_include) + (VALID_SEED_KEEP if is_en else [])
    for w in guard_words:
        if w not in keep:
            failures.append(f"MUST_INCLUDE lost: '{w}' ({drop.get(w, 'not a candidate')})")
    for w in block:
        if w in keep:
            failures.append(f"blocklisted word kept: '{w}' ({keep[w]})")
    if failures:
        print("\n".join(f"GUARD FAILURE: {f}" for f in failures), file=sys.stderr)
        sys.exit(1)

    # ---------------- Stage G: report + review artifacts ----------------
    reasons = Counter(keep.values())
    dreasons = Counter(v.split("→")[0].split(":")[0] for v in drop.values())
    print(f"\nKEEP {len(keep):,}  (shipped {len(shipped):,} → Δ{len(keep)-len(shipped):+,})")
    for r, n in reasons.most_common():
        print(f"  keep/{r:16s} {n:,}")
    print(f"DROP {len(drop):,}")
    for r, n in dreasons.most_common():
        print(f"  drop/{r:16s} {n:,}")
    lost = sorted(w for w in shipped if w not in keep)
    print(f"\nregression check — shipped words lost: {len(lost)}")

    # ---------------- eval coverage (held-out user-export words) ----------------
    if eval_set:
        ev_kept = {w: keep[w] for w in eval_set if w in keep}
        ev_drop = {w: drop[w] for w in eval_set if w in drop}
        ev_out = sorted(w for w in eval_set if w not in keep and w not in drop)
        old_cov = len(eval_set & shipped)
        print(f"\nEVAL — user dictionary exports ({len(eval_set)} words):")
        print(f"  old shipped dict covered : {old_cov}  ({100*old_cov/len(eval_set):.0f}%)")
        print(f"  new pipeline covers      : {len(ev_kept)}  ({100*len(ev_kept)/len(eval_set):.0f}%)")
        for r, n in Counter(ev_kept.values()).most_common():
            print(f"    kept/{r:16s} {n}")
        print(f"  dropped by pipeline      : {len(ev_drop)}")
        for r, n in Counter(v.split('→')[0].split(':')[0] for v in ev_drop.values()).most_common():
            print(f"    drop/{r:16s} {n}")
        print(f"  not reachable (beyond top-{top}, no oracle path): {len(ev_out)}")
        args.review_dir.mkdir(parents=True, exist_ok=True)
        evf = args.review_dir / ("cleverkeys-dictgen-eval.txt" if is_en
                                 else f"cleverkeys-dictgen-{lang}-eval.txt")
        with open(evf, "w", encoding="utf-8") as fp:
            fp.write(f"# Eval coverage of user dictionary-export words ({len(eval_set)}).\n"
                     f"# KEPT {len(ev_kept)} | DROPPED {len(ev_drop)} | UNREACHABLE {len(ev_out)}\n")
            for w in sorted(ev_kept):
                fp.write(f"KEPT\t{w}\t# {ev_kept[w]} zipf={zc.get(w, 0):.2f}\n")
            for w in sorted(ev_drop):
                fp.write(f"DROP\t{w}\t# {ev_drop[w]} zipf={zc.get(w, 0):.2f}\n")
            for w in ev_out:
                fp.write(f"MISS\t{w}\t# not a candidate zipf={zipf_frequency(w, lang):.2f}\n")
        print(f"  detail: {evf}")

    # EN keeps its historical artifact names (docs reference them); other
    # languages get lang-tagged files so parallel builds don't clobber.
    tag = "" if is_en else f"{lang}-"
    args.review_dir.mkdir(parents=True, exist_ok=True)
    rev = args.review_dir / f"cleverkeys-dictgen-{tag}drops-review.txt"
    with open(rev, "w", encoding="utf-8") as fp:
        fp.write(f"# Borderline drops (zipf>=2.5) — to rescue a word, add it to\n"
                 f"# scripts/dictionaries/{lang}/{lang}_allowlist.txt and re-run the builder.\n")
        for w in sorted((x for x in drop if zc.get(x, 0) >= 2.5), key=lambda x: -zc[x]):
            fp.write(f"{w}\t# {drop[w]} zipf={zc[w]:.2f}\n")
    lostf = args.review_dir / f"cleverkeys-dictgen-{tag}shipped-lost.txt"
    with open(lostf, "w", encoding="utf-8") as fp:
        fp.write("# Previously-shipped words the rebuild drops (with reason).\n")
        for w in lost:
            fp.write(f"{w}\t# {drop.get(w, '?')} zipf={zc.get(w, 0):.2f}\n")
    keepf = args.review_dir / f"cleverkeys-dictgen-{tag}keep.txt"
    with open(keepf, "w", encoding="utf-8") as fp:
        fp.write("# Full keep set with reasons — inspect band2-oracle/band1 samples here.\n")
        for w in sorted(keep):
            fp.write(f"{w}\t# {keep[w]} zipf={zc.get(w, 0):.2f}\n")
    print(f"review artifacts: {rev}  |  {lostf}  |  {keepf}")

    # ---------------- Stage H: write ----------------
    if not args.write:
        print(f"\n[report mode — no artifacts touched]  ({time.time()-t0:.0f}s)")
        return

    lang_dir.mkdir(parents=True, exist_ok=True)
    words_sorted = sorted(keep)
    src = lang_dir / f"{lang}_words.txt"
    oracle_desc = ", ".join(filter(None, [
        f"hunspell {cfg.hunspell}" if cfg.hunspell else None,
        f"aspell {cfg.aspell}" if cfg.aspell else None,
        "NLTK" if is_en else None,
        f"pyspellchecker {cfg.pyspell}" if cfg.pyspell else None,
        "AOSP LatinIME" if cfg.aosp else None,
    ])) or "none (Tier D: negatives-only)"
    with open(src, "w", encoding="utf-8") as fp:
        # No build date in the header: embedding time.strftime() made the
        # artifact byte-differ on every rebuild, defeating reproducibility.
        # The parameters (top/band/limit/keep) fully describe how the list was
        # produced; provenance/date lives in git, not the file body.
        fp.write(f"# CleverKeys {cfg.name} word list — generated by build_wordlist.py --lang {lang}\n"
                 f"# top={top} band={band} limit={limit} keep={len(words_sorted)}\n"
                 f"# Oracles: {oracle_desc},\n"
                 f"# allowlist/blocklist (scripts/dictionaries/{lang}/), contraction keys.\n")
        fp.write("\n".join(words_sorted) + "\n")
    print(f"wrote {src} ({len(words_sorted):,} words)")

    if is_en:
        # json fallback — same 128..255 scale the shipped artifact used (255 - rank//2).
        # EN-only: non-EN assets ship bin-only (adding per-lang json would change
        # the generateBinaryDictionaries task's behaviour).
        freqs = {w: get_word_frequency(w, "en", None) for w in words_sorted}
        max_freq = max(freqs.values())
        jpath = ASSETS / "en_enhanced.json"
        with open(jpath, "w", encoding="utf-8") as fp:
            fp.write("{\n")
            for i, w in enumerate(words_sorted):
                rank = frequency_to_rank(freqs[w], max_freq)
                comma = "," if i < len(words_sorted) - 1 else ""
                fp.write(f'  {json.dumps(w, ensure_ascii=False)}: {255 - rank // 2}{comma}\n')
            fp.write("}\n")
        print(f"wrote {jpath}")

    # V2 binary via the existing builder (accent map, CKDT)
    if is_en:
        outs = [ASSETS / "en_enhanced.bin", EN_DIR / "en_enhanced.bin"]
    else:
        outs = [lang_dir / f"{lang}_enhanced.bin"]
        if cfg.bundle:
            outs.append(ASSETS / f"{lang}_enhanced.bin")
    for out in outs:
        r = subprocess.run([sys.executable, str(SCRIPT_DIR / "build_dictionary.py"),
                            "--lang", lang, "--input", str(src), "--output", str(out),
                            "--use-wordfreq"], capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"build_dictionary.py failed for {out}:\n{r.stderr}")
        print(f"wrote {out}")

    # verify: CKDT magic + word-set equality with the source list (en: + json)
    for out in outs:
        data = out.read_bytes()
        magic, version = struct.unpack_from("<II", data, 0)
        assert magic == 0x54444B43 and version == 2, (out, hex(magic), version)
        wc, can_off = struct.unpack_from("<I", data, 12)[0], struct.unpack_from("<I", data, 16)[0]
        p, bin_words = can_off, set()
        for _ in range(wc):
            (ln,) = struct.unpack_from("<H", data, p); p += 2
            bin_words.add(data[p:p + ln].decode("utf-8")); p += ln + 1
        assert bin_words == set(words_sorted), \
            f"artifact mismatch {out}: bin={len(bin_words)} src={len(words_sorted)} " \
            f"(bin-src sample: {sorted(bin_words - set(words_sorted))[:5]} " \
            f"src-bin sample: {sorted(set(words_sorted) - bin_words)[:5]})"
    if is_en:
        jset = set(json.loads((ASSETS / "en_enhanced.json").read_text()).keys())
        assert jset == set(words_sorted), f"json mismatch: {len(jset)} vs {len(words_sorted)}"
    print(f"verified: CKDT v2, {len(words_sorted):,} words, bin/src identical "
          f"({'json checked, ' if is_en else ''}{time.time()-t0:.0f}s)")


if __name__ == "__main__":
    main()

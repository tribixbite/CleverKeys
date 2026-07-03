#!/usr/bin/env python3
"""
CleverKeys English word-list builder — one-pass evidence classifier.

Generates the "casual but large" English dictionary source
(`scripts/dictionaries/en/en_words.txt`) from wordfreq's frequency-ranked
candidates, classified by POSITIVE ORACLES (spellcheckers, the AOSP LatinIME
mobile-keyboard wordlist, NLTK names, contraction keys, a curated allowlist)
and NEGATIVE evidence (edit-distance-1 typo patterns with tiered zipf gaps,
foreign-language dominance, a curated blocklist).

This supersedes the subtract-then-rescue design of
`docs/specs/typo-drop-rescue-pipeline.md` (its own "Future Enhancements"
called for folding the rescue oracles into the filter — this is that fold).

Band structure (per maintainer spec, 2026-06-25):
  band 1  rank <  --band  : conservative — keep unless a negative fires with
                            no positive oracle to rescue it
  band 2  rank <  --top   : aggressive — keep ONLY with a positive oracle
                            (name-only evidence is NOT sufficient here; obscure
                            proper nouns make bad autocorrect targets)
  carryover (shipped dict): keep unless confirmed junk — no silent regressions

Resources (all offline; see scripts/data/PROVENANCE.md):
  scripts/data/aosp_en_wordlist.txt.gz            AOSP LatinIME headwords (Apache-2.0)
  scripts/dictionaries/en/en_allowlist.txt        force-keep (curated, delete-to-exclude)
  scripts/dictionaries/en/en_blocklist.txt        force-drop (overrides everything)
  src/main/assets/dictionaries/contractions_non_paired.json   functional keys
  src/main/assets/dictionaries/en_enhanced.json   shipped set (carryover basis)

Usage:
  python3 scripts/build_en_wordlist.py                 # report mode (no files touched)
  python3 scripts/build_en_wordlist.py --write         # regenerate en_words.txt + json + bin
  python3 scripts/build_en_wordlist.py --top 100000 --band 65000

AOSP snapshot refresh (documented, not automated):
  URL: https://android.googlesource.com/platform/packages/inputmethods/LatinIME/
       +/refs/heads/main/dictionaries/en_wordlist.combined.gz?format=TEXT (base64)
  Extract `word=…` headwords + flags to `headword\\tflags` lines, gzip -9, update
  PROVENANCE.md.
"""

import argparse
import gzip
import json
import re
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
ASSETS = PROJECT_ROOT / "src" / "main" / "assets" / "dictionaries"
EN_DIR = SCRIPT_DIR / "dictionaries" / "en"
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

WORD_RE = re.compile(r"[a-z][a-z'-]*")

# Words that MUST come out of the build (representative of every keep-class);
# hard build failure otherwise. Chosen to be oracle-robust, not freq-marginal.
# NOTE: deliberately contains NO words from the user's dictionary exports —
# those form the held-out --eval set and are measured, not asserted.
MUST_INCLUDE = [
    "the", "of", "and", "you",                    # core
    "gonna", "wanna", "yall", "lol", "idk",       # casual register
    "dont", "cant", "im", "theyre",               # contraction keys (FUNC)
    "json",                                       # tech register (allowlist)
    "a", "i",                                     # single letters (carryover)
]

# Valid-seed words (typo-drop-rescue spec): must never be silently lost when
# present in the candidate stream AND carrying positive evidence. These assert
# the oracle layering, not raw membership (some legitimately land in REVIEW).
VALID_SEED_KEEP = ["mgmt", "govt", "abt", "tbh", "ngl"]  # allowlisted → must be kept


def read_list_file(path: Path) -> set[str]:
    """Load a '#'-commented one-word-per-line curation file."""
    out: set[str] = set()
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        w = line.split("#", 1)[0].strip().lower()
        if w and WORD_RE.fullmatch(w):
            out.add(w)
    return out


def hunspell_accepted(tokens: list[str], transform) -> set[str]:
    """Run hunspell -G (echo ACCEPTED) over transformed tokens; return the
    lowercase originals that were accepted."""
    mapped = [transform(t) for t in tokens]
    proc = subprocess.run(
        ["hunspell", "-d", "en_US", "-G"],
        input="\n".join(mapped), capture_output=True, text=True,
    )
    accepted = {line.strip().lower() for line in proc.stdout.splitlines() if line.strip()}
    return {t for t, m in zip(tokens, mapped) if m.lower() in accepted}


def aspell_accepted(tokens: list[str], lang: str = "en_GB") -> set[str]:
    """aspell list prints MISSPELLED tokens; accepted = complement."""
    proc = subprocess.run(
        ["aspell", "-l", lang, "list"],
        input="\n".join(tokens), capture_output=True, text=True,
    )
    bad = {line.strip() for line in proc.stdout.splitlines() if line.strip()}
    return {t for t in tokens if t not in bad}


def load_aosp() -> set[str]:
    """AOSP LatinIME headwords (lowercase, alpha only, `nonword` flag excluded)."""
    path = DATA_DIR / "aosp_en_wordlist.txt.gz"
    out: set[str] = set()
    with gzip.open(path, "rt", encoding="utf-8") as fp:
        for line in fp:
            parts = line.rstrip("\n").split("\t")
            w, flags = parts[0], (parts[1] if len(parts) > 1 else "")
            if "nonword" in flags:
                continue
            lw = w.lower()
            if lw.isalpha() and _is_latin_word(lw):
                out.add(lw)
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--top", type=int, default=100_000, help="wordfreq candidate depth (default 100000)")
    ap.add_argument("--band", type=int, default=65_000, help="conservative/aggressive band boundary (default 65000)")
    ap.add_argument("--write", action="store_true", help="regenerate en_words.txt + en_enhanced.{json,bin}")
    ap.add_argument("--review-dir", type=Path, default=Path.home() / "git" / "swype",
                    help="where the annotated review artifacts are written")
    ap.add_argument("--eval", type=Path, default=EN_DIR / "en_user_export_eval.txt",
                    help="held-out eval wordlist (user dictionary exports); coverage is "
                         "reported and these words are EXCLUDED from the allowlist "
                         "force-keep path so the measurement is organic")
    args = ap.parse_args()
    t0 = time.time()

    # ---------------- Stage A: resources ----------------
    allow = read_list_file(EN_DIR / "en_allowlist.txt")
    block = read_list_file(EN_DIR / "en_blocklist.txt")
    eval_set = read_list_file(args.eval) if args.eval and args.eval.exists() else set()
    contaminated = allow & eval_set
    if contaminated:
        print(f"eval decontamination: {len(contaminated)} eval words removed from allowlist "
              f"({', '.join(sorted(contaminated)[:12])}{'…' if len(contaminated) > 12 else ''})")
        allow -= contaminated
    func = {k.lower() for k in json.loads((ASSETS / "contractions_non_paired.json").read_text()).keys()
            if k.isalpha()}
    aosp = load_aosp()
    nltk_set = {w.lower() for w in nltk_words.words()}
    name_set = {w.lower() for w in nltk_names.words()}
    pyspell_set = set(SpellChecker().word_frequency.words())
    shipped = {w.lower() for w in json.loads((ASSETS / "en_enhanced.json").read_text()).keys()}
    print(f"resources: allow={len(allow)} block={len(block)} func={len(func)} aosp={len(aosp)} "
          f"nltk={len(nltk_set)} names={len(name_set)} pyspell={len(pyspell_set)} shipped={len(shipped)}")

    # ---------------- Stage B: candidates ----------------
    ranked: list[str] = []
    seen: set[str] = set()
    for w in iter_wordlist("en"):
        if 2 <= len(w) <= 25 and w.isalpha() and _is_latin_word(w) and w not in seen:
            seen.add(w)
            ranked.append(w)
            if len(ranked) >= args.top:
                break
    rank_of = {w: i for i, w in enumerate(ranked)}
    extras = sorted((allow | shipped | func) - seen)          # beyond top-N or non-candidates
    universe = ranked + [w for w in extras if 1 <= len(w) <= 25 and _is_latin_word(w)]
    zc = {w: zipf_frequency(w, "en") for w in universe}
    print(f"candidates: ranked={len(ranked)} extras={len(extras)} universe={len(universe)}")

    # ---------------- Stage C: positive oracles ----------------
    alpha_tokens = [w for w in universe if w.isalpha()]
    hun_lower = hunspell_accepted(alpha_tokens, lambda w: w)
    hun_cap = hunspell_accepted(alpha_tokens, lambda w: w.capitalize())
    hun_upper = hunspell_accepted(alpha_tokens, lambda w: w.upper())
    gb_ok = aspell_accepted(alpha_tokens)
    check_set = hun_lower | nltk_set | pyspell_set
    british = {w for w in alpha_tokens
               for sfx, us in BRITISH_RULES.items()
               if w.endswith(sfx) and (w[: -len(sfx)] + us) in check_set}
    spell = hun_lower | gb_ok | british | (nltk_set & set(universe)) | (pyspell_set & set(universe))
    name = hun_cap | (name_set & set(universe))
    # True initialisms only: hunspell accepts ALL-CAPS of ANY dic entry ("JESSICA"),
    # so raw UPPER-acceptance would leak the whole proper-noun class into band 2.
    # A word valid ONLY in its all-caps form (ISP, NASA) is a real acronym.
    acro = hun_upper - hun_cap - hun_lower
    print(f"oracles: spell={len(spell)} name(+cap)={len(name)} acro={len(acro)} "
          f"aosp∩universe={len(aosp & set(universe))} ({time.time()-t0:.0f}s)")

    # ---------------- Stage D: negative evidence ----------------
    positive = lambda w: (w in spell or w in aosp or w in acro or w in allow or w in func)  # noqa: E731

    # Expressive-elongation exemption: "sooo"/"ahhh"/"aaah" are casual ENGLISH
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
    hf_valid = {w for w in spell if zc.get(w, 0) >= 3.5}
    at_risk = [w for w in at_risk if not is_elongation(w)]
    typo: dict[str, tuple[str, str, float]] = {}
    for lo, hi, gap in ((6, 99, 2.0), (4, 5, 2.5), (3, 3, 3.0)):
        part = [w for w in at_risk if lo <= len(w) <= hi]
        for w, match, rule, g, _ in find_misspellings(part, hf_valid, gap):
            typo[w] = (match, rule, g)
    # Foreign-language DOMINANCE (not mere presence): shared tokens (elongations,
    # anime/sports entities) score similarly in several languages — require the
    # foreign zipf to exceed English by a full point before calling it foreign.
    foreign: dict[str, tuple[str, float]] = {}
    for w in at_risk:
        best_l, best_z = "", 0.0
        for lang in FOREIGN_LANGS:
            fz = zipf_frequency(w, lang)
            if fz > best_z:
                best_l, best_z = lang, fz
        if best_z > 3.0 and best_z > zc[w] + 1.0:
            foreign[w] = (best_l, best_z)
    print(f"negatives: at_risk={len(at_risk)} typo={len(typo)} foreign={len(foreign)} ({time.time()-t0:.0f}s)")

    # ---------------- Stage E: decision ----------------
    keep: dict[str, str] = {}       # word -> keep-reason
    drop: dict[str, str] = {}       # word -> drop-reason
    for w in universe:
        if w in block or not _is_latin_word(w):
            drop[w] = "block"
            continue
        if w in func:
            keep[w] = "func"
            continue
        if w in allow:
            keep[w] = "allow"
            continue
        pos = w in spell or w in aosp or w in acro
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
        if rank is not None and rank < args.band:            # band 1: conservative
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

    # ---------------- Stage F: guards ----------------
    failures = []
    for w in MUST_INCLUDE + VALID_SEED_KEEP:
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
        print(f"  not reachable (beyond top-{args.top}, no oracle path): {len(ev_out)}")
        args.review_dir.mkdir(parents=True, exist_ok=True)
        evf = args.review_dir / "cleverkeys-dictgen-eval.txt"
        with open(evf, "w", encoding="utf-8") as fp:
            fp.write(f"# Eval coverage of user dictionary-export words ({len(eval_set)}).\n"
                     f"# KEPT {len(ev_kept)} | DROPPED {len(ev_drop)} | UNREACHABLE {len(ev_out)}\n")
            for w in sorted(ev_kept):
                fp.write(f"KEPT\t{w}\t# {ev_kept[w]} zipf={zc.get(w, 0):.2f}\n")
            for w in sorted(ev_drop):
                fp.write(f"DROP\t{w}\t# {ev_drop[w]} zipf={zc.get(w, 0):.2f}\n")
            for w in ev_out:
                fp.write(f"MISS\t{w}\t# not a candidate zipf={zipf_frequency(w, 'en'):.2f}\n")
        print(f"  detail: {evf}")

    args.review_dir.mkdir(parents=True, exist_ok=True)
    rev = args.review_dir / "cleverkeys-dictgen-drops-review.txt"
    with open(rev, "w", encoding="utf-8") as fp:
        fp.write("# Borderline drops (zipf>=2.5) — to rescue a word, add it to\n"
                 "# scripts/dictionaries/en/en_allowlist.txt and re-run the builder.\n")
        for w in sorted((x for x in drop if zc.get(x, 0) >= 2.5), key=lambda x: -zc[x]):
            fp.write(f"{w}\t# {drop[w]} zipf={zc[w]:.2f}\n")
    lostf = args.review_dir / "cleverkeys-dictgen-shipped-lost.txt"
    with open(lostf, "w", encoding="utf-8") as fp:
        fp.write("# Previously-shipped words the rebuild drops (with reason).\n")
        for w in lost:
            fp.write(f"{w}\t# {drop.get(w, '?')} zipf={zc.get(w, 0):.2f}\n")
    keepf = args.review_dir / "cleverkeys-dictgen-keep.txt"
    with open(keepf, "w", encoding="utf-8") as fp:
        fp.write("# Full keep set with reasons — inspect band2-oracle/band1 samples here.\n")
        for w in sorted(keep):
            fp.write(f"{w}\t# {keep[w]} zipf={zc.get(w, 0):.2f}\n")
    print(f"review artifacts: {rev}  |  {lostf}  |  {keepf}")

    # ---------------- Stage H: write ----------------
    if not args.write:
        print(f"\n[report mode — no artifacts touched]  ({time.time()-t0:.0f}s)")
        return

    words_sorted = sorted(keep)
    src = EN_DIR / "en_words.txt"
    with open(src, "w", encoding="utf-8") as fp:
        fp.write(f"# CleverKeys English word list — generated by build_en_wordlist.py\n"
                 f"# top={args.top} band={args.band} keep={len(words_sorted)} "
                 f"date={time.strftime('%Y-%m-%d')}\n"
                 f"# Oracles: hunspell en_US, aspell en_GB, NLTK, pyspellchecker, AOSP LatinIME,\n"
                 f"# allowlist/blocklist (scripts/dictionaries/en/), contraction keys.\n")
        fp.write("\n".join(words_sorted) + "\n")
    print(f"wrote {src} ({len(words_sorted):,} words)")

    # json fallback — same 128..255 scale the shipped artifact used (255 - rank//2)
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
    for out in (ASSETS / "en_enhanced.bin", EN_DIR / "en_enhanced.bin"):
        r = subprocess.run([sys.executable, str(SCRIPT_DIR / "build_dictionary.py"),
                            "--lang", "en", "--input", str(src), "--output", str(out),
                            "--use-wordfreq"], capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"build_dictionary.py failed for {out}:\n{r.stderr}")
        print(f"wrote {out}")

    # verify: CKDT magic + word-set equality with json
    import struct
    data = (ASSETS / "en_enhanced.bin").read_bytes()
    magic, version = struct.unpack_from("<II", data, 0)
    assert magic == 0x54444B43 and version == 2, (hex(magic), version)
    wc, can_off = struct.unpack_from("<I", data, 12)[0], struct.unpack_from("<I", data, 16)[0]
    p, bin_words = can_off, set()
    for _ in range(wc):
        (ln,) = struct.unpack_from("<H", data, p); p += 2
        bin_words.add(data[p:p + ln].decode("utf-8")); p += ln + 1
    jset = set(json.loads(jpath.read_text()).keys())
    assert bin_words == jset == set(words_sorted), \
        f"artifact mismatch: bin={len(bin_words)} json={len(jset)} src={len(words_sorted)}"
    print(f"verified: CKDT v2, {wc:,} words, json/bin/src identical  ({time.time()-t0:.0f}s)")


if __name__ == "__main__":
    main()

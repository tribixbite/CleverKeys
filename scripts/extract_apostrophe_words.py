#!/usr/bin/env python3
"""
Build the per-language contraction DISPLAY files from the AnySoftKeyboard wordlists.

A contraction file is a display overlay, not a dictionary: the key is the
apostrophe-free surface a CleverKeys engine can produce, the value is what the
user is shown.  Apostrophe is not a swipe key, so every dictionary stores
contractions as apostrophe-free ALIASES and these files put the apostrophe back.

TWO FILES PER LANGUAGE, and the split is the whole product rule
(src/main/kotlin/tribixbite/cleverkeys/swipe/ContractionOverlay.kt):

  contractions_<lang>.json       {alias: display}
      REPLACE mode.  The key has no reading of its own ("cest", "jai",
      "gehts"), so the display form takes its slot in the suggestion slate.

  contraction_pairs_<lang>.json  {word: [variant, ...]}
      APPEND mode.  The key IS a word of the language ("lune", "danse",
      "lago", "signora"), so it keeps its slot and the elision is offered as
      an extra candidate.  Both spellings stay reachable - a user who swiped
      "lune" may have meant the moon or "l'une", and the keyboard may not
      decide for them.

Two filters run before that split, and both exist because the pre-2026-08-17
output was ~99% unusable:

  1. REACHABILITY.  A mapping whose key no engine can emit can never fire.  It
     is dead weight in the APK and it hides the real coverage behind a large
     entry count.  27,256 of the 27,494 French mappings and 22,355 of the
     22,474 Italian ones were dead: the extractor lifted every apostrophe token
     out of the ASK wordlists without ever checking the key against
     CleverKeys' own dictionary.

  2. CLASSIFICATION.  Before the split existed, the bucket was inferred at
     RUNTIME from the key's frequency rank (ContractionOverlay's
     REAL_WORD_ORDINAL_MAX = 1200).  Rank works for English by luck - its
     aliases "dont"/"im"/"cant" genuinely are not words - and fails for French
     and Italian, where common words rank past the threshold and were
     DESTROYED: "lune" (rank 2,055) presented as "l'une", "danse" -> "d'anse",
     "lion" -> "l'ion", "signora" -> "s'ignora", "duomo" -> "d'uomo".  The
     discriminator is corpus attestation of the bare form, never rank, so it is
     resolved HERE and shipped as the file an entry lives in.

Attestation oracle (see classify_mappings for the thresholds and their
justification): hunspell's morphological lexicon for the language, cross-checked
against the ASK wordlist and a wordfreq floor.  All three must agree before a
key is called a word.

Guarded by:
  src/test/kotlin/tribixbite/cleverkeys/swipe/BundledContractionDataTest.kt
  src/test/kotlin/tribixbite/cleverkeys/swipe/SwipeContractionLanguageIsolationTest.kt

Requirements:
  - the AnySoftKeyboard checkout (ASK_BASE below);
  - `pip install wordfreq`;
  - the `hunspell` CLI plus a dictionary per classified language.  Termux ships
    fr_FR (pkg install hunspell-fr) and en_US (hunspell-en-us); it_IT and de_DE
    are not packaged - fetch them from the LibreOffice dictionary repo into
    ~/.local/share/hunspell (see HUNSPELL_SOURCES) or point DICPATH elsewhere.

Usage:
    python3 extract_apostrophe_words.py                # every configured language
    python3 extract_apostrophe_words.py --lang fr,it   # only these
    python3 extract_apostrophe_words.py --lang fr --dry-run
"""

import argparse
import gzip
import json
import os
import re
import struct
import subprocess
import sys
import unicodedata
from pathlib import Path

# Base path for ASK dictionaries
ASK_BASE = Path("/data/data/com.termux/files/home/git/swype/AnySoftKeyboard/addons/languages")

# Language dictionary mappings
# Bundled languages
LANG_DICT_PATHS = {
    "fr": ASK_BASE / "french/pack/dictionary/fr_wordlist.combined.gz",
    "it": ASK_BASE / "italian/pack/dictionary/it_wordlist.combined.gz",
    "de": ASK_BASE / "german/pack/dictionary/de_wordlist.combined.gz",
    "es": ASK_BASE / "spain/pack/dictionary/es_wordlist.combined.gz",
    "pt": ASK_BASE / "brazilian/pack/dictionary/pt_BR_wordlist.combined.gz",
    "en": ASK_BASE / "english/pack/dictionary/en_wordlist.combined.gz",
    # Downloadable language packs
    "nl": ASK_BASE / "dutch/pack/dictionary/nl_wordlist.combined.gz",
    # Note: id, ms, sw, tl don't have ASK dictionaries and rarely use apostrophes
}

# Output directory
OUTPUT_DIR = Path(__file__).parent.parent / "src/main/assets/dictionaries"

# Regex to parse ASK word format: word=c'est,f=159,flags=,originalFreq=159
WORD_PATTERN = re.compile(r"word=([^,]+),f=(\d+)")

# ---------------------------------------------------------------------------
# Per-language pipeline configuration.
#
#   lexicon   the bundled CleverKeys dictionary a decoded word must come from.
#             None -> the language ships no bundled dictionary (nl is an
#             import-only pack), so reachability cannot be measured and the
#             extraction is written through unchanged.
#   hunspell  the hunspell dictionary used for word attestation.  None -> no
#             classification: the file stays REPLACE-only.
#   classify  whether to split the reachable mappings into the two files.
#
# en is deliberately reachability-filtered but NOT classified.  Its aliases come
# from the bundled ENGLISH base (contractions.bin / contractions_non_paired.json
# / contraction_pairings.json), which ContractionManager loads FIRST and which
# shadows every key contractions_en.json repeats - all 14 English keys whose
# bare form is a real word ("cant", "shell", "shed", "wed", "lets", "wont",
# "dart", "whore", "readers", "editors", "sharia", "hows", "whats", "secy") are
# already owned by that base, 6 of them already as PAIRED entries.  Moving them
# inside this file would be a no-op; fixing English means fixing the base, which
# is a separate change with a much larger blast radius (it drives the typing
# pipeline, not just swipe).
LANGUAGE_PIPELINE = {
    "fr": {"lexicon": "fr_enhanced.bin", "hunspell": "fr_FR", "classify": True},
    "it": {"lexicon": "it_enhanced.bin", "hunspell": "it_IT", "classify": True},
    "de": {"lexicon": "de_enhanced.bin", "hunspell": "de_DE", "classify": True},
    "en": {"lexicon": "en_enhanced.json", "hunspell": None, "classify": False},
    "es": {"lexicon": "es_enhanced.bin", "hunspell": None, "classify": False},
    "pt": {"lexicon": "pt_enhanced.bin", "hunspell": None, "classify": False},
    "nl": {"lexicon": None, "hunspell": None, "classify": False},
}

# Where the non-packaged hunspell dictionaries come from, for whoever re-runs
# this.  Drop the .aff/.dic pair into ~/.local/share/hunspell (de_DE_frami.*
# renamed to de_DE.*), or set DICPATH.
HUNSPELL_SOURCES = {
    "it_IT": "https://raw.githubusercontent.com/LibreOffice/dictionaries/master/it_IT/it_IT.{ext}",
    "de_DE": "https://raw.githubusercontent.com/LibreOffice/dictionaries/master/de/de_DE_frami.{ext}",
}

DICPATH = os.pathsep.join([
    str(Path.home() / ".local/share/hunspell"),
    os.environ.get("PREFIX", "/usr") + "/share/hunspell",
    "/usr/share/hunspell",
])

# ---------------------------------------------------------------------------
# Hand-curated mappings this extractor CANNOT produce, merged on top of the
# extraction so that re-running the script is non-destructive.
#
# Why they are needed: the extractor's only source is the ASK wordlists, and it
# drops every token containing "'s" for non-Dutch languages (the possessive
# filter below). German's genuine apostrophe contraction is exactly that shape —
# the elided "e" of the clitic "es" (Duden D 16: "geht's", "gibt's", "hab's") —
# and the ASK German wordlist holds only 18 apostrophe words anyway, all proper
# nouns. So German would forever come out with just the four "d'" proper-noun
# elisions the filter happens to let through.
#
# Curation rules applied (2026-08-16), all three required:
#   1. The apostrophe form is the standard spelling of a clitic-"es" elision.
#   2. The apostrophe-free KEY is a word in CleverKeys' own bundled dictionary
#      (src/main/assets/dictionaries/de_enhanced.bin, projected onto a-z) —
#      otherwise the swipe beam can never emit it and the mapping is dead data.
#      54 of 76 hand-enumerated candidates were dropped by this rule.
#   3. The key has no competing reading in German, confirmed against wordfreq
#      ('de'): rejected "wies" (past tense of weisen), "versuchs"/"halts"
#      (genitives), "wars"/"wills" (proper nouns) — ContractionOverlay REPLACES
#      a key ranked past REAL_WORD_ORDINAL_MAX, so a homograph would be lost.
#
# Guarded by src/test/kotlin/tribixbite/cleverkeys/swipe/BundledContractionDataTest.kt.
CURATED_CONTRACTIONS = {
    # French hyphenated compounds (2026-08-20, docs/proposals/2026-08-20-hyphen-compound-
    # contractions.md Phase A). Hand-curated for the same reason the German clitics are: a
    # BULK hyphen extraction produces 16,687 keys of which 73 are native French words with no
    # rank protection (minuit<-mi-nuit, parla<-par-la, nonne<-non-ne), and the recovery
    # classifier itself misfires on anglicisms and 1990-reform spellings (weekend, email,
    # entretemps would classify REPLACE and be destroyed in-slot). That is the `lune` damage a
    # third time, so the extraction is deliberately NOT widened.
    #
    # Every entry below was derived, not assumed: hunspell fr_FR rejects the key, ASK
    # frequency of the key is 0, the key is absent from the fr lexicon surfaces, and it fits
    # the 32-frame CTC budget (max 13).
    #
    # Phase A's values are accent-free, which is why it shipped without touching any test.
    # Phase B's carry accents (peut-etre -> peut-être), so they could only land alongside a
    # deliberate relaxation of BundledContractionDataTest's "differs by apostrophes/hyphens
    # only" pin to fold accents on both sides. That pin's strictness WAS the thing refusing
    # nonne->non-né, so the compensation is an exact-content pin over this table plus explicit
    # negative assertions that the landmine keys are absent. Read that test before adding a
    # row here: an accented value can no longer be caught by the projection invariant alone.
    #
    # `rendezvous -> rendez-vous` was held out of Phase A because it is an English lexicon word
    # (@18993) and a German one, and the tap path merged every active language's REPLACE keys
    # into one map, so an fr+en user typing English "rendezvous" had it rewritten. That guard now
    # exists: `ContractionCollisionDemotion` moves any REPLACE key that is a real word of another
    # ACTIVE language into the PAIRED bucket, so an fr+en user gets both spellings and an
    # fr-only user still gets the REPLACE. Regenerate the collision sidecars after changing this
    # table: `python3 scripts/build_contraction_collisions.py`.
    "fr": {
        "questce": "qu'est-ce",
        "estce": "est-ce",
        "nestce": "n'est-ce",
        "celuici": "celui-ci",
        "celleci": "celle-ci",
        "ceuxci": "ceux-ci",
        "cellesci": "celles-ci",
        "audessus": "au-dessus",
        "audessous": "au-dessous",
        "cidessus": "ci-dessus",
        "cidessous": "ci-dessous",
        "quelquesuns": "quelques-uns",
        "quelquesunes": "quelques-unes",
        "grandsparents": "grands-parents",
        "avanthier": "avant-hier",
        "demiheure": "demi-heure",
        # ── Phase B: accent-carrying values (28) ──────────────────────────────────────
        "peutetre": "peut-être",
        "cestadire": "c'est-à-dire",
        "audela": "au-delà",
        "labas": "là-bas",
        "lahaut": "là-haut",
        "ladedans": "là-dedans",
        "ladessus": "là-dessus",
        "apresmidi": "après-midi",
        "celuila": "celui-là",
        "cellela": "celle-là",
        "ceuxla": "ceux-là",
        "cellesla": "celles-là",
        "moimeme": "moi-même",
        "toimeme": "toi-même",
        "luimeme": "lui-même",
        "ellememe": "elle-même",
        "soimeme": "soi-même",
        "nousmemes": "nous-mêmes",
        "vousmeme": "vous-même",
        "vousmemes": "vous-mêmes",
        "euxmemes": "eux-mêmes",
        "ellesmemes": "elles-mêmes",
        "grandmere": "grand-mère",
        "grandpere": "grand-père",
        "bellemere": "belle-mère",
        "beaupere": "beau-père",
        "beaufrere": "beau-frère",
        "visavis": "vis-à-vis",
        "rendezvous": "rendez-vous",
        # `belle-sœur` is excluded: `œ` is not a-z projectable, so the key could never be
        # injected and the mapping would be permanently dead.
    },
    "de": {
        "fands": "fand's",
        "gabs": "gab's",
        "gehts": "geht's",
        "gibts": "gibt's",
        "gings": "ging's",
        "habs": "hab's",
        "hats": "hat's",
        "ichs": "ich's",
        "ists": "ist's",
        "kanns": "kann's",
        "mans": "man's",
        "obs": "ob's",
        "reichts": "reicht's",
        "sags": "sag's",
        "weils": "weil's",
        "wenns": "wenn's",
        "wirds": "wird's",
    },
    # es/pt/sv deliberately have NO curated entries: Spanish ("al", "del") and
    # Portuguese ("do", "da", "no", "na", "pelo") amalgams are written solid, and
    # modern Swedish writes its reduced forms solid too ("stan", not "sta'n").
    # An empty file is the linguistically correct answer for those languages.
}

# Classification overrides for the handful of keys the automatic oracles get
# wrong.  Each one needs a reason; keep this table small enough to read.
FORCED_APPEND = {
    # "allego" is the standard 1sg of allegare ("io allego un file").  hunspell
    # it_IT attests it; the ASK frequency list simply does not carry it (it is a
    # truncated top-N list), and "all'ego" is a near-nonexistent sequence.
    "it": {"allego"},
    # "entretemps" is the 1990-reform spelling of "entre-temps" and a real fr
    # lexicon word (@~30334) — but hunspell fr_FR REJECTS reform spellings, so if
    # any future extraction ever maps it, the classifier would misfire it into
    # REPLACE and destroy the reform spelling in-slot.  No mapping ships today
    # (BundledContractionDataTest pins it absent from both files); this row is
    # defence in depth for the day one does, per the HANDOFF §1 landmine note.
    "fr": {"entretemps"},
}
FORCED_REPLACE = {
    # German capitalises its nouns, so the attestation probe has to try the
    # capitalised form too - which makes "Ichs" (plural of the noun "das Ich")
    # attest the surface "ichs".  Lowercase "ichs" is not German: its only
    # reading is the clitic elision "ich's" (Duden D 16), which is why the
    # curation above added it in the first place.
    "de": {"ichs"},
}

# ---------------------------------------------------------------------------
# French subject-pronoun VERB INVERSIONS (2026-09-01, HANDOFF §1 deferral).
#
# `est-elle`, `a-t-on`, `va-t-il` are legitimate French interrogative surfaces a
# swiper cannot type (hyphen is not a swipe key), so they need the same overlay
# treatment as the elisions. But their apostrophe-free keys collide with native
# words in ways no classifier predicts — `estelle` is a French lexicon word (the
# given name, past the rank guard) and `aton` is ASK-attested (the deity Aton) —
# so the WHOLE family is emitted PAIRED, never REPLACE: the decoded word keeps
# its slot and the inversion is appended alongside (`classify_mappings` receives
# the family as forced-append input).
#
# The family is CLOSED, not extracted: a bulk hyphen extraction is the rejected
# alternative (16,687 keys, 73 native-word landmines — see CURATED_CONTRACTIONS'
# comment). Closure = a curated verb-form table, person-keyed so ungrammatical
# combinations (`fauton`, `est-je`) cannot be generated, crossed with the subject
# pronouns and the Grevisse epenthesis rule (vowel-final 3sg forms take `-t-`:
# `a-t-il`, `va-t-on`, `aime-t-elle`; t/d-final forms link directly: `est-il`,
# `prend-elle`). Notes on the person rows:
#   - 1sg inversion is formal register and only idiomatic for a handful of verbs;
#     pouvoir's is the suppletive `puis-je` (`peux-je` is ungrammatical, so `peux`
#     appears in the 2sg row only).
#   - `3sg_il` holds the impersonal verbs (falloir, pleuvoir, importer), which
#     invert with `il` alone.
#   - The futur/conditionnel/imparfait rows exist only for the top auxiliaries
#     and modals, where inversion is genuinely frequent (`sera-t-il`,
#     `pourrait-on`, `fallait-il`).
#
# Every form is checked against the bundled fr lexicon at generation time
# (french_inversions), so a typo in this table dies there instead of shipping.
# Guarded by BundledContractionDataTest's inversion test: exact-value samples,
# the closed-family shape pin over every hyphenated PAIRED value, the agreement
# pins, and the REPLACE prohibition.
FRENCH_INVERSION_VERBS = {
    "1sg": ["suis", "ai", "puis", "vais", "dois", "sais", "fais", "dis", "vois",
            "crois"],
    "2sg": ["es", "as", "vas", "peux", "dois", "veux", "sais", "fais", "dis",
            "vois", "crois", "viens", "prends", "comprends", "entends",
            "attends", "connais", "penses", "aimes", "parles", "trouves"],
    "3sg": ["est", "a", "va", "peut", "doit", "veut", "sait", "fait", "dit",
            "vient", "voit", "croit", "prend", "comprend", "entend", "attend",
            "connaît", "pense", "aime", "parle", "trouve", "semble", "existe",
            "reste", "arrive", "suffit",
            # futur / conditionnel / imparfait of the auxiliaries and modals
            "sera", "serait", "était", "aura", "aurait", "avait",
            "ira", "irait", "allait", "pourra", "pourrait", "pouvait",
            "devra", "devrait", "devait", "voudra", "voudrait", "voulait",
            "saura", "saurait", "savait", "fera", "ferait", "faisait",
            "dira", "dirait", "disait", "viendra", "viendrait", "venait"],
    "3sg_il": ["faut", "faudra", "faudrait", "fallait", "pleut", "importe"],
    "1pl": ["sommes", "avons", "allons", "pouvons", "devons", "voulons",
            "savons", "faisons", "disons", "voyons", "prenons", "venons"],
    "2pl": ["êtes", "avez", "allez", "pouvez", "devez", "voulez", "savez",
            "faites", "dites", "voyez", "croyez", "prenez", "comprenez",
            "entendez", "attendez", "connaissez", "pensez", "aimez", "parlez",
            "trouvez", "venez"],
    "3pl": ["sont", "ont", "vont", "peuvent", "doivent", "veulent", "savent",
            "font", "disent", "voient", "croient", "prennent", "viennent",
            "pensent", "aiment", "parlent", "trouvent"],
}

INVERSION_PRONOUNS = {
    "1sg": ["je"],
    "2sg": ["tu"],
    "3sg": ["il", "elle", "on"],
    "3sg_il": ["il"],
    "1pl": ["nous"],
    "2pl": ["vous"],
    "3pl": ["ils", "elles"],
}


def extract_apostrophe_words(dict_path: Path, lang: str = "") -> tuple:
    """
    Extract words containing apostrophes from an ASK dictionary.

    Returns (contractions, lexicon):
      contractions  dict mapping: without_apostrophe -> with_apostrophe
      lexicon       dict mapping: every ASK word (lowercased) -> its f score,
                    reused as the second attestation oracle in
                    classify_mappings (this is the one wordlist in the
                    pipeline that keeps its apostrophes, so "c'est" and "cest"
                    are distinct entries in it).

    Only includes real words (not proper nouns starting with O', McDonald's, etc.)

    Language-specific handling:
    - Dutch (nl): Include 's plurals (auto's, foto's) which are NOT possessives
    - Other languages: Filter out 's endings as possessives
    """
    contractions = {}
    lexicon = {}

    if not dict_path.exists():
        print(f"  Warning: {dict_path} not found", file=sys.stderr)
        return contractions, lexicon

    with gzip.open(dict_path, 'rt', encoding='utf-8') as f:
        for line in f:
            match = WORD_PATTERN.search(line)
            if not match:
                continue

            word = match.group(1)
            freq = int(match.group(2))
            lexicon.setdefault(word.lower(), freq)

            # Skip words without apostrophes
            if "'" not in word:
                continue

            # Skip proper nouns (O'Brien, McDonald's, etc.)
            # These typically start with capital and have apostrophe after O or s
            if word[0].isupper() and (
                word.startswith("O'") or  # Irish names
                word.startswith("D'") or  # D'Arcy, etc.
                (word.endswith("'s") and lang != "nl") or    # Possessives (but not Dutch plurals)
                "'" in word and word.split("'")[0][0].isupper()  # Other proper nouns
            ):
                continue

            # Skip brand names and special cases
            # For Dutch, don't skip 's endings (they're plurals, not possessives)
            skip_patterns = ["McDonald", "Rock'n'Roll", "rock'n'roll"]
            if lang != "nl":
                skip_patterns.append("'s")  # Only skip 's for non-Dutch
            if any(p in word for p in skip_patterns):
                continue

            # Create the key (without apostrophe) and value (with apostrophe)
            key = word.replace("'", "").lower()
            value = word.lower()

            # Skip very short keys (single letter after removing apostrophe)
            if len(key) < 2:
                continue

            # Only keep the most frequent version for each key
            if key not in contractions:
                contractions[key] = value
            # If we already have this key, prefer the version with proper casing
            # (handled by lowercase conversion above)

    return contractions, lexicon


# ---------------------------------------------------------------------------
# The bundled CleverKeys lexicon: what a decoded word can actually BE.
#
# Ports (does not import) the readers the app uses:
#   CkdtDictionaryReader  - CKDT v2 canonical section
#   CtcAzProjection       - the a-z projection policy
#   CtcLexiconMerge       - the frequency ordinals ContractionOverlay guards on
# ---------------------------------------------------------------------------

CKDT_MAGIC = 0x54444B43  # "CKDT" little-endian
CKDT_HEADER_SIZE = 48
JOINERS = frozenset("'’-")


def project_az(word: str):
    """CtcAzProjection.project: NFD, drop combining marks, drop joiners, require a-z."""
    if not word:
        return None
    decomposed = unicodedata.normalize("NFD", word.lower())
    out = []
    for ch in decomposed:
        if unicodedata.category(ch) == "Mn":
            continue
        if ch in JOINERS:
            continue
        if not ("a" <= ch <= "z"):
            return None
        out.append(ch)
    return "".join(out) if out else None


def read_ckdt(path: Path) -> list:
    """CKDT v2 canonical section -> [(word, rank)], rank ascending (stable)."""
    blob = path.read_bytes()
    if len(blob) < CKDT_HEADER_SIZE:
        raise ValueError(f"{path}: too short for a CKDT header")
    magic, version = struct.unpack_from("<II", blob, 0)
    if magic != CKDT_MAGIC or version != 2:
        raise ValueError(f"{path}: not a CKDT v2 dictionary (magic={magic:#x}, version={version})")
    word_count, canonical_offset = struct.unpack_from("<II", blob, 12)
    pos = canonical_offset
    records = []
    for _ in range(word_count):
        (length,) = struct.unpack_from("<H", blob, pos)
        pos += 2
        records.append((blob[pos:pos + length].decode("utf-8"), blob[pos + length]))
        pos += length + 1
    return sorted(records, key=lambda r: r[1])  # stable: file order breaks ties


def load_lexicon(name: str) -> dict:
    """The bundled dictionary as {canonical word: frequency}, in engine order."""
    path = OUTPUT_DIR / name
    if not path.exists():
        raise FileNotFoundError(f"bundled lexicon {path} is missing")
    if path.suffix == ".json":
        # en_enhanced.json is already on the AOSP-like byte-score scale.
        return {w: float(f) for w, f in json.loads(path.read_text(encoding="utf-8")).items()}
    # CKDT stores a uint8 rank; the engines read it as max(1, 255 - rank).
    return {word: max(1.0, 255.0 - rank) for word, rank in read_ckdt(path)}


def emitted_forms(lexicon: dict) -> set:
    """
    Every string a shipped engine can put on the suggestion bar for `lexicon`.

    CTC walks the a-z projection and then restores the canonical form BEFORE the
    contraction overlay runs, so what the overlay sees is `display[surface] or
    surface` - never the bare surface of an accented word (swiping "dira" in
    Italian presents "dira" with the grave accent, so a "dira" mapping is dead).
    The geometric engine and the typing predictor carry the canonical dictionary
    word itself, including forms that lost their a-z surface to a collision.
    """
    freqs, display = {}, {}
    for word, freq in lexicon.items():
        surface = project_az(word)
        if surface is None:
            continue
        existing = freqs.get(surface)
        if existing is not None and freq <= existing:
            continue
        freqs[surface] = freq
        if word == surface:
            display.pop(surface, None)
        else:
            display[surface] = word
    out = {display.get(s, s).lower() for s in freqs}
    out.update(w.lower() for w in lexicon)
    return out


def is_ctc_injectable(key: str) -> bool:
    """
    True when `key` can be INJECTED into the CTC lexicon trie as its own surface.

    Mirrors `CtcContractionKeys.isInjectable` (swipe/ctc/CtcContractionKeys.kt).  The
    CTC alphabet is a-z, so a key made only of a-z characters can be inserted verbatim
    and the beam can then walk it; a key carrying anything else (the hyphen in en
    "high-falutin", the accent in fr "cest-a-dire") has no trie path and stays dead no
    matter what the lexicon holds.

    This is why `emitted_forms` alone UNDERSTATES reachability: it asks only what the
    bundled lexicon can emit.  A productive elision such as fr "dabaissement" is not a
    dictionary word and never will be, but it IS swipeable once injected - which is
    exactly what the French user needs, French being full of elisions.
    """
    if not key:
        return False
    lowered = key.lower()
    return all("a" <= ch <= "z" for ch in lowered)


def frequency_ordinals(lexicon: dict) -> dict:
    """CtcLexiconMerge.ordinals: lowercase word -> rank, frequency descending."""
    items = list(lexicon.items())
    order = sorted(range(len(items)), key=lambda i: (-items[i][1], i))
    ordinals = {}
    for rank, index in enumerate(order):
        key = items[index][0].lower()
        if key not in ordinals:
            ordinals[key] = rank
    return ordinals


def french_inversions(lexicon: dict) -> dict:
    """The closed verb-inversion family over forms attested in the bundled fr lexicon.

    Returns {a-z key: hyphenated display value} — e.g. {"aton": "a-t-on"}.  Every entry
    is destined for the PAIRED bucket (build_language passes the key set to
    classify_mappings as forced-append), because an inversion key can be a native word
    (`estelle`) and REPLACE would destroy it in-slot.

    Attestation: each verb form must project onto a surface of the bundled CleverKeys
    lexicon, so a typo in FRENCH_INVERSION_VERBS dies here instead of shipping.  The one
    principled exception is single-letter forms (`a`, 3sg of avoir): the bundled lexicon
    stores NO single-letter words at all (verified: its shortest entries are 2 chars), so
    absence there is a storage artifact, not linguistic evidence — and `a-t-il`/`a-t-on`
    are the most frequent members of the whole family.
    """
    surfaces = {project_az(word) for word in lexicon} - {None}
    out = {}
    dropped = []
    for person, verbs in FRENCH_INVERSION_VERBS.items():
        for verb in verbs:
            surface = project_az(verb)
            if len(verb) >= 2 and surface not in surfaces:
                dropped.append(verb)
                continue
            # Grevisse: vowel-final 3sg forms take the epenthetic -t- before the
            # vowel-initial pronouns (a-t-il, va-t-on, aime-t-elle).  French 3sg forms
            # end in -t/-d (linking directly) or a vowel; -c finals (vainc) are omitted
            # from the table outright.
            epenthetic = person in ("3sg", "3sg_il") and verb[-1] in "ea"
            for pronoun in INVERSION_PRONOUNS[person]:
                value = f"{verb}-t-{pronoun}" if epenthetic else f"{verb}-{pronoun}"
                key = project_az(value)
                if key is None:
                    raise SystemExit(f"inversion {value!r} has no a-z key - unshippable")
                # CTC frame budget: length + one blank per adjacent duplicate pair must
                # fit the 32-frame emission head (CtcDecodableLength).
                frames = 1 + sum(1 + (key[i] == key[i - 1]) for i in range(1, len(key)))
                if frames > 32:
                    raise SystemExit(f"inversion {value!r} needs {frames} CTC frames > 32")
                prior = out.get(key)
                if prior is not None and prior != value:
                    raise SystemExit(
                        f"intra-family key collision: {key!r} <- {prior!r} and {value!r}"
                    )
                out[key] = value
    if dropped:
        print(f"  inversions: dropped unattested verb forms: {', '.join(sorted(set(dropped)))}")
    return out


#: Languages with a generated inversion family; the generator receives the bundled
#: lexicon and returns {key: value} mappings that classify_mappings must force APPEND.
INVERSION_GENERATORS = {
    "fr": french_inversions,
}


# ContractionOverlay.REAL_WORD_ORDINAL_MAX - the runtime rank guard.
REAL_WORD_ORDINAL_MAX = 1200

# wordfreq floor for the APPEND side: zipf 2.0 is one occurrence per 10 million
# words.  Below that the "word" is a spellchecker artefact (an abbreviation such
# as en "sec'y"/"cont'd", or a lemma nothing writes), and keeping it ahead of a
# live elision costs a slate slot for nothing.
ZIPF_FLOOR = 2.0

# ASK f floor for the APPEND side.  The ASK lists demote hand-blacklisted junk to
# f=1 while keeping originalFreq (fr "los", "love", "lose" - English words that
# leaked into the French list), so f>=2 means "the list really vouches for it".
ASK_FREQ_FLOOR = 2


def hunspell_attested(dictionary: str, words) -> set:
    """
    The subset of `words` hunspell's lexicon for `dictionary` accepts.

    Tried lowercase AND capitalised: hunspell accepts a capitalised form of a
    lowercase entry, but never a lowercase form of a capitalised (proper-noun)
    entry, so the second probe is what recognises "Louvre"/"Laura"/"Lecce" as
    words of the language rather than elision debris.
    """
    words = list(words)
    if not words:
        return set()
    probes = words + [w.capitalize() for w in words]
    env = dict(os.environ, DICPATH=DICPATH)
    result = subprocess.run(
        ["hunspell", "-d", dictionary, "-l"],
        input="\n".join(probes) + "\n",
        capture_output=True, text=True, env=env,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"hunspell -d {dictionary} failed ({result.returncode}): {result.stderr.strip()}\n"
            f"  DICPATH={DICPATH}\n"
            f"  source: {HUNSPELL_SOURCES.get(dictionary, 'pkg install hunspell-<lang>')}"
        )
    rejected = set(result.stdout.split())
    return {w for w in words if w not in rejected or w.capitalize() not in rejected}


def classify_mappings(lang: str, mappings: dict, lexicon: dict, ask_lexicon: dict,
                      forced_append_extra: frozenset = frozenset()) -> tuple:
    """
    Split reachable `mappings` into (replace, pairs).

    APPEND (the key is a word of the language, so it must survive) when EITHER

      a) the key ranks inside REAL_WORD_ORDINAL_MAX - the runtime guard already
         appended those, so this preserves the shipped behaviour exactly; or

      b) all three attestation oracles agree that the bare form is a word:
           1. hunspell's morphological lexicon accepts it (lowercase or
              capitalised - see hunspell_attested);
           2. the ASK wordlist carries it with f >= ASK_FREQ_FLOOR;
           3. wordfreq's corpus gives it zipf >= ZIPF_FLOOR.

    REPLACE otherwise, plus the FORCED_* overrides.

    Why three oracles and not wordfreq alone, which the task of "is this a word"
    would seem to call for: wordfreq CANNOT see the difference, because its
    corpora contain plenty of text written without apostrophes.  Measured on the
    shipped data, fr zipf("cest") = 4.13 against zipf("lune") = 4.61 - the alias
    scores nearly as high as the word - and the ratio test fails the same way,
    since wordfreq scores the phrase "l'une" at 6.81 (it is l' + une, a
    productive elision) so lune/l'une = 10^-2.20 lands right on top of
    cest/c'est = 10^-2.57.  hunspell's lexicon is not corpus-derived and rejects
    "cest"/"jai"/"lhomme"/"quest" while accepting "lune"/"danse"/"lavez", so it
    is the primary; the ASK list (the one apostrophe-preserving wordlist here)
    catches hunspell's obscure entries - fr "sil" is a pigment name and would
    otherwise outrank "s'il"; and the wordfreq floor drops what neither corpus
    actually writes.
    """
    from wordfreq import zipf_frequency

    spec = LANGUAGE_PIPELINE[lang]
    ordinals = frequency_ordinals(lexicon)
    # forced_append_extra carries the generated inversion family (build_language), whose
    # membership is computed against the lexicon rather than curated as a literal set.
    forced_append = FORCED_APPEND.get(lang, set()) | set(forced_append_extra)
    forced_replace = FORCED_REPLACE.get(lang, set())

    guarded = {k for k in mappings if ordinals.get(k, REAL_WORD_ORDINAL_MAX) < REAL_WORD_ORDINAL_MAX}
    candidates = sorted(set(mappings) - guarded)
    attested = hunspell_attested(spec["hunspell"], candidates)

    append, replace = {}, {}
    for key in sorted(mappings):
        is_word = (
            key in guarded
            or key in forced_append
            or (
                key in attested
                and ask_lexicon.get(key, 0) >= ASK_FREQ_FLOOR
                and zipf_frequency(key, lang) >= ZIPF_FLOOR
            )
        )
        if is_word and key not in forced_replace:
            append[key] = [mappings[key]]
        else:
            replace[key] = mappings[key]
    return replace, append


def load_shipped(lang: str) -> tuple:
    """
    The mappings already in the assets, as (replace, append).

    They are an INPUT, not only an output: the ASK wordlists are not the only
    thing that ever put an entry in these files (contractions_en.json ships 119
    mappings where a fresh extraction yields 101), so a regeneration that used
    the extraction alone would silently delete hand-added data.  Feeding the
    shipped files back in makes a re-run non-destructive - it can add, and it
    can drop what the dictionary cannot reach, but it cannot lose an entry.
    """
    replace_path = OUTPUT_DIR / f"contractions_{lang}.json"
    pairs_path = OUTPUT_DIR / f"contraction_pairs_{lang}.json"
    replace = json.loads(replace_path.read_text(encoding="utf-8")) if replace_path.exists() else {}
    pairs = json.loads(pairs_path.read_text(encoding="utf-8")) if pairs_path.exists() else {}
    flat = {k.lower(): v.lower() for k, v in replace.items()}
    for base, variants in pairs.items():
        flat[base.lower()] = variants[0].lower()
    return flat, {k.lower(): [v.lower() for v in vs] for k, vs in pairs.items()}


def build_language(lang: str, dict_path: Path) -> dict:
    """Run the whole pipeline for one language and return a report."""
    spec = LANGUAGE_PIPELINE.get(lang)
    if spec is None:
        raise KeyError(f"no pipeline configured for '{lang}' - add it to LANGUAGE_PIPELINE")

    contractions, ask_lexicon = extract_apostrophe_words(dict_path, lang)
    extracted = len(contractions)

    # Precedence, weakest first: extraction < already-shipped < hand-curated.
    shipped, shipped_pairs = load_shipped(lang)
    contractions.update(shipped)

    curated = CURATED_CONTRACTIONS.get(lang, {})
    if curated:
        # Curated entries WIN: they were verified against the app's own dictionary,
        # while the extraction cannot see it (see CURATED_CONTRACTIONS' comment).
        contractions.update(curated)

    dead = 0
    injected = 0
    inversions = {}
    if spec["lexicon"]:
        lexicon = load_lexicon(spec["lexicon"])

        # Generated verb-inversion family (fr): computed against the loaded lexicon and
        # merged at curated precedence.  A key colliding with the hand-curated table would
        # mean two authorities disagree about one surface - that is an error to resolve,
        # never a silent overwrite.
        generator = INVERSION_GENERATORS.get(lang)
        if generator is not None:
            inversions = generator(lexicon)
            clash = set(inversions) & set(curated)
            if clash:
                raise SystemExit(f"{lang}: inversion keys collide with curated table: {sorted(clash)}")
            for key, value in inversions.items():
                prior = contractions.get(key)
                if prior is not None and prior != value:
                    print(f"  inversions: {key!r} overrides {prior!r} with {value!r}")
            contractions.update(inversions)

        emitted = emitted_forms(lexicon)
        # An entry is REACHABLE if either the bundled lexicon can already emit its key,
        # or the key is a pure a-z string that the CTC adapter injects into the lexicon
        # trie (CtcEngineAdapter.lexiconFor -> CtcContractionKeys.inject).  Before that
        # injection existed the second clause was empty, which is why 27,256 fr + 22,355
        # it productive elisions looked dead and were trimmed - they are not.
        live = {
            k: v for k, v in contractions.items()
            if k in emitted or is_ctc_injectable(k)
        }
        injected = sum(1 for k in live if k not in emitted)
        dead = len(contractions) - len(live)
        contractions = live
    else:
        lexicon = {}

    if spec["classify"]:
        replace, append = classify_mappings(
            lang, contractions, lexicon, ask_lexicon,
            forced_append_extra=frozenset(inversions),
        )
    else:
        # No attestation oracle for this language: every mapping stays REPLACE and
        # any already-shipped APPEND file is carried through untouched.
        append = {k: v for k, v in shipped_pairs.items() if k in contractions}
        replace = {k: v for k, v in contractions.items() if k not in append}

    return {
        "extracted": extracted,
        "curated": len(curated),
        "dead": dead,
        "injected": injected,
        "replace": dict(sorted(replace.items())),
        "append": dict(sorted(append.items())),
    }


def write_language(lang: str, report: dict, dry_run: bool) -> None:
    """Write both files for one language (the APPEND file only when non-empty)."""
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    replace_path = OUTPUT_DIR / f"contractions_{lang}.json"
    pairs_path = OUTPUT_DIR / f"contraction_pairs_{lang}.json"

    if dry_run:
        print(f"  [dry-run] {replace_path.name}: {len(report['replace'])} mappings")
        print(f"  [dry-run] {pairs_path.name}: {len(report['append'])} mappings")
        return

    with open(replace_path, "w", encoding="utf-8") as f:
        # No trailing newline: matches every contraction asset already in the tree.
        json.dump(report["replace"], f, indent=2, ensure_ascii=False)
    print(f"  {replace_path}: {len(report['replace'])} REPLACE mappings")

    if report["append"]:
        with open(pairs_path, "w", encoding="utf-8") as f:
            json.dump(report["append"], f, indent=2, ensure_ascii=False)
        print(f"  {pairs_path}: {len(report['append'])} APPEND mappings")
    elif pairs_path.exists():
        pairs_path.unlink()
        print(f"  {pairs_path}: removed (no APPEND mappings for {lang})")


def print_sample(lang: str, report: dict, sample_size: int = 12) -> None:
    """Print sample mappings for verification."""
    for bucket in ("replace", "append"):
        items = sorted(report[bucket].items(), key=lambda kv: len(kv[0]))[:sample_size]
        if not items:
            continue
        print(f"  {bucket.upper()} sample:")
        for key, value in items:
            shown = value if isinstance(value, str) else ", ".join(value)
            print(f"    {key} -> {shown}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument(
        "--lang",
        help="comma-separated language codes to rebuild (default: all configured)",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="report what would be written without touching the assets",
    )
    args = parser.parse_args()

    languages = list(LANG_DICT_PATHS)
    if args.lang:
        languages = [code.strip() for code in args.lang.split(",") if code.strip()]
        unknown = [code for code in languages if code not in LANG_DICT_PATHS]
        if unknown:
            parser.error(f"unknown language(s): {', '.join(unknown)}")

    print("Extracting apostrophe words from AnySoftKeyboard dictionaries...")
    print()

    reports = {}
    for lang in languages:
        print(f"Processing {lang}...")
        report = build_language(lang, LANG_DICT_PATHS[lang])
        reports[lang] = report
        print(
            f"  extracted {report['extracted']}"
            f" + {report['curated']} curated"
            f" - {report['dead']} unreachable"
            f" ({report['injected']} reachable only via CTC injection)"
            f" = {len(report['replace'])} REPLACE + {len(report['append'])} APPEND"
        )
        write_language(lang, report, args.dry_run)
        print_sample(lang, report)
        print()

    print("=" * 60)
    print("SUMMARY")
    print("=" * 60)
    for lang, report in reports.items():
        print(
            f"  {lang}: {len(report['replace'])} replace, {len(report['append'])} append"
            f" ({report['dead']} unreachable dropped)"
        )
    print()
    print(f"Output directory: {OUTPUT_DIR}")


if __name__ == "__main__":
    main()

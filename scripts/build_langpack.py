#!/usr/bin/env python3
"""
Build Language Pack ZIP for CleverKeys.

Creates a language pack ZIP file containing:
- manifest.json: metadata (language code, name, version)
- dictionary.bin: V2 binary dictionary with accent normalization
- unigrams.txt: word frequency list for language detection
- contractions.json: apostrophe word mappings (optional, for languages that use them)
- prefix_boost.bin: Aho-Corasick trie for prefix boosting (optional, for non-English)
- model.onnx: CTC swipe encoder for a non-Latin script (optional, --model)
- NOTICE.txt: attribution, licence and change note for the third-party word data

Attribution travels INSIDE every pack (2026-09-26 data-licensing audit,
docs/audit/2026-09-26-data-licensing-audit.md). The word data is CC BY-SA
(wordfreq, FrequencyWords, Swwiki), and a bare extraction without its
attribution does not satisfy BY-SA -- wordfreq's own FAQ says so -- so a pack
downloaded on its own must still carry its credits. The manifest gains three
keys, appended after the existing ones:

    "license":     "GPL-3.0-only"                (the pack as distributed)
    "attribution": "<one-line credit + upstream licences + change note>"
    "source":      "<upstream URL(s), space-separated>"

and NOTICE.txt carries the full text. Sources come from the DATA_SOURCES /
PACK_SOURCES tables below, overridable with --data-source, --license,
--attribution and --source. The importer ignores both additions (it reads the
manifest with optX and copies only the members it knows), so older app versions
import the new packs unchanged.

Repacking (--repack): rewrites an EXISTING pack with the new metadata while
keeping every payload member (dictionary.bin, unigrams.txt, contractions.json,
prefix_boost.bin, model.onnx) byte-identical. This is how the published packs
were brought into compliance, because a from-inputs rebuild cannot reproduce
them: the raw inputs are untracked (scripts/en_opensubtitles_*.txt,
swahili_freq.ods), the AOSP oracle snapshots were removed from the tree
(scripts/data/PROVENANCE.md), and the prefix_boost.bin members came from
src/main/assets/prefix_boosts/, deleted with the neural engine (ADR-011). A
content rebuild is a dictionary change that needs its own evaluation; a
licensing fix must not smuggle one in.

The six per-script CTC encoders (ru/el/uk/bg/mk/he) ship IN their packs rather
than in the APK: every one of those languages is langpack-sourced, so the model
could only ever run for a user who had imported the pack anyway. When --model is
given the manifest gains

    "model": {"file": "model.onnx", "sha256": "<64 hex>"}

which the importer checks the bytes against. That is an integrity check only --
the app additionally refuses to load any pack model that is not byte-identical
to a hash compiled into the APK (CtcScriptSupport.modelSha256), so a NEW model
is an app change as well as a pack change, by design.

Usage:
    python3 build_langpack.py --lang fr --name "French" --input french_words.txt --output langpack-fr.zip
    python3 build_langpack.py --lang de --name "German" --input german_words.txt --output langpack-de.zip --use-wordfreq
    python3 build_langpack.py --lang ru --name "Russian" --dict dictionary.bin --unigrams unigrams.txt \
        --model ru_synth_v3_ch80_fp16w.onnx --version 2 --output langpack-ru.zip
    # Add/refresh licence metadata on an existing pack, payload bytes unchanged:
    python3 build_langpack.py --repack dictionaries/langpack-de.zip --output dictionaries/langpack-de.zip
    # Custom pack from your own data -- attribution is mandatory:
    python3 build_langpack.py --lang xx --name "MyLang" --dict my.bin --output langpack-xx.zip \
        --license "CC-BY-4.0" --attribution "My corpus, (c) Me, CC BY 4.0" --source https://example.org

Prerequisites:
    - Run build_dictionary.py first to generate dictionary.bin
    - Run generate_unigrams.py to generate unigrams.txt
    OR use --auto to generate all files from a single word list

Requirements:
    pip install wordfreq  # Optional, for frequency enrichment

License: Apache-2.0
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent

# Licence of a pack AS DISTRIBUTED by CleverKeys. Every upstream licence below is
# one-way compatible into GPLv3 (CC BY-SA 4.0 per creativecommons.org/compatible-licenses,
# Apache-2.0 per the FSF), so the adapted pack is GPL-3.0-only like the app.
PACK_LICENSE = "GPL-3.0-only"
PACK_LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

# The change note CC BY-SA 4.0 §3(a)(1)(B) and the CC "ShareAlike compatibility: GPLv3"
# conditions require ("indicate if you modified the material").
CHANGE_NOTE = "Modified: converted to a CleverKeys frequency list."

NOTICE_MEMBER = "NOTICE.txt"


@dataclass(frozen=True)
class DataSource:
    """One upstream the word data of a pack is derived from.

    `credit` is the one-line form that goes into manifest "attribution"; `notice`
    is the full block written into NOTICE.txt (author, link, licence, required
    third-party credits). Text only -- no dates or paths -- so NOTICE.txt is a pure
    function of the source keys and the pack stays reproducible.
    """
    key: str
    credit: str
    url: str
    notice: str


DATA_SOURCES: dict[str, DataSource] = {
    "wordfreq": DataSource(
        key="wordfreq",
        credit=("Word frequencies: wordfreq by Robyn Speer et al. (CC BY-SA 4.0), including "
                "SUBTLEX (Brysbaert et al.; freely available data), OpenSubtitles "
                "(opensubtitles.org, via OPUS) and Google Books Ngram "
                "(books.google.com/ngrams) data"),
        url="https://github.com/rspeer/wordfreq",
        notice="""\
wordfreq -- Robyn Speer et al.
  https://github.com/rspeer/wordfreq
  Data licence: Creative Commons Attribution-ShareAlike 4.0 (CC BY-SA 4.0)
  https://creativecommons.org/licenses/by-sa/4.0/
  (wordfreq's code is Apache-2.0; no wordfreq code is included in this pack.)

  wordfreq's frequencies combine, among others:
  - SUBTLEX word lists (SUBTLEX-US, -UK, -CH, -DE, -NL) created by Marc
    Brysbaert et al., http://crr.ugent.be/programs-data/subtitle-frequencies
    -- SUBTLEX is freely available data; its authors must be credited.
  - OPUS OpenSubtitles 2018 (http://opus.nlpl.eu/OpenSubtitles.php), whose data
    originates from OpenSubtitles.org (http://www.opensubtitles.org/).
    P. Lison and J. Tiedemann (2016). OpenSubtitles2016: Extracting Large
    Parallel Corpora from Movie and TV Subtitles. LREC 2016.
  - Google Books Ngram Viewer data, http://books.google.com/ngrams
  - Wikipedia (http://www.wikipedia.org), the Leeds Internet Corpus and
    ParaCrawl, under Creative Commons licences.
""",
    ),
    "aosp-latinime": DataSource(
        key="aosp-latinime",
        credit=("inclusion oracle: AOSP LatinIME word list, The Android Open Source "
                "Project (Apache-2.0)"),
        url="https://android.googlesource.com/platform/packages/inputmethods/LatinIME/",
        notice="""\
AOSP LatinIME dictionaries -- The Android Open Source Project
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/
  Licence: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0
  Used at build time as an inclusion oracle: a word's presence in the AOSP
  list helped decide whether it was kept. No AOSP frequencies are included.
""",
    ),
    "frequencywords": DataSource(
        key="frequencywords",
        credit=("Word frequencies: FrequencyWords 2018 by Hermit Dave (content CC BY-SA 4.0), "
                "derived from OpenSubtitles.org subtitles via the OPUS OpenSubtitles corpus "
                "(Lison & Tiedemann 2016)"),
        url="https://github.com/hermitdave/FrequencyWords",
        notice="""\
FrequencyWords (2018 release) -- Hermit Dave
  https://github.com/hermitdave/FrequencyWords
  Licence: "MIT License for code. CC-by-sa-4.0 for content." -- the word list
  used here is content: Creative Commons Attribution-ShareAlike 4.0
  https://creativecommons.org/licenses/by-sa/4.0/

  FrequencyWords is computed from the OPUS OpenSubtitles corpus
  (http://opus.nlpl.eu/OpenSubtitles.php), whose data originates from
  OpenSubtitles.org (http://www.opensubtitles.org/).
  P. Lison and J. Tiedemann (2016). OpenSubtitles2016: Extracting Large
  Parallel Corpora from Movie and TV Subtitles. In Proceedings of the 10th
  International Conference on Language Resources and Evaluation (LREC 2016).
""",
    ),
    "swwiki": DataSource(
        key="swwiki",
        credit=("Word frequencies: Swwiki Swahili Wikipedia word list by Kevin Donnelly, from "
                "Swahili Wikipedia text (CC BY-SA 3.0, adapted under CC BY-SA 4.0)"),
        url="https://kevindonnelly.org.uk/swahili/swwiki/",
        notice="""\
Swwiki -- Kevin Donnelly
  https://kevindonnelly.org.uk/swahili/swwiki/
  A word-frequency list built from the text of Swahili Wikipedia
  (https://sw.wikipedia.org/, Wikipedia contributors).
  Licence: Creative Commons Attribution-ShareAlike 3.0 Unported
  https://creativecommons.org/licenses/by-sa/3.0/

  Licence chain for this pack: CC BY-SA 3.0 §4(b) permits distributing an
  adaptation under a later version of the licence with the same licence
  elements, i.e. CC BY-SA 4.0; CC BY-SA 4.0 adaptations may in turn be
  licensed under GPLv3 (https://creativecommons.org/compatible-licenses/).
  CC BY-SA 3.0 itself is NOT GPL-compatible; the chain runs through 4.0.
""",
    ),
}

# Which sources each PUBLISHED pack (scripts/dictionaries/langpack-<id>.zip) was built from.
# Keyed by pack id (the file stem after "langpack-"), so English corpus variants are
# distinct rows. Derived per pack from its git history (2026-09-26 audit):
#   - es fr de it nl pt sv ru el tr he were (re)built on 2026-07-20 / 2026-09-10 by the
#     evidence classifier (build_wordlist.py), whose LANG_CONFIG gives each of them an AOSP
#     LatinIME oracle; uk bg mk id ms tl ran it oracle-less (no AOSP dictionary upstream).
#   - langpack-en.zip and langpack-en-wordfreq.zip date from 2026-01-06, BEFORE the AOSP
#     oracle existed; they are plain wordfreq lists.
#     TODO: a rebuild of langpack-en.zip through build_all_languages.py goes through
#     build_wordlist.py, which DOES use aosp_en -- change the "en" row to
#     ("wordfreq", "aosp-latinime") in the same commit as that rebuild.
#   - sw: wordfreq has no Swahili; the list comes from Swwiki (parse_swahili_ods.py).
#   - en-opensubtitles*: hermitdave/FrequencyWords 2018 en lists.
# langpack-en-norvig-50k.zip was REMOVED (no redistribution grant: Google Web 1T / LDC2006T13).
_WORDFREQ_AOSP = ("wordfreq", "aosp-latinime")
PACK_SOURCES: dict[str, tuple[str, ...]] = {
    "en": ("wordfreq",),
    "en-wordfreq": ("wordfreq",),
    "en-opensubtitles": ("frequencywords",),
    "en-opensubtitles-50k": ("frequencywords",),
    **{code: _WORDFREQ_AOSP for code in
       ("es", "fr", "de", "it", "nl", "pt", "sv", "ru", "el", "tr", "he")},
    **{code: ("wordfreq",) for code in ("uk", "bg", "mk", "id", "ms", "tl")},
    "sw": ("swwiki",),
}


@dataclass(frozen=True)
class Attribution:
    """The licence metadata written into manifest.json and NOTICE.txt."""
    license: str
    attribution: str
    source: str
    notice_blocks: tuple[str, ...]


def pack_id_for(output: Path, lang: str) -> str:
    """Pack id = stem after "langpack-" (e.g. "en-opensubtitles-50k"); falls back to lang."""
    stem = output.stem
    return stem[len("langpack-"):] if stem.startswith("langpack-") else lang


def resolve_attribution(pack_id: str, source_keys: list[str] | None,
                        license_override: str | None, attribution_override: str | None,
                        source_override: str | None) -> Attribution | None:
    """Work out a pack's licence metadata.

    Precedence: explicit --data-source keys, else the PACK_SOURCES row for the pack id.
    Free-text --license/--attribution/--source override the derived strings (and are the
    only route for a custom pack built from the user's own data). Returns None when nothing
    identifies the data's origin -- the caller refuses to build then, so no pack can ship
    without its credits.
    """
    keys = list(source_keys) if source_keys else list(PACK_SOURCES.get(pack_id, ()))
    sources = [DATA_SOURCES[k] for k in keys]
    if not sources and not attribution_override:
        return None
    attribution = attribution_override or (
        "; ".join(s.credit for s in sources) + f". {CHANGE_NOTE} Full text: {NOTICE_MEMBER}")
    source = source_override or " ".join(s.url for s in sources)
    blocks = tuple(s.notice for s in sources)
    if not blocks:
        # Custom pack: the user's own attribution line is the whole third-party notice.
        blocks = (attribution_override + "\n",)
    return Attribution(
        license=license_override or PACK_LICENSE,
        attribution=attribution,
        source=source,
        notice_blocks=blocks,
    )


def render_notice(code: str, name: str, pack_file: str, attr: Attribution,
                  has_model: bool) -> str:
    """NOTICE.txt text. Deterministic: depends only on its arguments."""
    rule = "=" * 72
    parts = [
        f"CleverKeys language pack: {name} ({code}) -- {pack_file}",
        "https://github.com/tribixbite/CleverKeys",
        "",
        f"Pack licence: {attr.license}",
    ]
    if attr.license == PACK_LICENSE:
        parts.append(f"  {PACK_LICENSE_URL}")
    parts += [
        "",
        "The word list in this pack (dictionary.bin, unigrams.txt and, where present,",
        "prefix_boost.bin, which is computed from the same list) is an adaptation of",
        "the third-party data credited below.",
        "",
        rule,
        "Third-party data",
        rule,
    ]
    for block in attr.notice_blocks:
        parts += ["", block.rstrip("\n")]
    parts += [
        "",
        rule,
        "Changes",
        rule,
        CHANGE_NOTE,
        "Words were selected, filtered and ranked, and their frequencies quantised",
        "into CleverKeys' binary dictionary format; the selection and ranking differ",
        "from the upstream lists.",
    ]
    if attr.license == PACK_LICENSE:
        parts += [
            "",
            rule,
            "Licence compatibility",
            rule,
            "CC BY-SA 4.0 material may be adapted under GPLv3",
            "(https://creativecommons.org/compatible-licenses/; conditions:",
            "https://wiki.creativecommons.org/wiki/ShareAlike_compatibility:_GPLv3).",
            "Apache-2.0 material is likewise one-way compatible with GPLv3. This pack is",
            "therefore distributed under GPL-3.0-only, with the attribution, licence",
            "notices, links and change note above preserved as those licences require.",
        ]
    parts += [
        "",
        "contractions.json (where present) and manifest.json are CleverKeys work,",
        "GPL-3.0-only.",
    ]
    if has_model:
        parts += [
            "model.onnx is a CleverKeys CTC swipe encoder, original work trained from",
            "scratch on synthesized gestures (generator fitted to the MIT-licensed FUTO",
            "swipe corpus and How-We-Swipe dataset), GPL-3.0-only. See the NOTICE file",
            "of the CleverKeys repository for its full provenance.",
        ]
    return "\n".join(parts) + "\n"

# ZIP epoch for reproducible output. zipfile pulls the filesystem mtime when you
# call ZipFile.write(path), so the same inputs produce byte-differing archives on
# every rebuild. We instead pin every entry's timestamp to the DOS/ZIP epoch
# (the earliest value the ZIP format can represent) so the archive is a pure
# function of its file contents. (year, month, day, hour, minute, second)
ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)

# Mirrors CtcPackModel.MAX_PACK_MODEL_BYTES. Enforced here too so a pack that the
# app would refuse to import can never be built and published in the first place.
MAX_MODEL_BYTES = 8 * 1024 * 1024


def _add_deterministic(zf: zipfile.ZipFile, arcname: str, data: bytes) -> None:
    """Add one entry with a fixed timestamp so rebuilds are byte-identical.

    Uses writestr() with a hand-built ZipInfo (fixed date_time, explicit
    DEFLATE, standard 0o644 external attrs) rather than write(path), which would
    embed the source file's mtime. Callers must add entries in a deterministic
    (sorted) order for the whole archive to be reproducible.
    """
    info = zipfile.ZipInfo(filename=arcname, date_time=ZIP_EPOCH)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16  # -rw-r--r-- regular file permissions
    zf.writestr(info, data)


def run_build_dictionary(lang: str, input_file: Path, output_file: Path, use_wordfreq: bool) -> bool:
    """Run build_dictionary.py to generate dictionary.bin."""
    cmd = [
        sys.executable,
        str(SCRIPT_DIR / "build_dictionary.py"),
        "--lang", lang,
        "--input", str(input_file),
        "--output", str(output_file)
    ]
    if use_wordfreq:
        cmd.append("--use-wordfreq")

    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Error building dictionary:\n{result.stderr}")
        return False
    print(result.stdout)
    return True


def run_generate_unigrams(lang: str, output_file: Path, count: int = 5000) -> bool:
    """Run generate_unigrams.py to create unigrams.txt."""
    cmd = [
        sys.executable,
        str(SCRIPT_DIR / "generate_unigrams.py"),
        "--lang", lang,
        "--output", str(output_file),
        "--top-n", str(count)
    ]

    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Error generating unigrams:\n{result.stderr}")
        return False
    print(result.stdout)
    return True


def run_compute_prefix_boosts(lang: str, output_dir: Path) -> bool:
    """Run compute_prefix_boosts.py to generate prefix boost trie.

    No longer called from build_langpack (see the comment at its prefix-boost step):
    generating a dead asset as a side effect of building a pack recreated a tree that
    ADR-011 deleted. Kept because compute_prefix_boosts.py still exists and this is the
    documented way to drive it, should the boosts ever acquire a consumer again.
    """
    if lang == "en":
        return False  # English doesn't need prefix boosts

    cmd = [
        sys.executable,
        str(SCRIPT_DIR / "compute_prefix_boosts.py"),
        "--langs", lang,
        "--threshold", "1.5"
    ]

    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Error generating prefix boosts:\n{result.stderr}")
        return False
    print(result.stdout)
    return True


def count_words_in_dictionary(dict_file: Path) -> int:
    """Read word count from V2 dictionary header."""
    try:
        with open(dict_file, 'rb') as f:
            # Skip magic (4), version (4), lang (4)
            f.seek(12)
            # Read word count (4 bytes, little-endian)
            word_count_bytes = f.read(4)
            return int.from_bytes(word_count_bytes, byteorder='little')
    except Exception as e:
        print(f"Warning: Could not read word count: {e}")
        return 0


def create_manifest(lang: str, name: str, version: int, author: str, word_count: int,
                    has_prefix_boost: bool = False, model_sha256: str | None = None,
                    attr: Attribution | None = None) -> dict:
    """Create manifest.json content.

    Key order is fixed (json.dump preserves insertion order) so the serialized
    manifest -- and therefore the whole archive -- stays a pure function of its
    inputs. "model" follows the original six keys; the licence keys come last.
    """
    manifest = {
        "code": lang,
        "name": name,
        "version": version,
        "author": author,
        "wordCount": word_count,
        "hasPrefixBoost": has_prefix_boost
    }
    if model_sha256:
        manifest["model"] = {"file": "model.onnx", "sha256": model_sha256}
    if attr is not None:
        apply_attribution(manifest, attr)
    return manifest


def apply_attribution(manifest: dict, attr: Attribution) -> None:
    """Set the three licence keys, appended after whatever the manifest already holds.

    Popping first keeps them LAST even when refreshing a manifest that already has them,
    so repacking an already-repacked pack is byte-stable.
    """
    for key in ("license", "attribution", "source"):
        manifest.pop(key, None)
    manifest["license"] = attr.license
    manifest["attribution"] = attr.attribution
    manifest["source"] = attr.source


def serialize_manifest(manifest: dict) -> bytes:
    """The one manifest serialisation every pack uses (2-space indent, UTF-8, trailing NL)."""
    return (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def write_pack(output: Path, entries: dict[str, bytes]) -> None:
    """Write the archive deterministically: fixed per-entry timestamp + sorted order.

    Written to a sibling temp file and renamed over `output`, so --repack can target the
    pack it is reading without truncating it mid-read.
    """
    tmp = output.with_name(output.name + ".tmp")
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for arcname in sorted(entries):
            _add_deterministic(zf, arcname, entries[arcname])
    os.replace(tmp, output)


def repack_langpack(source_zip: Path, output: Path, source_keys: list[str] | None,
                    license_override: str | None, attribution_override: str | None,
                    source_override: str | None) -> bool:
    """Rewrite an existing pack with licence metadata; payload members stay byte-identical.

    Only manifest.json (licence keys set, everything else preserved in its order) and
    NOTICE.txt (regenerated) change. Idempotent: repacking a repacked pack reproduces it.
    """
    with zipfile.ZipFile(source_zip) as zf:
        entries = {info.filename: zf.read(info) for info in zf.infolist() if not info.is_dir()}
    if "manifest.json" not in entries or "dictionary.bin" not in entries:
        print(f"Error: {source_zip} is not a language pack (needs manifest.json + dictionary.bin)")
        return False
    manifest = json.loads(entries["manifest.json"].decode("utf-8"))
    pack_id = pack_id_for(output, manifest["code"])
    attr = resolve_attribution(pack_id, source_keys, license_override,
                               attribution_override, source_override)
    if attr is None:
        print(f"Error: no data source known for pack id '{pack_id}'. Add it to PACK_SOURCES "
              f"or pass --data-source / --attribution.")
        return False
    apply_attribution(manifest, attr)
    entries["manifest.json"] = serialize_manifest(manifest)
    entries[NOTICE_MEMBER] = render_notice(
        manifest["code"], manifest["name"], output.name, attr,
        has_model="model.onnx" in entries).encode("utf-8")
    write_pack(output, entries)
    print(f"Repacked {source_zip} -> {output} ({pack_id}: {', '.join(source_keys or PACK_SOURCES.get(pack_id, ()))})")
    print(f"  sha256 {hashlib.sha256(output.read_bytes()).hexdigest()}")
    return True


def build_langpack(
    lang: str,
    name: str,
    output: Path,
    input_file: Path = None,
    dict_file: Path = None,
    unigrams_file: Path = None,
    use_wordfreq: bool = False,
    version: int = 1,
    author: str = "",
    model_file: Path = None,
    attr: Attribution = None,
):
    """Build a language pack ZIP file. `attr` is required: no pack ships without credits."""
    if attr is None:
        print("Error: no attribution resolved for this pack (see --data-source/--attribution)")
        return False

    with tempfile.TemporaryDirectory() as temp_dir:
        temp_path = Path(temp_dir)

        # Determine what files we need to generate vs use directly
        final_dict = dict_file
        final_unigrams = unigrams_file

        # If input file provided, generate dictionary and unigrams
        if input_file:
            if not dict_file:
                final_dict = temp_path / "dictionary.bin"
                print(f"\n=== Building dictionary from {input_file} ===")
                if not run_build_dictionary(lang, input_file, final_dict, use_wordfreq):
                    return False

            if not unigrams_file:
                final_unigrams = temp_path / "unigrams.txt"
                print(f"\n=== Generating unigrams for {lang} ===")
                if not run_generate_unigrams(lang, final_unigrams):
                    print("Warning: Could not generate unigrams (wordfreq may not be installed)")
                    final_unigrams = None

        # Validate required files
        if not final_dict or not final_dict.exists():
            print("Error: No dictionary.bin available. Provide --dict or --input")
            return False

        # Get word count
        word_count = count_words_in_dictionary(final_dict)
        print(f"\nDictionary contains {word_count} words")

        # Include the prefix-boost trie only if the asset is already there. This used to
        # GENERATE one on the spot for any non-English pack, which is now actively harmful:
        # prefix boosts have had no consumer since the neural beam search was deleted
        # (ADR-011, 2026-08-18) and src/main/assets/prefix_boosts/ was removed with it, so
        # the auto-generation step's only remaining effect was to recreate a deleted tree as
        # a side effect of building an unrelated pack. Regenerating a trie is
        # compute_prefix_boosts.py's own job, run deliberately.
        prefix_boost_file = SCRIPT_DIR.parent / f"src/main/assets/prefix_boosts/{lang}.bin"
        has_prefix_boost = prefix_boost_file.exists() and lang != "en"

        # Read and hash the CTC encoder, if this pack carries one. The manifest records
        # the hash so the importer can tell a corrupt download from a good one; the app
        # separately pins its own copy of the same value, which is what actually decides
        # whether the graph may be loaded.
        model_bytes = None
        model_sha256 = None
        if model_file:
            if not model_file.exists():
                print(f"Error: --model {model_file} does not exist")
                return False
            model_bytes = model_file.read_bytes()
            if len(model_bytes) > MAX_MODEL_BYTES:
                print(f"Error: --model is {len(model_bytes)} B, over the "
                      f"{MAX_MODEL_BYTES} B limit the importer enforces")
                return False
            model_sha256 = hashlib.sha256(model_bytes).hexdigest()

        # Create manifest
        manifest = create_manifest(lang, name, version, author, word_count, has_prefix_boost,
                                   model_sha256, attr)

        # Look for contractions file in assets
        contractions_file = SCRIPT_DIR.parent / f"src/main/assets/dictionaries/contractions_{lang}.json"

        # Collect entries as (arcname -> bytes) so the archive is a pure
        # function of file CONTENTS, independent of source mtimes/paths.
        entries: dict[str, bytes] = {
            "manifest.json": serialize_manifest(manifest),
            "dictionary.bin": final_dict.read_bytes(),
            NOTICE_MEMBER: render_notice(lang, name, output.name, attr,
                                         has_model=model_file is not None).encode("utf-8"),
        }
        if final_unigrams and final_unigrams.exists():
            entries["unigrams.txt"] = final_unigrams.read_bytes()
            print(f"  + unigrams.txt")
        if contractions_file.exists():
            # Check if contractions file has content (not just "{}")
            with open(contractions_file, 'r') as cf:
                content = cf.read().strip()
                if content and content != "{}":
                    entries["contractions.json"] = contractions_file.read_bytes()
                    print(f"  + contractions.json")
                else:
                    print(f"  (no contractions - language doesn't use apostrophes)")
        # Include prefix boost trie (for non-English languages)
        if has_prefix_boost and prefix_boost_file.exists():
            entries["prefix_boost.bin"] = prefix_boost_file.read_bytes()
            boost_size = prefix_boost_file.stat().st_size / 1024
            print(f"  + prefix_boost.bin ({boost_size:.1f} KB)")
        if model_bytes is not None:
            entries["model.onnx"] = model_bytes
            print(f"  + model.onnx ({len(model_bytes) / 1024:.1f} KB, sha256 {model_sha256})")

        # Create ZIP deterministically: fixed per-entry timestamp + sorted order.
        print(f"\n=== Creating {output} ===")
        write_pack(output, entries)

        # Print summary
        zip_size = output.stat().st_size
        print(f"\nLanguage Pack Created:")
        print(f"  File: {output}")
        print(f"  Size: {zip_size / 1024:.1f} KB")
        print(f"  Language: {name} ({lang})")
        print(f"  Words: {word_count}")
        print(f"  Prefix boost: {'Yes' if has_prefix_boost else 'No'}")
        print(f"  CTC model: {model_sha256 if model_sha256 else 'No'}")
        print(f"\nTo install: Copy to your device and import in CleverKeys Settings > Multi-Language")

        return True


def main():
    parser = argparse.ArgumentParser(
        description='Build Language Pack ZIP for CleverKeys',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument('--lang', help='Language code (e.g., fr, de, pt); required unless --repack')
    parser.add_argument('--name', help='Language display name (e.g., "French"); required unless --repack')
    parser.add_argument('--output', required=True, type=Path, help='Output ZIP file path')
    parser.add_argument('--input', type=Path, help='Input word list (generates dict + unigrams)')
    parser.add_argument('--dict', type=Path, help='Pre-built dictionary.bin file')
    parser.add_argument('--unigrams', type=Path, help='Pre-built unigrams.txt file')
    parser.add_argument('--use-wordfreq', action='store_true',
                        help='Use wordfreq library for frequency enrichment')
    parser.add_argument('--version', type=int, default=1, help='Pack version number')
    parser.add_argument('--author', default='', help='Pack author name')
    parser.add_argument('--model', type=Path,
                        help='CTC swipe encoder (.onnx) to ship as model.onnx. Only meaningful '
                             'for a script the app has a CtcScriptSupport row for -- the app '
                             'refuses any pack model that is not byte-identical to its pin.')
    parser.add_argument('--repack', type=Path, metavar='ZIP',
                        help='Rewrite an existing pack with licence metadata + NOTICE.txt, keeping '
                             'every payload member byte-identical (use when the raw inputs are '
                             'not available). --output may name the same file.')
    parser.add_argument('--data-source', action='append', choices=sorted(DATA_SOURCES),
                        help='Upstream the word data came from (repeatable). Default: the '
                             'PACK_SOURCES row for the pack id (output stem after "langpack-").')
    parser.add_argument('--license', dest='license_override',
                        help=f'Override the manifest "license" (default {PACK_LICENSE})')
    parser.add_argument('--attribution', help='Override the manifest "attribution" line; '
                                              'mandatory for a pack built from your own data')
    parser.add_argument('--source', dest='source_override',
                        help='Override the manifest "source" URL(s), space-separated')

    args = parser.parse_args()

    if args.repack:
        ok = repack_langpack(args.repack, args.output, args.data_source, args.license_override,
                             args.attribution, args.source_override)
        sys.exit(0 if ok else 1)

    # Validate arguments
    if not args.lang or not args.name:
        parser.error("--lang and --name are required (unless --repack)")
    if not args.input and not args.dict:
        parser.error("Must provide either --input (word list) or --dict (pre-built dictionary)")

    # --use-wordfreq on a language with no table row implies wordfreq is the source.
    source_keys = args.data_source
    pack_id = pack_id_for(args.output, args.lang)
    if not source_keys and pack_id not in PACK_SOURCES and args.use_wordfreq:
        source_keys = ["wordfreq"]
    attr = resolve_attribution(pack_id, source_keys, args.license_override,
                               args.attribution, args.source_override)
    if attr is None:
        parser.error(f"no data source known for pack id '{pack_id}': pass --data-source "
                     f"({', '.join(sorted(DATA_SOURCES))}) or --attribution/--license/--source. "
                     f"Every pack must carry the attribution of the data it was built from.")

    success = build_langpack(
        lang=args.lang,
        name=args.name,
        output=args.output,
        input_file=args.input,
        dict_file=args.dict,
        unigrams_file=args.unigrams,
        use_wordfreq=args.use_wordfreq,
        version=args.version,
        author=args.author,
        model_file=args.model,
        attr=attr,
    )

    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()

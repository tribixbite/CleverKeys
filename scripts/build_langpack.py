#!/usr/bin/env python3
"""
Build Language Pack ZIP for CleverKeys.

Creates a language pack ZIP file containing:
- manifest.json: metadata (language code, name, version, inputMethod)
- dictionary.bin: V2 binary dictionary with accent normalization
- phrases.bin: V1 CKPY pinyin phrase table (required for --input-method pinyin)
- unigrams.txt: word frequency list for language detection
- contractions.json: apostrophe word mappings (optional, for languages that use them)
- prefix_boost.bin: Aho-Corasick trie for prefix boosting (optional, for non-English)
- model.onnx: CTC swipe encoder for a non-Latin script (optional, --model)

A composing pinyin pack (gh #177) carries `"inputMethod": "pinyin"` and a `phrases.bin`
built by `build_phrase_table.py`. The app refuses a pinyin pack without the table, and
refuses a table without the mode, so this script enforces the same coupling at build
time. The CKDT dictionary is still required (the swipe path decodes pinyin spellings
over it); it is the phrase table that supplies the committed 汉字.

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
    python3 build_langpack.py --lang zh --name "中文（拼音）" --dict zh_pinyin.bin \
        --input-method pinyin --phrases phrases.bin --output langpack-zh-Hans.zip

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
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent

# ZIP epoch for reproducible output. zipfile pulls the filesystem mtime when you
# call ZipFile.write(path), so the same inputs produce byte-differing archives on
# every rebuild. We instead pin every entry's timestamp to the DOS/ZIP epoch
# (the earliest value the ZIP format can represent) so the archive is a pure
# function of its file contents. (year, month, day, hour, minute, second)
ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)

# Mirrors CtcPackModel.MAX_PACK_MODEL_BYTES. Enforced here too so a pack that the
# app would refuse to import can never be built and published in the first place.
MAX_MODEL_BYTES = 8 * 1024 * 1024

# Mirrors CkpyPhraseTable.MAX_FILE_BYTES (private there): the reader refuses a larger
# table, so refuse to build one.
MAX_PHRASES_BYTES = 64 * 1024 * 1024

# Mirrors CkpyPhraseTable.MAGIC / VERSION — a phrases.bin the app cannot read must fail
# here, not at import time on a user's phone.
CKPY_MAGIC = b"CKPY"
CKPY_VERSION = 1


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
                    input_method: str = "wordfreq") -> dict:
    """Create manifest.json content.

    Key order is fixed (json.dump preserves insertion order) so the serialized
    manifest -- and therefore the whole archive -- stays a pure function of its
    inputs. "inputMethod" is written only for non-wordfreq packs, so every legacy
    pack keeps its exact manifest bytes; "model" goes last.
    """
    manifest = {
        "code": lang,
        "name": name,
        "version": version,
        "author": author,
        "wordCount": word_count,
        "hasPrefixBoost": has_prefix_boost
    }
    if input_method != "wordfreq":
        manifest["inputMethod"] = input_method
    if model_sha256:
        manifest["model"] = {"file": "model.onnx", "sha256": model_sha256}
    return manifest


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
    input_method: str = "wordfreq",
    phrases_file: Path = None
):
    """Build a language pack ZIP file."""

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

        # The phrase table and the mode must travel together, exactly as the importer
        # enforces: a pinyin pack without phrases.bin is refused at import, and a stray
        # phrases.bin without the mode is refused too. Fail at build time instead.
        phrases_bytes = None
        if input_method == "pinyin":
            if not phrases_file or not phrases_file.exists():
                print("Error: --input-method pinyin requires --phrases phrases.bin "
                      "(build it with build_phrase_table.py)")
                return False
            phrases_bytes = phrases_file.read_bytes()
            if (len(phrases_bytes) < 48 or phrases_bytes[:4] != CKPY_MAGIC
                    or int.from_bytes(phrases_bytes[4:8], byteorder="little") != CKPY_VERSION):
                print(f"Error: --phrases {phrases_file} is not a CKPY v{CKPY_VERSION} table")
                return False
            if len(phrases_bytes) > MAX_PHRASES_BYTES:
                print(f"Error: --phrases is {len(phrases_bytes)} B, over the "
                      f"{MAX_PHRASES_BYTES} B limit the reader enforces")
                return False
        elif phrases_file:
            print("Error: --phrases requires --input-method pinyin")
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
                                   model_sha256, input_method)
        manifest_file = temp_path / "manifest.json"
        with open(manifest_file, 'w', encoding='utf-8') as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)

        # Look for contractions file in assets
        contractions_file = SCRIPT_DIR.parent / f"src/main/assets/dictionaries/contractions_{lang}.json"

        # Collect entries as (arcname -> bytes) so the archive is a pure
        # function of file CONTENTS, independent of source mtimes/paths.
        entries: dict[str, bytes] = {
            "manifest.json": manifest_file.read_bytes(),
            "dictionary.bin": final_dict.read_bytes(),
        }
        if phrases_bytes is not None:
            entries["phrases.bin"] = phrases_bytes
            print(f"  + phrases.bin ({len(phrases_bytes) / 1024:.1f} KB)")
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
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
            for arcname in sorted(entries):
                _add_deterministic(zf, arcname, entries[arcname])

        # Print summary
        zip_size = output.stat().st_size
        print(f"\nLanguage Pack Created:")
        print(f"  File: {output}")
        print(f"  Size: {zip_size / 1024:.1f} KB")
        print(f"  Language: {name} ({lang})")
        print(f"  Input method: {input_method}")
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
    parser.add_argument('--lang', required=True, help='Language code (e.g., fr, de, pt)')
    parser.add_argument('--name', required=True, help='Language display name (e.g., "French")')
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
    parser.add_argument('--input-method', choices=['wordfreq', 'pinyin'], default='wordfreq',
                        help='How the pack is consumed. "pinyin" writes "inputMethod":"pinyin" '
                             'and requires --phrases; the default keeps legacy manifests '
                             'byte-identical.')
    parser.add_argument('--phrases', type=Path,
                        help='Pre-built CKPY phrases.bin (build_phrase_table.py). Required when '
                             '--input-method is pinyin; refused otherwise.')

    args = parser.parse_args()

    # Validate arguments
    if not args.input and not args.dict:
        parser.error("Must provide either --input (word list) or --dict (pre-built dictionary)")

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
        input_method=args.input_method,
        phrases_file=args.phrases
    )

    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()

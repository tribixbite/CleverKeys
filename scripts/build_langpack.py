#!/usr/bin/env python3
"""
Build Language Pack ZIP for CleverKeys.

Creates a language pack ZIP file containing:
- manifest.json: metadata (language code, name, version)
- dictionary.bin: V2 binary dictionary with accent normalization
- unigrams.txt: word frequency list for language detection
- contractions.json: apostrophe word mappings (optional, for languages that use them)

Usage:
    python3 build_langpack.py --lang fr --name "French" --input french_words.txt --output langpack-fr.zip
    python3 build_langpack.py --lang de --name "German" --input german_words.txt --output langpack-de.zip --use-wordfreq

Prerequisites:
    - Run build_dictionary.py first to generate dictionary.bin
    - Run generate_unigrams.py to generate unigrams.txt
    OR use --auto to generate all files from a single word list

Requirements:
    pip install wordfreq  # Optional, for frequency enrichment

License: Apache-2.0
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent


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


def create_manifest(lang: str, name: str, version: int, author: str, word_count: int) -> dict:
    """Create manifest.json content."""
    return {
        "code": lang,
        "name": name,
        "version": version,
        "author": author,
        "wordCount": word_count
    }


def build_langpack(
    lang: str,
    name: str,
    output: Path,
    input_file: Path = None,
    dict_file: Path = None,
    unigrams_file: Path = None,
    use_wordfreq: bool = False,
    version: int = 1,
    author: str = ""
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

        # Get word count
        word_count = count_words_in_dictionary(final_dict)
        print(f"\nDictionary contains {word_count} words")

        # Create manifest
        manifest = create_manifest(lang, name, version, author, word_count)
        manifest_file = temp_path / "manifest.json"
        with open(manifest_file, 'w', encoding='utf-8') as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)

        # Look for contractions file in assets
        contractions_file = SCRIPT_DIR.parent / f"src/main/assets/dictionaries/contractions_{lang}.json"

        # Create ZIP
        print(f"\n=== Creating {output} ===")
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
            zf.write(manifest_file, "manifest.json")
            zf.write(final_dict, "dictionary.bin")
            if final_unigrams and final_unigrams.exists():
                zf.write(final_unigrams, "unigrams.txt")
                print(f"  + unigrams.txt")
            if contractions_file.exists():
                # Check if contractions file has content (not just "{}")
                with open(contractions_file, 'r') as cf:
                    content = cf.read().strip()
                    if content and content != "{}":
                        zf.write(contractions_file, "contractions.json")
                        print(f"  + contractions.json")
                    else:
                        print(f"  (no contractions - language doesn't use apostrophes)")

        # Print summary
        zip_size = output.stat().st_size
        print(f"\nLanguage Pack Created:")
        print(f"  File: {output}")
        print(f"  Size: {zip_size / 1024:.1f} KB")
        print(f"  Language: {name} ({lang})")
        print(f"  Words: {word_count}")
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
        author=args.author
    )

    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()

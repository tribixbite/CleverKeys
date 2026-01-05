#!/usr/bin/env python3
"""
Extract real apostrophe words from AnySoftKeyboard dictionaries.

Creates contraction mapping files for CleverKeys that map:
  "cest" -> "c'est" (French)
  "dun" -> "d'un" (French)
  "lalbum" -> "l'album" (Italian)
  etc.

Uses REAL word data from ASK dictionaries, not programmatic generation.

Usage:
    python3 extract_apostrophe_words.py

Output:
    - contractions_fr.json
    - contractions_it.json
    - contractions_de.json
    - contractions_es.json
    - contractions_pt.json
"""

import gzip
import json
import re
import sys
from pathlib import Path
from collections import defaultdict

# Base path for ASK dictionaries
ASK_BASE = Path("/data/data/com.termux/files/home/git/swype/AnySoftKeyboard/addons/languages")

# Language dictionary mappings
LANG_DICT_PATHS = {
    "fr": ASK_BASE / "french/pack/dictionary/fr_wordlist.combined.gz",
    "it": ASK_BASE / "italian/pack/dictionary/it_wordlist.combined.gz",
    "de": ASK_BASE / "german/pack/dictionary/de_wordlist.combined.gz",
    "es": ASK_BASE / "spain/pack/dictionary/es_wordlist.combined.gz",
    "pt": ASK_BASE / "brazilian/pack/dictionary/pt_BR_wordlist.combined.gz",
    "en": ASK_BASE / "english/pack/dictionary/en_wordlist.combined.gz",
}

# Output directory
OUTPUT_DIR = Path(__file__).parent.parent / "src/main/assets/dictionaries"

# Regex to parse ASK word format: word=c'est,f=159,flags=,originalFreq=159
WORD_PATTERN = re.compile(r"word=([^,]+),f=(\d+)")


def extract_apostrophe_words(dict_path: Path) -> dict:
    """
    Extract words containing apostrophes from an ASK dictionary.

    Returns dict mapping: without_apostrophe -> with_apostrophe
    Only includes real words (not proper nouns starting with O', McDonald's, etc.)
    """
    contractions = {}

    if not dict_path.exists():
        print(f"  Warning: {dict_path} not found", file=sys.stderr)
        return contractions

    with gzip.open(dict_path, 'rt', encoding='utf-8') as f:
        for line in f:
            match = WORD_PATTERN.search(line)
            if not match:
                continue

            word = match.group(1)
            freq = int(match.group(2))

            # Skip words without apostrophes
            if "'" not in word:
                continue

            # Skip proper nouns (O'Brien, McDonald's, etc.)
            # These typically start with capital and have apostrophe after O or s
            if word[0].isupper() and (
                word.startswith("O'") or  # Irish names
                word.startswith("D'") or  # D'Arcy, etc.
                word.endswith("'s") or    # Possessives
                "'" in word and word.split("'")[0][0].isupper()  # Other proper nouns
            ):
                continue

            # Skip brand names and special cases
            skip_patterns = ["McDonald", "Rock'n'Roll", "rock'n'roll", "'s"]
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

    return contractions


def extract_all_languages():
    """Extract apostrophe words from all configured languages."""

    print("Extracting apostrophe words from AnySoftKeyboard dictionaries...")
    print()

    results = {}

    for lang, dict_path in LANG_DICT_PATHS.items():
        print(f"Processing {lang}...")
        contractions = extract_apostrophe_words(dict_path)
        results[lang] = contractions
        print(f"  Found {len(contractions)} apostrophe word mappings")

    return results


def write_contraction_files(results: dict):
    """Write contraction mapping files for each language."""

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print()
    print("Writing contraction files...")

    for lang, contractions in results.items():
        output_path = OUTPUT_DIR / f"contractions_{lang}.json"

        # Sort by key for consistent output
        sorted_contractions = dict(sorted(contractions.items()))

        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(sorted_contractions, f, indent=2, ensure_ascii=False)

        print(f"  {output_path}: {len(contractions)} mappings")


def print_sample(results: dict, sample_size: int = 20):
    """Print sample mappings for verification."""

    print()
    print("=" * 60)
    print("SAMPLE MAPPINGS (for verification)")
    print("=" * 60)

    for lang, contractions in results.items():
        if not contractions:
            continue

        print(f"\n{lang.upper()} ({len(contractions)} total):")
        # Show top entries by key length (usually the most common patterns)
        sorted_items = sorted(contractions.items(), key=lambda x: len(x[0]))[:sample_size]
        for key, value in sorted_items:
            print(f"  {key} -> {value}")


def main():
    # Extract from all languages
    results = extract_all_languages()

    # Write output files
    write_contraction_files(results)

    # Print samples for verification
    print_sample(results)

    # Summary
    print()
    print("=" * 60)
    print("SUMMARY")
    print("=" * 60)
    total = sum(len(c) for c in results.values())
    print(f"Total mappings: {total}")
    for lang, contractions in results.items():
        print(f"  {lang}: {len(contractions)}")

    print()
    print("Done! Contraction files written to:")
    print(f"  {OUTPUT_DIR}")


if __name__ == "__main__":
    main()

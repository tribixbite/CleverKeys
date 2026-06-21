#!/usr/bin/env python3
"""
Binary Dictionary Generator

Converts JSON dictionaries to optimized binary format for fast loading.

Binary Format V1:
-----------------
Header (32 bytes):
  - Magic number: b'DICT' (4 bytes)
  - Format version: uint32 (4 bytes) = 1
  - Number of words: uint32 (4 bytes)
  - Dictionary offset: uint32 (4 bytes) - offset to word data
  - Frequency offset: uint32 (4 bytes) - offset to frequency data
  - Prefix index offset: uint32 (4 bytes) - offset to prefix index
  - Reserved: 8 bytes for future use

Dictionary Section (sorted alphabetically):
  - For each word:
    - Word length: uint16 (2 bytes)
    - Word bytes: UTF-8 encoded string

Frequency Section (parallel to dictionary):
  - For each word:
    - Frequency: uint32 (4 bytes)

Prefix Index Section (1-3 char prefixes):
  - Number of prefixes: uint32 (4 bytes)
  - For each prefix:
    - Prefix length: uint8 (1 byte)
    - Prefix bytes: UTF-8 encoded string
    - Match count: uint32 (4 bytes)
    - Match indices: uint32[] (4 bytes each)

Benefits:
  - No JSON parsing overhead
  - Memory-mappable for instant loading
  - Pre-built prefix index (no runtime computation)
  - Compact binary representation
"""

import json
import struct
import sys
import unicodedata
from pathlib import Path
from typing import Dict, List, Tuple
from collections import defaultdict

# Binary format constants
MAGIC = b'DICT'
VERSION = 1
HEADER_SIZE = 32
PREFIX_INDEX_MAX_LENGTH = 3

# --------------------------------------------------------------------------
# Junk filter (added 2026-06-17)
#
# The bundled English word lists were built from noisy web-text corpora and
# accumulated three classes of non-words that then served as autocorrect
# CORRECTION TARGETS (e.g. typing "teg" could "correct" to the junk entry
# "teh"). This filter strips them at the .json -> .bin step so the shipped
# binary can never carry them, even if the source JSON regresses.
#
# Two rules, both conservative:
#
#   (1) ENGLISH_JUNK_BLOCKLIST — an explicit, audited set of apostrophe-split
#       contraction LEFT-fragments ("doesn" from "doesn't", "isn" from
#       "isn't", ...) and well-known typos ("teh", "wich", "havin", ...).
#       Each was verified to be (a) NOT a contraction key — the real keys are
#       the FULL forms "doesnt"/"isnt", which are re-injected into the runtime
#       dictionary by WordPredictor.loadContractionKeysIntoMaps and so must NOT
#       be removed — and (b) NOT a real word in the curated en.txt list.
#
#   (2) NON-LATIN-SCRIPT rule — for a Latin-script dictionary only, drop any
#       entry containing a character that is neither a Latin letter (accented
#       Latin is fine: "café", "résumé", "naïve" are KEPT) nor an apostrophe/
#       hyphen. Removes Greek (α, μ), Cyrillic (а, в), ordinal/symbol marks
#       (ª, º) and stray glyphs (ツ). Gated behind a >90%-ASCII heuristic so it
#       NEVER fires on a genuinely non-Latin pack (a Cyrillic/Greek dictionary
#       is left untouched).
# --------------------------------------------------------------------------
ENGLISH_JUNK_BLOCKLIST = frozenset({
    # apostrophe-split "n't" left-fragments (NOT contraction keys)
    "doesn", "didn", "isn", "wasn", "couldn", "wouldn", "aren", "shan", "ain",
    # common typos / fragments
    "teh", "wich", "hav", "havin", "abl", "thr", "thro", "snd", "ral",
})

def _is_latin_word(word: str) -> bool:
    """True iff `word` is composed only of Latin letters (accented allowed)
    plus apostrophe/hyphen. False for Greek/Cyrillic/CJK letters and symbols."""
    for ch in word:
        if ch in "'-":
            continue
        try:
            name = unicodedata.name(ch)
        except ValueError:
            return False  # control / unnamed glyph
        if unicodedata.category(ch)[0] == "L" and name.startswith("LATIN"):
            continue
        return False
    return True

def filter_junk(dictionary: Dict[str, int]) -> Tuple[Dict[str, int], List[str]]:
    """Apply the junk rules. Returns (cleaned_dict, removed_words)."""
    ascii_share = sum(1 for w in dictionary if w.isascii()) / max(1, len(dictionary))
    latin_dict = ascii_share > 0.90
    cleaned: Dict[str, int] = {}
    removed: List[str] = []
    for word, freq in dictionary.items():
        if word in ENGLISH_JUNK_BLOCKLIST:
            removed.append(word)
        elif latin_dict and not _is_latin_word(word):
            removed.append(word)
        else:
            cleaned[word] = freq
    return cleaned, removed

def load_json_dictionary(json_path: Path) -> Dict[str, int]:
    """Load dictionary from JSON file, stripping junk entries."""
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    # Ensure all keys are lowercase and frequencies are integers
    dictionary = {word.lower(): int(freq) for word, freq in data.items()}
    dictionary, removed = filter_junk(dictionary)
    if removed:
        print(f"Filtered {len(removed)} junk entries: {', '.join(sorted(removed))}")
    print(f"Loaded {len(dictionary)} words from {json_path.name}")
    return dictionary

def build_prefix_index(words: List[str]) -> Dict[str, List[int]]:
    """
    Build prefix index mapping prefixes (1-3 chars) to word indices.

    Args:
        words: Sorted list of dictionary words

    Returns:
        Dict mapping prefix strings to lists of word indices
    """
    prefix_index = defaultdict(list)

    for idx, word in enumerate(words):
        max_len = min(PREFIX_INDEX_MAX_LENGTH, len(word))
        for prefix_len in range(1, max_len + 1):
            prefix = word[:prefix_len]
            prefix_index[prefix].append(idx)

    print(f"Built prefix index: {len(prefix_index)} prefixes")
    return dict(prefix_index)

def write_binary_dictionary(output_path: Path, dictionary: Dict[str, int]):
    """
    Write dictionary to binary format.

    Args:
        output_path: Output file path for binary dictionary
        dictionary: Dict mapping words to frequencies
    """
    # Sort words alphabetically for efficient binary search
    sorted_items = sorted(dictionary.items(), key=lambda x: x[0])
    words = [word for word, _ in sorted_items]
    frequencies = [freq for _, freq in sorted_items]

    # Build prefix index
    prefix_index = build_prefix_index(words)

    # Calculate section offsets
    dict_offset = HEADER_SIZE
    freq_offset = dict_offset + sum(2 + len(word.encode('utf-8')) for word in words)
    prefix_offset = freq_offset + len(frequencies) * 4

    with open(output_path, 'wb') as f:
        # Write header
        header = struct.pack(
            '<4sIIIII8s',
            MAGIC,              # Magic number
            VERSION,            # Format version
            len(words),         # Number of words
            dict_offset,        # Dictionary offset
            freq_offset,        # Frequency offset
            prefix_offset,      # Prefix index offset
            b'\x00' * 8         # Reserved
        )
        f.write(header)

        # Write dictionary section
        for word in words:
            word_bytes = word.encode('utf-8')
            f.write(struct.pack('<H', len(word_bytes)))
            f.write(word_bytes)

        # Write frequency section
        for freq in frequencies:
            f.write(struct.pack('<I', freq))

        # Write prefix index section
        f.write(struct.pack('<I', len(prefix_index)))
        for prefix, indices in sorted(prefix_index.items()):
            prefix_bytes = prefix.encode('utf-8')
            f.write(struct.pack('<B', len(prefix_bytes)))
            f.write(prefix_bytes)
            f.write(struct.pack('<I', len(indices)))
            for idx in indices:
                f.write(struct.pack('<I', idx))

    file_size = output_path.stat().st_size
    json_size = Path(str(output_path).replace('.bin', '.json')).stat().st_size if Path(str(output_path).replace('.bin', '.json')).exists() else 0
    compression_ratio = (1 - file_size / json_size) * 100 if json_size > 0 else 0

    print(f"Written binary dictionary: {output_path.name}")
    print(f"  Words: {len(words)}")
    print(f"  Prefixes: {len(prefix_index)}")
    print(f"  File size: {file_size:,} bytes")
    if json_size > 0:
        print(f"  Compression: {compression_ratio:.1f}% smaller than JSON")

def main():
    if len(sys.argv) < 2:
        print("Usage: python generate_binary_dict.py <input_json> [output_bin]")
        print("\nExample:")
        print("  python scripts/generate_binary_dict.py assets/dictionaries/en_enhanced.json")
        sys.exit(1)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        print(f"Error: Input file not found: {input_path}")
        sys.exit(1)

    # Default output: same name with .bin extension
    if len(sys.argv) >= 3:
        output_path = Path(sys.argv[2])
    else:
        output_path = input_path.with_suffix('.bin')

    print(f"Converting {input_path} to binary format...")
    dictionary = load_json_dictionary(input_path)
    write_binary_dictionary(output_path, dictionary)
    print(f"\n✓ Binary dictionary generated successfully!")

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
Build binary dictionary with accent normalization for CleverKeys.

Generates V2 binary dictionary format (.bin) from word lists with:
- Accent normalization for swipe typing lookup
- Frequency ranks (0-255) from wordfreq or raw frequencies
- Canonical → normalized mapping for display

Usage:
    python3 build_dictionary.py --lang es --input spanish_words.txt --output es_enhanced.bin
    python3 build_dictionary.py --lang fr --input french_words.txt --output fr_enhanced.bin --use-wordfreq

Input formats supported:
    - One word per line (uses wordfreq for frequencies)
    - word<tab>frequency (uses provided frequencies)
    - word<space>frequency (uses provided frequencies)

Output: V2 binary format compatible with BinaryDictionaryLoader.loadIntoNormalizedIndex()

Requirements:
    pip install wordfreq  # Optional, for frequency enrichment

License: Apache-2.0 (code), CC BY-SA 4.0 (dictionary data if using wordfreq)
"""

import argparse
import struct
import unicodedata
import sys
from pathlib import Path
from typing import Dict, List, Tuple, Optional
from collections import defaultdict
import math

# Try to import wordfreq for frequency enrichment
try:
    import wordfreq
    WORDFREQ_AVAILABLE = True
except ImportError:
    WORDFREQ_AVAILABLE = False
    print("Warning: wordfreq not installed. Using default frequencies.", file=sys.stderr)
    print("Install with: pip install wordfreq", file=sys.stderr)


def normalize_accents(word: str) -> str:
    """
    Remove diacritical marks from a word.

    Uses Unicode NFD normalization to decompose characters,
    then removes combining diacritical marks (U+0300-U+036F).
    Also handles special characters like ß, ø, æ, etc.

    Args:
        word: Input word (may contain accents)

    Returns:
        Normalized word with accents stripped, lowercase
    """
    # Special replacements first
    special = {
        'ß': 'ss', 'ø': 'o', 'Ø': 'o', 'ð': 'd', 'Ð': 'd',
        'þ': 'th', 'Þ': 'th', 'æ': 'ae', 'Æ': 'ae',
        'œ': 'oe', 'Œ': 'oe', 'ł': 'l', 'Ł': 'l',
        'đ': 'd', 'Đ': 'd', 'ı': 'i', 'ħ': 'h', 'Ħ': 'h'
    }

    result = word.lower()
    for char, replacement in special.items():
        result = result.replace(char, replacement)

    # NFD decomposition + remove combining marks
    normalized = unicodedata.normalize('NFD', result)
    return ''.join(c for c in normalized if unicodedata.category(c) != 'Mn')


def load_word_list(input_path: Path) -> List[Tuple[str, Optional[int]]]:
    """
    Load words from input file.

    Supports formats:
    - word (frequency will be looked up or defaulted)
    - word<tab>frequency
    - word<space>frequency

    Args:
        input_path: Path to input word list

    Returns:
        List of (word, frequency) tuples. Frequency is None if not provided.
    """
    words = []
    with open(input_path, 'r', encoding='utf-8') as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith('#'):
                continue

            # Try tab-separated first
            if '\t' in line:
                parts = line.split('\t')
                word = parts[0].strip()
                freq = int(parts[1]) if len(parts) > 1 and parts[1].strip().isdigit() else None
            # Try space-separated (word freq)
            elif ' ' in line:
                parts = line.rsplit(' ', 1)
                word = parts[0].strip()
                freq = int(parts[1]) if len(parts) > 1 and parts[1].strip().isdigit() else None
            else:
                word = line
                freq = None

            if word and len(word) <= 50:  # Skip very long "words"
                words.append((word, freq))

    return words


def get_word_frequency(word: str, lang: str, provided_freq: Optional[int]) -> float:
    """
    Get word frequency, using wordfreq if available.

    Args:
        word: The word to look up
        lang: Language code (e.g., 'es', 'fr')
        provided_freq: User-provided frequency (used if wordfreq unavailable)

    Returns:
        Frequency value (higher = more common)
    """
    if provided_freq is not None:
        return float(provided_freq)

    if WORDFREQ_AVAILABLE:
        # wordfreq returns values like 1e-4 for common words, 1e-8 for rare
        freq = wordfreq.word_frequency(word, lang)
        if freq > 0:
            # Convert to positive scale (1e-8 → 1, 1e-4 → 10000)
            return freq * 1e8
        return 1.0  # Default for unknown words

    return 1000.0  # Default when no frequency source


def frequency_to_rank(frequency: float, max_freq: float) -> int:
    """
    Convert frequency to rank (0 = most common, 255 = least common).

    Uses log scale for better distribution.

    Args:
        frequency: Word frequency (higher = more common)
        max_freq: Maximum frequency in corpus

    Returns:
        Rank 0-255
    """
    if frequency <= 0 or max_freq <= 0:
        return 255

    log_freq = math.log(frequency + 1)
    log_max = math.log(max_freq + 1)

    # Invert so higher frequency = lower rank
    rank = int((1.0 - log_freq / log_max) * 255)
    return max(0, min(255, rank))


def build_accent_map(words_with_freq: List[Tuple[str, float]]) -> Dict[str, List[Tuple[str, float]]]:
    """
    Build mapping from normalized forms to canonical forms.

    Args:
        words_with_freq: List of (canonical_word, frequency) tuples

    Returns:
        Dict mapping normalized form to list of (canonical, frequency) tuples
    """
    accent_map: Dict[str, List[Tuple[str, float]]] = defaultdict(list)

    for canonical, freq in words_with_freq:
        normalized = normalize_accents(canonical)
        accent_map[normalized].append((canonical, freq))

    # Sort each list by frequency (highest first)
    for normalized in accent_map:
        accent_map[normalized].sort(key=lambda x: x[1], reverse=True)

    return accent_map


def write_v2_binary(
    output_path: Path,
    lang: str,
    canonical_words: List[Tuple[str, int]],  # (word, rank)
    normalized_words: List[str],
    accent_map: Dict[str, List[int]]  # normalized → [canonical_indices]
):
    """
    Write V2 binary dictionary format.

    Args:
        output_path: Output file path
        lang: Language code (2-4 chars)
        canonical_words: List of (canonical_word, frequency_rank) tuples
        normalized_words: List of normalized words (keys for lookup)
        accent_map: Mapping from normalized word to canonical indices
    """
    with open(output_path, 'wb') as f:
        # We'll write header last after calculating offsets
        header_size = 48

        # Prepare canonical section
        canonical_data = bytearray()
        for word, rank in canonical_words:
            word_bytes = word.encode('utf-8')
            canonical_data += struct.pack('<H', len(word_bytes))  # uint16 length
            canonical_data += word_bytes
            canonical_data += struct.pack('<B', rank)  # uint8 rank

        # Prepare normalized section
        normalized_data = bytearray()
        normalized_data += struct.pack('<I', len(normalized_words))  # count
        for word in normalized_words:
            word_bytes = word.encode('utf-8')
            normalized_data += struct.pack('<H', len(word_bytes))
            normalized_data += word_bytes

        # Prepare accent map section
        accent_map_data = bytearray()
        for normalized in normalized_words:
            indices = accent_map.get(normalized, [])
            accent_map_data += struct.pack('<B', len(indices))  # uint8 count
            for idx in indices:
                accent_map_data += struct.pack('<I', idx)  # uint32 index

        # Calculate offsets
        canonical_offset = header_size
        normalized_offset = canonical_offset + len(canonical_data)
        accent_map_offset = normalized_offset + len(normalized_data)

        # Write header
        lang_bytes = lang.encode('utf-8')[:4].ljust(4, b'\x00')
        header = struct.pack('<I', 0x54444B43)  # Magic: "CKDT" (0x43='C', 0x4B='K', 0x44='D', 0x54='T')
        header += struct.pack('<I', 2)  # Version
        header += lang_bytes  # Language (4 bytes)
        header += struct.pack('<I', len(canonical_words))  # Word count
        header += struct.pack('<I', canonical_offset)
        header += struct.pack('<I', normalized_offset)
        header += struct.pack('<I', accent_map_offset)
        header += b'\x00' * 20  # Reserved

        f.write(header)
        f.write(canonical_data)
        f.write(normalized_data)
        f.write(accent_map_data)

    print(f"Wrote {output_path}: {len(canonical_words)} canonical, "
          f"{len(normalized_words)} normalized, "
          f"{sum(len(v) for v in accent_map.values())} mappings")


def main():
    parser = argparse.ArgumentParser(
        description='Build binary dictionary with accent normalization',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument('--lang', required=True, help='Language code (e.g., es, fr, de)')
    parser.add_argument('--input', required=True, type=Path, help='Input word list file')
    parser.add_argument('--output', required=True, type=Path, help='Output binary file')
    parser.add_argument('--use-wordfreq', action='store_true',
                        help='Use wordfreq for frequency enrichment')
    parser.add_argument('--min-length', type=int, default=1,
                        help='Minimum word length (default: 1)')
    parser.add_argument('--max-length', type=int, default=30,
                        help='Maximum word length (default: 30)')

    args = parser.parse_args()

    if args.use_wordfreq and not WORDFREQ_AVAILABLE:
        print("Error: --use-wordfreq specified but wordfreq not installed", file=sys.stderr)
        sys.exit(1)

    # Load words
    print(f"Loading words from {args.input}...")
    raw_words = load_word_list(args.input)
    print(f"Loaded {len(raw_words)} words")

    # Filter by length
    raw_words = [(w, f) for w, f in raw_words
                 if args.min_length <= len(w) <= args.max_length]
    print(f"After length filter: {len(raw_words)} words")

    # Get frequencies
    print(f"Getting frequencies (lang={args.lang})...")
    words_with_freq = []
    for word, provided_freq in raw_words:
        freq = get_word_frequency(word, args.lang, provided_freq)
        words_with_freq.append((word, freq))

    # Find max frequency for rank conversion
    max_freq = max(f for _, f in words_with_freq) if words_with_freq else 1.0

    # Build accent map
    print("Building accent map...")
    accent_map_raw = build_accent_map(words_with_freq)
    print(f"Found {len(accent_map_raw)} unique normalized forms")

    # Convert to indexed format
    canonical_words: List[Tuple[str, int]] = []  # (word, rank)
    canonical_to_idx: Dict[str, int] = {}

    for word, freq in words_with_freq:
        if word not in canonical_to_idx:
            rank = frequency_to_rank(freq, max_freq)
            canonical_to_idx[word] = len(canonical_words)
            canonical_words.append((word, rank))

    # Build normalized list and index mapping
    normalized_words = list(accent_map_raw.keys())
    accent_map_indexed: Dict[str, List[int]] = {}

    for normalized, canonicals in accent_map_raw.items():
        indices = [canonical_to_idx[c] for c, _ in canonicals if c in canonical_to_idx]
        accent_map_indexed[normalized] = indices

    # Write binary
    print(f"Writing binary dictionary to {args.output}...")
    write_v2_binary(
        args.output,
        args.lang,
        canonical_words,
        normalized_words,
        accent_map_indexed
    )

    print("Done!")

    # Print statistics
    accented_count = sum(1 for w, _ in canonical_words if w != normalize_accents(w))
    print(f"\nStatistics:")
    print(f"  Total canonical words: {len(canonical_words)}")
    print(f"  Words with accents: {accented_count} ({100*accented_count/len(canonical_words):.1f}%)")
    print(f"  Unique normalized forms: {len(normalized_words)}")
    print(f"  Accent collisions: {len(canonical_words) - len(normalized_words)}")


if __name__ == '__main__':
    main()

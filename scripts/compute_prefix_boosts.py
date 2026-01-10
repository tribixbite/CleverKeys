#!/usr/bin/env python3
"""
Compute language-specific prefix boosts for beam search.

This script analyzes dictionaries to find prefixes that are common in the target
language (e.g., French) but rare in English. These prefixes get under-predicted
by the English-trained neural network, so we apply additive boosts to the logits.

Algorithm:
1. For each prefix length (2-4 characters), compute conditional continuation probabilities:
   - P_target(c|prefix): probability of next char c given prefix in target language
   - P_english(c|prefix): probability of next char c given prefix in English

2. Compute log-odds difference (disadvantage score):
   delta = log(P_target(c|prefix)) - log(P_english(c|prefix))

3. Store positive deltas (where target language is more likely than English) as boosts

The resulting JSON maps prefix → {next_char → boost_value}
"""

import json
import struct
import sys
import os
from collections import defaultdict
from math import log, exp


def read_binary_dictionary(path: str) -> dict:
    """
    Read CleverKeys binary dictionary format (.bin).

    Format:
    - Header: "CKDT" + version (2 bytes) + lang (2 bytes null-terminated)
    - Word count (4 bytes little-endian)
    - Trie node count (4 bytes)
    - Reserved (24 bytes)
    - Words: length (1 byte) + word (n bytes) + freq (1 byte)
    """
    words = {}
    try:
        with open(path, 'rb') as f:
            # Read header
            magic = f.read(4)
            if magic != b'CKDT':
                print(f"Warning: Invalid magic bytes in {path}: {magic}", file=sys.stderr)
                return words

            version = struct.unpack('<H', f.read(2))[0]
            lang = f.read(2).decode('utf-8').rstrip('\x00')

            # Skip rest of header (word count, node count, reserved)
            f.read(4 + 4 + 24)

            # Read words
            while True:
                len_byte = f.read(1)
                if not len_byte:
                    break
                word_len = len_byte[0]
                if word_len == 0:
                    continue
                word = f.read(word_len).decode('utf-8', errors='ignore')
                freq_byte = f.read(1)
                if not freq_byte:
                    break
                freq = freq_byte[0]
                # Convert stored frequency (log-scale 0-255) to linear scale
                # The encoding uses: stored = 255 - (255 / log(max_freq)) * log(freq)
                # For simplicity, we'll use the stored value as a ranking score
                words[word.lower()] = max(1, freq)

    except Exception as e:
        print(f"Error reading {path}: {e}", file=sys.stderr)

    return words


def read_json_dictionary(path: str) -> dict:
    """Read JSON dictionary format (word → frequency)."""
    try:
        with open(path, 'r') as f:
            data = json.load(f)
            return {word.lower(): freq for word, freq in data.items()}
    except Exception as e:
        print(f"Error reading {path}: {e}", file=sys.stderr)
        return {}


def compute_prefix_continuations(words: dict, max_prefix_len: int = 4) -> dict:
    """
    Compute conditional continuation probabilities P(next_char | prefix).

    Returns: dict[prefix] → dict[next_char] → count
    """
    continuations = defaultdict(lambda: defaultdict(int))

    for word, freq in words.items():
        # Weight by frequency (use log-scale to prevent very common words from dominating)
        weight = max(1, int(log(freq + 1) * 10))

        for prefix_len in range(1, max_prefix_len + 1):
            for i in range(len(word) - prefix_len):
                prefix = word[i:i + prefix_len]
                next_char = word[i + prefix_len]
                if next_char.isalpha():
                    continuations[prefix][next_char.lower()] += weight

    return continuations


def compute_prefix_boosts(
    target_dict: dict,
    english_dict: dict,
    min_prefix_len: int = 2,
    max_prefix_len: int = 4,
    alpha: float = 1.0,  # Smoothing parameter
    min_target_count: int = 10,  # Minimum count in target language to consider
    boost_threshold: float = 0.5,  # Minimum log-odds to include
) -> dict:
    """
    Compute boost values for prefixes disadvantaged in English.

    Returns: dict[prefix] → dict[next_char] → boost_value
    """
    target_cont = compute_prefix_continuations(target_dict, max_prefix_len)
    english_cont = compute_prefix_continuations(english_dict, max_prefix_len)

    boosts = {}

    for prefix, target_chars in target_cont.items():
        if len(prefix) < min_prefix_len or len(prefix) > max_prefix_len:
            continue

        english_chars = english_cont.get(prefix, {})

        # Compute total counts for normalization
        target_total = sum(target_chars.values()) + 26 * alpha
        english_total = sum(english_chars.values()) + 26 * alpha

        prefix_boosts = {}

        for char, target_count in target_chars.items():
            if target_count < min_target_count:
                continue

            english_count = english_chars.get(char, 0)

            # Compute conditional probabilities with smoothing
            p_target = (target_count + alpha) / target_total
            p_english = (english_count + alpha) / english_total

            # Log-odds difference (positive = target more likely)
            delta = log(p_target) - log(p_english)

            # Only store if target is significantly more likely
            if delta > boost_threshold:
                prefix_boosts[char] = round(delta, 4)

        if prefix_boosts:
            boosts[prefix] = prefix_boosts

    return boosts


def simplify_boosts(boosts: dict, top_k_per_prefix: int = 5) -> dict:
    """
    Simplify boost table by keeping only top K boosts per prefix
    and removing redundant shorter prefixes.
    """
    simplified = {}

    for prefix, char_boosts in boosts.items():
        # Keep top K by boost value
        sorted_boosts = sorted(char_boosts.items(), key=lambda x: -x[1])
        top_boosts = dict(sorted_boosts[:top_k_per_prefix])

        if top_boosts:
            simplified[prefix] = top_boosts

    return simplified


def main():
    import argparse

    parser = argparse.ArgumentParser(description='Compute prefix boosts for beam search')
    parser.add_argument('--target-lang', default='fr', help='Target language code')
    parser.add_argument('--target-dict', help='Path to target language dictionary')
    parser.add_argument('--english-dict', help='Path to English dictionary')
    parser.add_argument('--output', help='Output JSON path')
    parser.add_argument('--min-prefix', type=int, default=2, help='Minimum prefix length')
    parser.add_argument('--max-prefix', type=int, default=4, help='Maximum prefix length')
    parser.add_argument('--alpha', type=float, default=1.0, help='Smoothing parameter')
    parser.add_argument('--threshold', type=float, default=0.5, help='Minimum boost threshold')
    parser.add_argument('--verbose', action='store_true', help='Print verbose output')

    args = parser.parse_args()

    # Set default paths based on project structure
    base_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    assets_path = os.path.join(base_path, 'src/main/assets')

    if not args.target_dict:
        # Try binary first, then JSON
        bin_path = os.path.join(assets_path, f'dictionaries/{args.target_lang}_enhanced.bin')
        json_path = os.path.join(assets_path, f'dictionaries/{args.target_lang}_enhanced.json')
        if os.path.exists(bin_path):
            args.target_dict = bin_path
        elif os.path.exists(json_path):
            args.target_dict = json_path
        else:
            print(f"Error: No dictionary found for {args.target_lang}", file=sys.stderr)
            sys.exit(1)

    if not args.english_dict:
        bin_path = os.path.join(assets_path, 'dictionaries/en_enhanced.bin')
        json_path = os.path.join(assets_path, 'dictionaries/en_enhanced.json')
        if os.path.exists(json_path):
            args.english_dict = json_path
        elif os.path.exists(bin_path):
            args.english_dict = bin_path
        else:
            print("Error: No English dictionary found", file=sys.stderr)
            sys.exit(1)

    if not args.output:
        output_dir = os.path.join(assets_path, 'prefix_boosts')
        os.makedirs(output_dir, exist_ok=True)
        args.output = os.path.join(output_dir, f'{args.target_lang}.json')

    # Load dictionaries
    print(f"Loading target dictionary: {args.target_dict}")
    if args.target_dict.endswith('.bin'):
        target_dict = read_binary_dictionary(args.target_dict)
    else:
        target_dict = read_json_dictionary(args.target_dict)
    print(f"  Loaded {len(target_dict)} words")

    print(f"Loading English dictionary: {args.english_dict}")
    if args.english_dict.endswith('.bin'):
        english_dict = read_binary_dictionary(args.english_dict)
    else:
        english_dict = read_json_dictionary(args.english_dict)
    print(f"  Loaded {len(english_dict)} words")

    # Compute boosts
    print(f"\nComputing prefix boosts (prefix len {args.min_prefix}-{args.max_prefix})...")
    boosts = compute_prefix_boosts(
        target_dict,
        english_dict,
        min_prefix_len=args.min_prefix,
        max_prefix_len=args.max_prefix,
        alpha=args.alpha,
        boost_threshold=args.threshold,
    )

    # Simplify
    simplified = simplify_boosts(boosts)

    # Stats
    total_prefixes = len(simplified)
    total_boosts = sum(len(v) for v in simplified.values())
    print(f"  Generated {total_boosts} boosts across {total_prefixes} prefixes")

    # Show sample boosts for the problematic prefix "ve"
    if args.verbose or 've' in simplified:
        print("\nSample boosts for 've' prefix (the 'veux' problem):")
        if 've' in simplified:
            for char, boost in sorted(simplified['ve'].items(), key=lambda x: -x[1]):
                print(f"  ve + '{char}' → boost={boost:.3f}")
        else:
            print("  (no significant boosts found for 've')")

    # Save output
    output_data = {
        'version': 1,
        'target_language': args.target_lang,
        'source_language': 'en',
        'parameters': {
            'min_prefix_len': args.min_prefix,
            'max_prefix_len': args.max_prefix,
            'alpha': args.alpha,
            'threshold': args.threshold,
        },
        'boosts': simplified,
    }

    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, 'w') as f:
        json.dump(output_data, f, indent=2, ensure_ascii=False)

    print(f"\nSaved to: {args.output}")
    print(f"File size: {os.path.getsize(args.output) / 1024:.1f} KB")


if __name__ == '__main__':
    main()

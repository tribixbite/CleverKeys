#!/usr/bin/env python3
"""
Generate unigram frequency lists for language detection.

Creates simple word lists ordered by frequency (most common first).
Used by UnigramLanguageDetector for word-based language detection.

Usage:
    python3 generate_unigrams.py --lang en --output ../src/main/assets/unigrams/en_unigrams.txt
    python3 generate_unigrams.py --lang es --output ../src/main/assets/unigrams/es_unigrams.txt

Input: Uses wordfreq library for frequency data
Output: One word per line, ordered by frequency (most common first)

Requirements:
    pip install wordfreq

License: Apache-2.0
"""

import argparse
import sys
from pathlib import Path

# Try to import wordfreq
try:
    import wordfreq
    WORDFREQ_AVAILABLE = True
except ImportError:
    WORDFREQ_AVAILABLE = False
    print("Warning: wordfreq not installed. Install with: pip install wordfreq", file=sys.stderr)


def generate_unigrams(lang: str, output_path: Path, top_n: int = 5000, min_length: int = 2):
    """
    Generate unigram frequency list for a language.

    Args:
        lang: Language code (e.g., 'en', 'es', 'fr')
        output_path: Output file path
        top_n: Number of top words to include
        min_length: Minimum word length
    """
    if not WORDFREQ_AVAILABLE:
        print("Error: wordfreq is required. Install with: pip install wordfreq", file=sys.stderr)
        sys.exit(1)

    print(f"Generating unigrams for {lang}...")

    # Get top words from wordfreq
    try:
        words = wordfreq.top_n_list(lang, top_n * 2)  # Get extra to filter
    except LookupError:
        print(f"Error: Language '{lang}' not available in wordfreq", file=sys.stderr)
        print("Available languages:", wordfreq.available_languages())
        sys.exit(1)

    # Filter and clean
    filtered = []
    for word in words:
        word = word.lower().strip()

        # Skip if too short
        if len(word) < min_length:
            continue

        # Skip if contains non-letter characters
        if not word.isalpha():
            continue

        filtered.append(word)

        if len(filtered) >= top_n:
            break

    # Ensure output directory exists
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Write output
    with open(output_path, 'w', encoding='utf-8') as f:
        for word in filtered:
            f.write(word + '\n')

    print(f"Wrote {len(filtered)} unigrams to {output_path}")

    # Show sample
    print(f"\nTop 20 words for {lang}:")
    for i, word in enumerate(filtered[:20], 1):
        print(f"  {i}. {word}")


def main():
    parser = argparse.ArgumentParser(
        description='Generate unigram frequency lists for language detection',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument('--lang', required=True, help='Language code (e.g., en, es, fr)')
    parser.add_argument('--output', required=True, type=Path, help='Output file path')
    parser.add_argument('--top-n', type=int, default=5000, help='Number of top words (default: 5000)')
    parser.add_argument('--min-length', type=int, default=2, help='Minimum word length (default: 2)')

    args = parser.parse_args()

    generate_unigrams(args.lang, args.output, args.top_n, args.min_length)


if __name__ == '__main__':
    main()

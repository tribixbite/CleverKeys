#!/usr/bin/env python3
"""
Parse Swahili frequency list from Kwici Wikipedia corpus ODS file.

The ODS file contains word frequencies from a 2.8M word corpus of Swahili Wikipedia.
Source: https://kevindonnelly.org.uk/swahili/swwiki/
License: CC-BY-SA

Output format matches get_wordlist.py for use with build_dictionary.py

Usage:
    python3 parse_swahili_ods.py --input swahili_freq.ods --output sw_words.txt --count 20000
"""

import argparse
import re
import sys
import zipfile
from pathlib import Path


def parse_ods_content(content_xml: str) -> list:
    """
    Parse ODS content.xml and extract word-frequency pairs.

    Returns list of (word, frequency) tuples sorted by frequency descending.
    """
    # Pattern to match table rows with frequency and word
    # Format: <table:table-row>...<text:p>FREQ</text:p>...<text:p>WORD</text:p>...</table:table-row>
    row_pattern = re.compile(
        r'<table:table-row[^>]*>.*?'
        r'office:value="(\d+)".*?'  # frequency value
        r'<text:p>([^<]+)</text:p>.*?'  # word in second cell
        r'</table:table-row>',
        re.DOTALL
    )

    results = []
    for match in row_pattern.finditer(content_xml):
        freq_str = match.group(1)
        # The word is in the second <text:p> tag
        # Find all text:p tags in this match
        text_p_matches = re.findall(r'<text:p>([^<]+)</text:p>', match.group(0))
        if len(text_p_matches) >= 2:
            freq = int(freq_str)
            word = text_p_matches[1]  # Second text:p is the word
            results.append((word, freq))

    return results


def filter_words(words: list, min_length: int = 2, max_length: int = 25) -> list:
    """Filter words by length and valid characters."""
    filtered = []
    for word, freq in words:
        # Skip words with invalid length
        if len(word) < min_length or len(word) > max_length:
            continue
        # Skip words with digits or special characters (allow letters only)
        if not word.isalpha():
            continue
        # Skip single letters (except common ones like "i" in some languages)
        if len(word) == 1:
            continue
        filtered.append((word, freq))
    return filtered


def main():
    parser = argparse.ArgumentParser(
        description='Parse Swahili frequency list from ODS file'
    )
    parser.add_argument('--input', required=True, help='Input ODS file path')
    parser.add_argument('--output', required=True, help='Output word list file')
    parser.add_argument('--count', type=int, default=20000,
                        help='Number of words to output (default: 20000)')
    parser.add_argument('--min-length', type=int, default=2,
                        help='Minimum word length (default: 2)')
    parser.add_argument('--max-length', type=int, default=25,
                        help='Maximum word length (default: 25)')
    parser.add_argument('--with-freq', action='store_true',
                        help='Include frequency in output (word<TAB>freq)')

    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        print(f"Error: Input file not found: {args.input}", file=sys.stderr)
        sys.exit(1)

    print(f"Parsing ODS file: {args.input}")

    # Read content.xml from ODS (which is a ZIP file)
    try:
        with zipfile.ZipFile(input_path, 'r') as zf:
            content_xml = zf.read('content.xml').decode('utf-8')
    except Exception as e:
        print(f"Error reading ODS file: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"Extracting word frequencies...")
    words = parse_ods_content(content_xml)
    print(f"Found {len(words)} total entries")

    # Filter words
    filtered = filter_words(words, args.min_length, args.max_length)
    print(f"After filtering: {len(filtered)} words")

    # Take top N words (already sorted by frequency in the ODS)
    top_words = filtered[:args.count]
    print(f"Taking top {len(top_words)} words")

    # Write output
    with open(args.output, 'w', encoding='utf-8') as f:
        for word, freq in top_words:
            if args.with_freq:
                f.write(f"{word}\t{freq}\n")
            else:
                f.write(f"{word}\n")

    print(f"Saved to {args.output}")

    # Print sample
    print(f"\nTop 20 words:")
    for word, freq in top_words[:20]:
        print(f"  {word}: {freq}")


if __name__ == '__main__':
    main()

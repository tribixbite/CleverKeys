#!/usr/bin/env python3
"""
Convert frequency word lists to dictionary format.

Supports input formats:
- word<space>count (FrequencyWords/OpenSubtitles format)
- word<tab>count

Usage:
    python3 convert_frequency_list.py --input en_opensubtitles_50k.txt --output en_words.txt --count 25000
"""

import argparse
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description='Convert frequency list to dictionary format')
    parser.add_argument('--input', required=True, help='Input frequency file')
    parser.add_argument('--output', required=True, help='Output word list file')
    parser.add_argument('--count', type=int, default=25000, help='Max words to output')
    parser.add_argument('--min-length', type=int, default=2, help='Min word length')
    parser.add_argument('--max-length', type=int, default=25, help='Max word length')
    parser.add_argument('--with-freq', action='store_true', help='Include frequency in output')
    parser.add_argument('--alpha-only', action='store_true', help='Only alphabetic words')

    args = parser.parse_args()

    words = []
    with open(args.input, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            # Parse word and frequency
            if '\t' in line:
                parts = line.split('\t')
            else:
                parts = line.split(' ')

            if len(parts) < 2:
                continue

            word = parts[0]
            try:
                freq = int(parts[1])
            except ValueError:
                continue

            # Filter
            if len(word) < args.min_length or len(word) > args.max_length:
                continue

            if args.alpha_only and not word.isalpha():
                continue

            # Skip contractions parts like 's, 't, 'm
            if word.startswith("'"):
                continue

            words.append((word, freq))

            if len(words) >= args.count:
                break

    print(f"Extracted {len(words)} words from {args.input}")

    # Write output
    with open(args.output, 'w', encoding='utf-8') as f:
        for word, freq in words:
            if args.with_freq:
                f.write(f"{word}\t{freq}\n")
            else:
                f.write(f"{word}\n")

    print(f"Saved to {args.output}")

    # Print sample
    print(f"\nTop 20 words:")
    for word, freq in words[:20]:
        print(f"  {word}: {freq}")


if __name__ == '__main__':
    main()

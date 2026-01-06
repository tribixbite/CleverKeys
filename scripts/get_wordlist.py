#!/usr/bin/env python3
"""
Generate word list from wordfreq for a given language.

Usage:
    python3 get_wordlist.py --lang fr --output french_words.txt --count 50000
"""

import argparse
import sys

try:
    from wordfreq import iter_wordlist, word_frequency
except ImportError:
    print("Error: wordfreq not installed. Run: pip install wordfreq", file=sys.stderr)
    sys.exit(1)


# Languages known to be properly supported by wordfreq
# Verified by checking that native words have higher frequency than English words
SUPPORTED_LANGUAGES = {
    'en', 'es', 'fr', 'pt', 'it', 'de', 'nl', 'id', 'ms', 'tl', 'fil',
    'ar', 'bg', 'bn', 'bs', 'ca', 'cs', 'cy', 'da', 'el', 'et', 'eu',
    'fa', 'fi', 'he', 'hi', 'hr', 'hu', 'hy', 'is', 'ja', 'ka', 'kk',
    'ko', 'lt', 'lv', 'mk', 'mn', 'nb', 'nn', 'no', 'pl', 'ro', 'ru',
    'sh', 'sk', 'sl', 'sq', 'sr', 'sv', 'ta', 'te', 'th', 'tr', 'uk',
    'ur', 'vi', 'zh'
}

# Languages NOT properly supported - will fall back to English
UNSUPPORTED_LANGUAGES = {'sw'}  # Swahili falls back to English


def main():
    parser = argparse.ArgumentParser(description='Generate word list from wordfreq')
    parser.add_argument('--lang', required=True, help='Language code (e.g., fr, de, pt)')
    parser.add_argument('--output', required=True, help='Output file path')
    parser.add_argument('--count', type=int, default=50000, help='Number of words (default: 50000)')
    parser.add_argument('--min-length', type=int, default=2, help='Minimum word length (default: 2)')
    parser.add_argument('--max-length', type=int, default=25, help='Maximum word length (default: 25)')
    parser.add_argument('--force', action='store_true', help='Force generation even for unsupported languages')

    args = parser.parse_args()

    # Check for unsupported languages
    if args.lang in UNSUPPORTED_LANGUAGES and not args.force:
        print(f"Error: Language '{args.lang}' is not properly supported by wordfreq", file=sys.stderr)
        print("       wordfreq will fall back to English data, producing incorrect results.", file=sys.stderr)
        print("       Use --force to generate anyway (not recommended).", file=sys.stderr)
        sys.exit(1)

    print(f"Extracting top {args.count} words for language: {args.lang}")

    words = []
    # Try 'large' wordlist first, fall back to 'small' or 'best'
    wordlists_to_try = ['large', 'small', 'best']
    wordlist_used = None

    for wordlist in wordlists_to_try:
        try:
            for word in iter_wordlist(args.lang, wordlist=wordlist):
                # Filter by length
                if args.min_length <= len(word) <= args.max_length:
                    # Skip words with digits or special characters (except accented letters)
                    if word.isalpha():
                        words.append(word)
                        if len(words) >= args.count:
                            break
            wordlist_used = wordlist
            break
        except LookupError:
            print(f"Warning: '{wordlist}' wordlist not available for {args.lang}, trying next...")
            continue

    if not wordlist_used:
        print(f"Error: No wordlist available for language '{args.lang}'", file=sys.stderr)
        sys.exit(1)

    print(f"Using '{wordlist_used}' wordlist")

    print(f"Found {len(words)} words")

    with open(args.output, 'w', encoding='utf-8') as f:
        for word in words:
            f.write(word + '\n')

    print(f"Saved to {args.output}")


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
Preprocess AOSP .combined wordlist format to simple word list.

AOSP format:
  word=palabra,f=200,flags=,originalFreq=200

Output format:
  palabra 200
"""
import sys
import re

pattern = re.compile(r'word=([^,]+),f=(\d+)')

for line in sys.stdin:
    match = pattern.search(line)
    if match:
        word = match.group(1)
        freq = match.group(2)
        # Allow words with apostrophes (contractions, elisions)
        # Filter: must be >= 1 char, only letters and apostrophes, no leading/trailing apostrophes
        if len(word) >= 1:
            # Check if word contains only letters and apostrophes (no hyphens, numbers, etc.)
            valid_chars = all(c.isalpha() or c == "'" for c in word)
            # Don't allow words that start or end with apostrophe
            no_edge_apostrophe = not (word.startswith("'") or word.endswith("'"))
            if valid_chars and no_edge_apostrophe:
                print(f"{word}\t{freq}")

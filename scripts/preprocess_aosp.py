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
        # Skip entries with special characters or very short
        if len(word) >= 1 and word.isalpha():
            print(f"{word}\t{freq}")

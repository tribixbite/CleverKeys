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
from pathlib import Path
from typing import Dict, List, Tuple
from collections import defaultdict

# Binary format constants
MAGIC = b'DICT'
VERSION = 1
HEADER_SIZE = 32
PREFIX_INDEX_MAX_LENGTH = 3

def load_json_dictionary(json_path: Path) -> Dict[str, int]:
    """Load dictionary from JSON file."""
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    # Ensure all keys are lowercase and frequencies are integers
    dictionary = {word.lower(): int(freq) for word, freq in data.items()}
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

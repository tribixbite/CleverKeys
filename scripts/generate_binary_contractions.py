#!/usr/bin/env python3
"""
Binary Contraction Generator

Converts contraction JSON files to optimized binary format for fast loading.

Binary Format V1:
-----------------
Header (16 bytes):
  - Magic number: b'CTRB' (4 bytes)
  - Format version: uint32 (4 bytes) = 1
  - Non-paired count: uint32 (4 bytes)
  - Paired count: uint32 (4 bytes)

Non-Paired Section:
  - For each non-paired mapping (apostrophe-free -> contraction):
    - Key length: uint16 (2 bytes)
    - Key bytes: UTF-8 encoded string
    - Value length: uint16 (2 bytes)
    - Value bytes: UTF-8 encoded string

Paired Section:
  - For each paired contraction:
    - Contraction length: uint16 (2 bytes)
    - Contraction bytes: UTF-8 encoded string

Benefits:
  - No JSON parsing overhead
  - Faster loading at startup
  - Compact binary representation
  - Direct memory loading
"""

import json
import struct
import sys
from pathlib import Path
from typing import Dict, Set, Tuple

# Binary format constants
MAGIC = b'CTRB'
VERSION = 1
HEADER_SIZE = 16

def load_non_paired_contractions(json_path: Path) -> Dict[str, str]:
    """
    Load non-paired contractions from JSON file.

    Format: {"dont": "don't", "cant": "can't", ...}

    Returns:
        Dict mapping apostrophe-free forms to contractions
    """
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    # Ensure all keys and values are lowercase
    contractions = {k.lower(): v.lower() for k, v in data.items()}
    print(f"Loaded {len(contractions)} non-paired contractions from {json_path.name}")
    return contractions

def load_paired_contractions(json_path: Path) -> Set[str]:
    """
    Load paired contractions from JSON file.

    Format: {"well": [{"contraction": "we'll", "frequency": 243}], ...}

    Returns:
        Set of all contraction forms (with apostrophes)
    """
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    contractions = set()
    for base_word, variants in data.items():
        for variant in variants:
            contraction = variant['contraction'].lower()
            contractions.add(contraction)

    print(f"Loaded {len(contractions)} paired contractions from {json_path.name}")
    return contractions

def write_binary_contractions(
    output_path: Path,
    non_paired: Dict[str, str],
    paired: Set[str]
):
    """
    Write contractions to binary format.

    Args:
        output_path: Output file path for binary contractions
        non_paired: Dict mapping apostrophe-free forms to contractions
        paired: Set of paired contraction forms
    """
    with open(output_path, 'wb') as f:
        # Write header
        header = struct.pack(
            '<4sIII',
            MAGIC,              # Magic number
            VERSION,            # Format version
            len(non_paired),    # Non-paired count
            len(paired)         # Paired count
        )
        f.write(header)

        # Write non-paired section (sorted for consistency)
        for key, value in sorted(non_paired.items()):
            key_bytes = key.encode('utf-8')
            value_bytes = value.encode('utf-8')

            f.write(struct.pack('<H', len(key_bytes)))
            f.write(key_bytes)
            f.write(struct.pack('<H', len(value_bytes)))
            f.write(value_bytes)

        # Write paired section (sorted for consistency)
        for contraction in sorted(paired):
            contraction_bytes = contraction.encode('utf-8')

            f.write(struct.pack('<H', len(contraction_bytes)))
            f.write(contraction_bytes)

    file_size = output_path.stat().st_size

    print(f"\nWritten binary contractions: {output_path.name}")
    print(f"  Non-paired: {len(non_paired)}")
    print(f"  Paired: {len(paired)}")
    print(f"  Total known: {len(non_paired) + len(paired)}")
    print(f"  File size: {file_size:,} bytes")

def main():
    if len(sys.argv) < 3:
        print("Usage: python generate_binary_contractions.py <non_paired_json> <paired_json> [output_bin]")
        print("\nExample:")
        print("  python scripts/generate_binary_contractions.py \\")
        print("    assets/dictionaries/contractions_non_paired.json \\")
        print("    assets/dictionaries/contraction_pairings.json \\")
        print("    assets/dictionaries/contractions.bin")
        sys.exit(1)

    non_paired_path = Path(sys.argv[1])
    paired_path = Path(sys.argv[2])

    if not non_paired_path.exists():
        print(f"Error: Non-paired file not found: {non_paired_path}")
        sys.exit(1)

    if not paired_path.exists():
        print(f"Error: Paired file not found: {paired_path}")
        sys.exit(1)

    # Default output: contractions.bin in same directory
    if len(sys.argv) >= 4:
        output_path = Path(sys.argv[3])
    else:
        output_path = non_paired_path.parent / 'contractions.bin'

    print(f"Converting contractions to binary format...")
    non_paired = load_non_paired_contractions(non_paired_path)
    paired = load_paired_contractions(paired_path)
    write_binary_contractions(output_path, non_paired, paired)
    print(f"\n✓ Binary contractions generated successfully!")

if __name__ == '__main__':
    main()

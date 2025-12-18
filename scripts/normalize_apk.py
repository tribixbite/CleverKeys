#!/usr/bin/env python3
"""
APK Resource Path Normalizer

Removes unnecessary version qualifiers (like -v4) from resource paths in APKs
to improve reproducibility across different build environments.

The -v4 suffix is added by AAPT2 for resources that use features available since
API level 4. Since all supported devices are API 21+, these suffixes are unnecessary
and cause reproducibility issues.

Usage:
    python3 normalize_apk.py input.apk output.apk [--sign]
"""

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


# Resource path patterns to normalize (remove -v4 suffix)
# Pattern: res/type-qualifiers-v4/filename -> res/type-qualifiers/filename
V4_PATTERN = re.compile(r'^(res/[^/]+-[^/]+)-v4(/[^/]+)$')

# Also handle mipmap without other qualifiers: res/mipmap-hdpi-v4 -> res/mipmap-hdpi
SIMPLE_V4_PATTERN = re.compile(r'^(res/(?:drawable|mipmap)-(?:ldpi|mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi))-v4(/[^/]+)$')


def should_normalize_path(path: str) -> tuple[bool, str]:
    """
    Check if a resource path should be normalized and return the normalized path.

    Returns:
        (should_normalize, normalized_path)
    """
    # Try simple pattern first (most common case)
    match = SIMPLE_V4_PATTERN.match(path)
    if match:
        return True, match.group(1) + match.group(2)

    # Try more complex pattern
    match = V4_PATTERN.match(path)
    if match:
        return True, match.group(1) + match.group(2)

    return False, path


def normalize_resources_arsc(data: bytes, path_mappings: dict[str, str]) -> bytes:
    """
    Update resource paths in resources.arsc binary.

    This is a simplified implementation that does string replacement in the
    string pool section of the ARSC file.

    Args:
        data: Original resources.arsc content
        path_mappings: Dict mapping old paths to new paths

    Returns:
        Modified resources.arsc content
    """
    # resources.arsc uses a string pool with null-terminated UTF-8/UTF-16 strings
    # We need to be careful to maintain the same byte length for each string

    result = bytearray(data)

    for old_path, new_path in path_mappings.items():
        # The paths are stored as UTF-8 strings in the string pool
        old_bytes = old_path.encode('utf-8')
        new_bytes = new_path.encode('utf-8')

        # Only proceed if new path is shorter or equal (we can pad with nulls)
        if len(new_bytes) <= len(old_bytes):
            # Pad new path to same length
            padded_new = new_bytes + b'\x00' * (len(old_bytes) - len(new_bytes))

            # Find and replace all occurrences
            pos = 0
            while True:
                pos = result.find(old_bytes, pos)
                if pos == -1:
                    break
                result[pos:pos + len(old_bytes)] = padded_new
                pos += len(padded_new)
        else:
            print(f"Warning: Cannot normalize {old_path} -> {new_path} (new path longer)")

    return bytes(result)


def normalize_apk(input_apk: str, output_apk: str, sign: bool = False,
                  keystore: str = None, key_alias: str = None,
                  store_pass: str = None, key_pass: str = None) -> bool:
    """
    Normalize an APK by removing -v4 suffixes from resource paths.

    Args:
        input_apk: Path to input APK
        output_apk: Path to output APK
        sign: Whether to sign the output APK
        keystore: Path to keystore file (required if sign=True)
        key_alias: Key alias in keystore
        store_pass: Keystore password
        key_pass: Key password

    Returns:
        True if successful, False otherwise
    """
    print(f"Normalizing APK: {input_apk}")

    # Collect path mappings and files to process
    path_mappings = {}

    with zipfile.ZipFile(input_apk, 'r') as zin:
        # First pass: identify paths to normalize
        for info in zin.infolist():
            should_norm, new_path = should_normalize_path(info.filename)
            if should_norm:
                path_mappings[info.filename] = new_path
                print(f"  {info.filename} -> {new_path}")

        if not path_mappings:
            print("No paths need normalization")
            shutil.copy(input_apk, output_apk)
            return True

        print(f"Normalizing {len(path_mappings)} resource paths...")

        # Create output APK
        with zipfile.ZipFile(output_apk, 'w', zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)

                # Determine output filename
                if info.filename in path_mappings:
                    new_info = zipfile.ZipInfo(path_mappings[info.filename])
                    new_info.compress_type = info.compress_type
                    new_info.external_attr = info.external_attr
                    # Use fixed timestamp for reproducibility
                    new_info.date_time = (1981, 1, 1, 0, 1, 0)
                    zout.writestr(new_info, data)
                elif info.filename == 'resources.arsc':
                    # Update resource paths in ARSC file
                    modified_data = normalize_resources_arsc(data, path_mappings)
                    new_info = zipfile.ZipInfo(info.filename)
                    new_info.compress_type = zipfile.ZIP_STORED  # ARSC is stored uncompressed
                    new_info.external_attr = info.external_attr
                    new_info.date_time = (1981, 1, 1, 0, 1, 0)
                    zout.writestr(new_info, modified_data)
                else:
                    # Copy as-is but normalize timestamp
                    new_info = zipfile.ZipInfo(info.filename)
                    new_info.compress_type = info.compress_type
                    new_info.external_attr = info.external_attr
                    new_info.date_time = (1981, 1, 1, 0, 1, 0)
                    zout.writestr(new_info, data)

    # Zipalign the APK
    aligned_apk = output_apk + '.aligned'
    try:
        subprocess.run(['zipalign', '-f', '4', output_apk, aligned_apk],
                      check=True, capture_output=True)
        shutil.move(aligned_apk, output_apk)
        print("APK aligned successfully")
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        print(f"Warning: zipalign failed or not found: {e}")
        # Continue without alignment

    # Sign if requested
    if sign:
        if not keystore:
            # Use debug keystore
            keystore = 'debug.keystore'
            key_alias = key_alias or 'debug'
            store_pass = store_pass or 'debug0'
            key_pass = key_pass or 'debug0'

        try:
            # Try apksigner first (preferred)
            subprocess.run([
                'apksigner', 'sign',
                '--ks', keystore,
                '--ks-key-alias', key_alias,
                '--ks-pass', f'pass:{store_pass}',
                '--key-pass', f'pass:{key_pass}',
                output_apk
            ], check=True, capture_output=True)
            print("APK signed with apksigner")
        except (subprocess.CalledProcessError, FileNotFoundError):
            # Fall back to jarsigner
            try:
                subprocess.run([
                    'jarsigner',
                    '-keystore', keystore,
                    '-storepass', store_pass,
                    '-keypass', key_pass,
                    output_apk,
                    key_alias
                ], check=True, capture_output=True)
                print("APK signed with jarsigner")
            except (subprocess.CalledProcessError, FileNotFoundError) as e:
                print(f"Warning: Signing failed: {e}")
                return False

    print(f"Output: {output_apk}")
    return True


def main():
    parser = argparse.ArgumentParser(
        description='Normalize APK resource paths for reproducibility'
    )
    parser.add_argument('input_apk', help='Input APK file')
    parser.add_argument('output_apk', help='Output APK file')
    parser.add_argument('--sign', action='store_true', help='Sign the output APK')
    parser.add_argument('--keystore', help='Keystore file for signing')
    parser.add_argument('--key-alias', help='Key alias in keystore')
    parser.add_argument('--store-pass', help='Keystore password')
    parser.add_argument('--key-pass', help='Key password')

    args = parser.parse_args()

    if not os.path.exists(args.input_apk):
        print(f"Error: Input file not found: {args.input_apk}")
        sys.exit(1)

    success = normalize_apk(
        args.input_apk,
        args.output_apk,
        sign=args.sign,
        keystore=args.keystore,
        key_alias=args.key_alias,
        store_pass=args.store_pass,
        key_pass=args.key_pass
    )

    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()

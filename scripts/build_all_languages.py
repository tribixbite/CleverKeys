#!/usr/bin/env python3
"""
Build all supported language dictionaries and packs for CleverKeys.

Supported languages (26-letter QWERTY compatible):
- English (en) - primary, bundled
- Spanish (es) - bundled
- French (fr)
- Portuguese (pt)
- Italian (it)
- German (de)
- Indonesian (id)
- Swahili (sw)
- Malay (ms)
- Dutch (nl)
- Tagalog/Filipino (tl)

Each language gets:
- dictionary.bin: V2 binary dictionary with accent normalization
- unigrams.txt: 5000 top words for language detection

Usage:
    python3 build_all_languages.py --output-dir ./dictionaries
    python3 build_all_languages.py --output-dir ./dictionaries --bundle-dir ../src/main/assets/dictionaries
    python3 build_all_languages.py --lang fr,de,it  # Build specific languages only

Requirements:
    pip install wordfreq

License: Apache-2.0
"""

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

SCRIPT_DIR = Path(__file__).parent.resolve()

# Required helper scripts (must be in same directory)
REQUIRED_SCRIPTS = [
    'get_wordlist.py',
    'build_dictionary.py',
    'generate_unigrams.py',
    'build_langpack.py',
    'compute_prefix_boosts.py',
]

# All supported languages with display names and word counts
# Languages with 'wordlist' use pre-existing word lists instead of wordfreq
SUPPORTED_LANGUAGES = {
    'en': {'name': 'English', 'words': 50000, 'bundle': True},
    'es': {'name': 'Spanish', 'words': 50000, 'bundle': True},
    'fr': {'name': 'French', 'words': 25000, 'bundle': True},
    'pt': {'name': 'Portuguese', 'words': 25000, 'bundle': True},
    'it': {'name': 'Italian', 'words': 25000, 'bundle': True},
    'de': {'name': 'German', 'words': 25000, 'bundle': True},
    'nl': {'name': 'Dutch', 'words': 20000, 'bundle': False},
    'id': {'name': 'Indonesian', 'words': 20000, 'bundle': False},
    'ms': {'name': 'Malay', 'words': 20000, 'bundle': False},
    'tl': {'name': 'Tagalog', 'words': 20000, 'bundle': False},
    # Swahili uses wiki corpus word list (wordfreq falls back to English)
    'sw': {'name': 'Swahili', 'words': 20000, 'bundle': False, 'wordlist': 'sw_words.txt'},
}

# Minimum word count for language detection unigrams
UNIGRAM_COUNT = 5000


def check_required_scripts():
    """Check that all required helper scripts exist in the same directory."""
    missing = []
    for script in REQUIRED_SCRIPTS:
        script_path = SCRIPT_DIR / script
        if not script_path.exists():
            missing.append(script)

    if missing:
        print(f"Error: Required helper scripts not found in {SCRIPT_DIR}")
        print(f"Missing: {', '.join(missing)}")
        print()
        print("Make sure you're running this script from the cleverkeys/scripts/ directory")
        print("or that all helper scripts are in the same directory as this script.")
        print()
        print(f"Expected location: {SCRIPT_DIR}")
        return False
    return True


def check_wordfreq():
    """Check if wordfreq is installed."""
    try:
        import wordfreq
        return True
    except ImportError:
        return False


def get_wordlist(lang: str, output_file: Path, count: int) -> bool:
    """Generate word list using wordfreq."""
    cmd = [
        sys.executable,
        str(SCRIPT_DIR / 'get_wordlist.py'),
        '--lang', lang,
        '--output', str(output_file),
        '--count', str(count),
        '--min-length', '2',
        '--max-length', '25'
    ]

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  Error getting wordlist for {lang}: {result.stderr}")
        return False
    return True


def build_dictionary(lang: str, input_file: Path, output_file: Path) -> bool:
    """Build V2 binary dictionary."""
    cmd = [
        sys.executable,
        str(SCRIPT_DIR / 'build_dictionary.py'),
        '--lang', lang,
        '--input', str(input_file),
        '--output', str(output_file),
        '--use-wordfreq'
    ]

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  Error building dictionary for {lang}: {result.stderr}")
        return False
    return True


def build_unigrams(lang: str, output_file: Path) -> bool:
    """Generate unigrams for language detection."""
    cmd = [
        sys.executable,
        str(SCRIPT_DIR / 'generate_unigrams.py'),
        '--lang', lang,
        '--output', str(output_file),
        '--top-n', str(UNIGRAM_COUNT)
    ]

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  Warning: Could not generate unigrams for {lang}: {result.stderr}")
        return False
    return True


def build_langpack(lang: str, name: str, dict_file: Path, unigrams_file: Path, output_file: Path) -> bool:
    """Build language pack ZIP."""
    cmd = [
        sys.executable,
        str(SCRIPT_DIR / 'build_langpack.py'),
        '--lang', lang,
        '--name', name,
        '--dict', str(dict_file),
        '--output', str(output_file)
    ]

    if unigrams_file.exists():
        cmd.extend(['--unigrams', str(unigrams_file)])

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  Error building langpack for {lang}: {result.stderr}")
        return False
    return True


def build_prefix_boosts(lang: str) -> bool:
    """Generate prefix boost trie for non-English languages."""
    if lang == 'en':
        return True  # English doesn't need prefix boosts

    cmd = [
        sys.executable,
        str(SCRIPT_DIR / 'compute_prefix_boosts.py'),
        '--langs', lang,
        '--threshold', '1.5'
    ]

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  Warning: Could not generate prefix boosts for {lang}: {result.stderr}")
        return False
    return True


def build_language(lang: str, info: dict, output_dir: Path) -> dict:
    """Build all artifacts for a single language."""
    name = info['name']
    word_count = info['words']

    result = {
        'lang': lang,
        'name': name,
        'success': False,
        'wordlist': None,
        'dictionary': None,
        'unigrams': None,
        'prefix_boost': None,
        'langpack': None
    }

    print(f"\n{'='*60}")
    print(f"Building {name} ({lang}) - {word_count} words")
    print('='*60)

    # Create output directory
    lang_dir = output_dir / lang
    lang_dir.mkdir(parents=True, exist_ok=True)

    # Step 1: Get word list (use pre-existing if specified)
    existing_wordlist = info.get('wordlist')
    if existing_wordlist:
        wordlist_file = SCRIPT_DIR / existing_wordlist
        if not wordlist_file.exists():
            print(f"  ERROR: Word list not found: {wordlist_file}")
            return result
        print(f"  [1/5] Using existing word list: {existing_wordlist}")
    else:
        wordlist_file = lang_dir / f'{lang}_words.txt'
        print(f"  [1/5] Extracting word list...")
        if not get_wordlist(lang, wordlist_file, word_count):
            return result
    result['wordlist'] = wordlist_file

    # Step 2: Build dictionary
    dict_file = lang_dir / f'{lang}_enhanced.bin'
    print(f"  [2/5] Building V2 dictionary...")
    if not build_dictionary(lang, wordlist_file, dict_file):
        return result
    result['dictionary'] = dict_file

    # Step 3: Generate unigrams
    unigrams_file = lang_dir / 'unigrams.txt'
    print(f"  [3/5] Generating unigrams...")
    if existing_wordlist:
        # For languages with pre-existing word lists, use top N words from the list
        # (wordfreq falls back to English for unsupported languages)
        try:
            with open(wordlist_file, 'r') as f:
                lines = f.readlines()[:UNIGRAM_COUNT]
            with open(unigrams_file, 'w') as f:
                f.writelines(lines)
            print(f"    Using top {len(lines)} words from word list")
        except Exception as e:
            print(f"    Warning: Could not create unigrams: {e}")
    else:
        build_unigrams(lang, unigrams_file)  # Optional, don't fail if it doesn't work
    if unigrams_file.exists():
        result['unigrams'] = unigrams_file

    # Step 4: Generate prefix boosts (for non-English)
    # NOTE: compute_prefix_boosts.py reads from assets/dictionaries/, so we need to
    # temporarily copy the dictionary there for prefix boost generation
    if lang != 'en':
        print(f"  [4/5] Generating prefix boosts...")
        assets_dict_dir = SCRIPT_DIR.parent / 'src/main/assets/dictionaries'
        assets_dict_file = assets_dict_dir / f'{lang}_enhanced.bin'

        # Copy dictionary to assets for prefix boost computation
        assets_dict_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(dict_file, assets_dict_file)

        if build_prefix_boosts(lang):
            prefix_boost_file = SCRIPT_DIR.parent / f'src/main/assets/prefix_boosts/{lang}.bin'
            if prefix_boost_file.exists():
                result['prefix_boost'] = prefix_boost_file

        # Clean up temporary dictionary copy (unless it's a bundle language)
        if not info.get('bundle', False) and assets_dict_file.exists():
            assets_dict_file.unlink()
    else:
        print(f"  [4/5] Skipping prefix boosts (English is base language)")

    # Step 5: Build language pack
    langpack_file = output_dir / f'langpack-{lang}.zip'
    print(f"  [5/5] Building language pack...")
    if build_langpack(lang, name, dict_file, unigrams_file, langpack_file):
        result['langpack'] = langpack_file

    result['success'] = True

    # Print size info
    dict_size = dict_file.stat().st_size / 1024 / 1024
    boost_info = ""
    if result['prefix_boost']:
        boost_size = result['prefix_boost'].stat().st_size / 1024
        boost_info = f", prefix_boost={boost_size:.0f}KB"
    print(f"\n  ✓ {name}: dictionary={dict_size:.1f}MB{boost_info}")

    return result


def copy_to_bundle(results: list, bundle_dir: Path):
    """Copy bundle-marked dictionaries to assets folder."""
    bundle_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n{'='*60}")
    print("Copying to bundle directory")
    print('='*60)

    for r in results:
        if not r['success']:
            continue

        lang = r['lang']
        info = SUPPORTED_LANGUAGES.get(lang, {})

        if info.get('bundle', False) and r['dictionary']:
            dest = bundle_dir / f"{lang}_enhanced.bin"
            print(f"  Copying {lang}_enhanced.bin to assets...")
            shutil.copy2(r['dictionary'], dest)


def main():
    parser = argparse.ArgumentParser(
        description='Build all language dictionaries for CleverKeys',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument('--output-dir', type=Path, default=Path('./dictionaries'),
                        help='Output directory for generated files')
    parser.add_argument('--bundle-dir', type=Path,
                        help='Copy bundled dictionaries to this assets directory')
    parser.add_argument('--lang', type=str,
                        help='Comma-separated list of languages to build (default: all)')
    parser.add_argument('--parallel', type=int, default=1,
                        help='Number of parallel builds (default: 1)')
    parser.add_argument('--list', action='store_true',
                        help='List supported languages and exit')

    args = parser.parse_args()

    if args.list:
        print("Supported languages:")
        for code, info in SUPPORTED_LANGUAGES.items():
            bundle_marker = "[BUNDLE]" if info.get('bundle') else ""
            print(f"  {code}: {info['name']} ({info['words']} words) {bundle_marker}")
        return

    # Check required helper scripts exist
    if not check_required_scripts():
        sys.exit(1)

    # Check wordfreq
    if not check_wordfreq():
        print("Error: wordfreq not installed. Run: pip install wordfreq")
        sys.exit(1)

    # Determine which languages to build
    if args.lang:
        languages = [l.strip() for l in args.lang.split(',')]
        # Validate
        for l in languages:
            if l not in SUPPORTED_LANGUAGES:
                print(f"Error: Unknown language '{l}'")
                print(f"Supported: {', '.join(SUPPORTED_LANGUAGES.keys())}")
                sys.exit(1)
    else:
        languages = list(SUPPORTED_LANGUAGES.keys())

    print(f"Building {len(languages)} languages: {', '.join(languages)}")

    # Create output directory
    args.output_dir.mkdir(parents=True, exist_ok=True)

    # Build languages
    results = []
    if args.parallel > 1:
        with ThreadPoolExecutor(max_workers=args.parallel) as executor:
            futures = {
                executor.submit(build_language, lang, SUPPORTED_LANGUAGES[lang], args.output_dir): lang
                for lang in languages
            }
            for future in as_completed(futures):
                results.append(future.result())
    else:
        for lang in languages:
            results.append(build_language(lang, SUPPORTED_LANGUAGES[lang], args.output_dir))

    # Copy to bundle if requested
    if args.bundle_dir:
        copy_to_bundle(results, args.bundle_dir)

    # Summary
    print(f"\n{'='*60}")
    print("BUILD SUMMARY")
    print('='*60)

    success_count = sum(1 for r in results if r['success'])
    print(f"Built: {success_count}/{len(languages)} languages\n")

    for r in results:
        status = "✓" if r['success'] else "✗"
        details = []
        if r['dictionary'] and r['dictionary'].exists():
            details.append(f"dict={r['dictionary'].stat().st_size / 1024 / 1024:.1f}MB")
        if r.get('prefix_boost') and r['prefix_boost'].exists():
            details.append(f"boost={r['prefix_boost'].stat().st_size / 1024:.0f}KB")
        detail_str = f" ({', '.join(details)})" if details else ""
        print(f"  {status} {r['name']} ({r['lang']}){detail_str}")

    print(f"\nOutput directory: {args.output_dir}")
    if args.bundle_dir:
        print(f"Bundle directory: {args.bundle_dir}")

    # Print language pack locations
    print("\nLanguage packs:")
    for r in results:
        if r['langpack'] and r['langpack'].exists():
            print(f"  {r['langpack']}")


if __name__ == '__main__':
    main()

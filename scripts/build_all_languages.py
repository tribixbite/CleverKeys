#!/usr/bin/env python3
"""
Build all supported language dictionaries and packs for CleverKeys.

Since 2026-07 every wordfreq-backed language is built by the one-pass evidence
classifier (`build_wordlist.py` — see its LANG_CONFIG for the per-language
oracle tiers, band boundaries and size caps). This orchestrator runs, per
language:

  1. build_wordlist.py --lang X --write
       → scripts/dictionaries/X/X_words.txt + X_enhanced.bin
       → src/main/assets/dictionaries/X_enhanced.bin (bundled languages)
       (the classifier verifies CKDT magic/version + bin==src word set)
  2. generate_unigrams.py       → scripts/dictionaries/X/unigrams.txt
  3. compute_prefix_boosts.py   → src/main/assets/prefix_boosts/X.{bin,json}
       (Latin-script languages with prefix-boost assets only; reads the
        assets dictionary, so non-bundled languages get a temporary copy)
  4. build_langpack.py          → scripts/dictionaries/langpack-X.zip
       (deterministic zip; manifest version from the table below)

Swahili (sw) is the exception: wordfreq has no sw data, so it keeps the
corpus-file pipeline (scripts/sw_words.txt via parse_swahili_ods.py) and only
steps 2-4 run for it.

English is FROZEN at the 98,140-word artifact — regenerate it only via
`build_wordlist.py --lang en --write` deliberately, never as part of a bulk
run (this script refuses to touch en unless it is the sole --lang argument).

Usage:
    python3 build_all_languages.py --list
    python3 build_all_languages.py --lang fr,de          # specific languages
    python3 build_all_languages.py                       # all except en

Requirements:
    pip install wordfreq nltk pyspellchecker
    hunspell (en_US fr_FR nl_NL ru_RU) + aspell (en_GB de es fr) system dicts

License: Apache-2.0
"""

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent.resolve()
DICT_DIR = SCRIPT_DIR / "dictionaries"
ASSETS_DICT = SCRIPT_DIR.parent / "src/main/assets/dictionaries"
ASSETS_BOOST = SCRIPT_DIR.parent / "src/main/assets/prefix_boosts"

# Required helper scripts (must be in same directory)
REQUIRED_SCRIPTS = [
    'build_wordlist.py',
    'build_dictionary.py',
    'generate_unigrams.py',
    'build_langpack.py',
    'compute_prefix_boosts.py',
]

# Language table. `words` is the NOMINAL shipped size (the classifier's
# --limit where set; survivor-count languages are approximate, marked ~).
# `boost` marks languages with prefix-boost assets (Latin-script only — the
# trie boosts the 26 English NN logits; el/ru/tr ship hasPrefixBoost=false).
# `version` is the langpack manifest version to stamp on the next build.
SUPPORTED_LANGUAGES = {
    'en': {'name': 'English',    'words': 98140, 'bundle': True,  'boost': False, 'version': 2},
    'es': {'name': 'Spanish',    'words': 50000, 'bundle': True,  'boost': True,  'version': 2},
    'fr': {'name': 'French',     'words': 40000, 'bundle': True,  'boost': True,  'version': 2},
    'pt': {'name': 'Portuguese', 'words': 40000, 'bundle': True,  'boost': True,  'version': 2},
    'it': {'name': 'Italian',    'words': 40000, 'bundle': True,  'boost': True,  'version': 2},
    'de': {'name': 'German',     'words': 40000, 'bundle': True,  'boost': True,  'version': 2},
    'sv': {'name': 'Swedish',    'words': 40000, 'bundle': True,  'boost': True,  'version': 2},
    'nl': {'name': 'Dutch',      'words': 40000, 'bundle': False, 'boost': True,  'version': 2},
    'ru': {'name': 'Russian',    'words': 50000, 'bundle': False, 'boost': False, 'version': 2},
    'el': {'name': 'Greek',      'words': 0,     'bundle': False, 'boost': False, 'version': 2},  # ~ survivors of 46,306
    'tr': {'name': 'Turkish',    'words': 40000, 'bundle': False, 'boost': False, 'version': 2},
    'id': {'name': 'Indonesian', 'words': 0,     'bundle': False, 'boost': True,  'version': 2},  # ~ survivors (ceiling 30,718)
    'ms': {'name': 'Malay',      'words': 0,     'bundle': False, 'boost': True,  'version': 2},  # ~ survivors (ceiling 28,361)
    'tl': {'name': 'Tagalog',    'words': 0,     'bundle': False, 'boost': True,  'version': 2},  # ~ survivors (ceiling 29,877)
    # ARC-056 additions (2026-09-01): pack-only, non-Latin script → no boosts.
    'uk': {'name': 'Ukrainian',  'words': 50000, 'bundle': False, 'boost': False, 'version': 1},
    'bg': {'name': 'Bulgarian',  'words': 0,     'bundle': False, 'boost': False, 'version': 1},  # ~ survivors of 35,791
    'mk': {'name': 'Macedonian', 'words': 50000, 'bundle': False, 'boost': False, 'version': 1},
    'he': {'name': 'Hebrew',     'words': 50000, 'bundle': False, 'boost': False, 'version': 1},
    # Swahili uses the wiki-corpus word list (wordfreq has no sw data)
    'sw': {'name': 'Swahili',    'words': 20000, 'bundle': False, 'boost': True,  'version': 2,
           'wordlist': 'sw_words.txt'},
}

# Minimum word count for language detection unigrams
UNIGRAM_COUNT = 5000


def check_required_scripts() -> bool:
    missing = [s for s in REQUIRED_SCRIPTS if not (SCRIPT_DIR / s).exists()]
    if missing:
        print(f"Error: required helper scripts not found in {SCRIPT_DIR}: {', '.join(missing)}")
        return False
    return True


def run(cmd: list, label: str) -> bool:
    """Run a helper script, streaming a compact status; fail loud on error."""
    result = subprocess.run([sys.executable] + cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  ERROR in {label}:\n{result.stdout[-2000:]}\n{result.stderr[-2000:]}")
        return False
    return True


def build_unigrams(lang: str, output_file: Path) -> bool:
    return run([str(SCRIPT_DIR / 'generate_unigrams.py'), '--lang', lang,
                '--output', str(output_file), '--top-n', str(UNIGRAM_COUNT)],
               f"generate_unigrams({lang})")


def build_prefix_boosts(lang: str, dict_file: Path, bundled: bool) -> bool:
    """Regenerate the prefix-boost trie against the FRESH dictionary.

    compute_prefix_boosts.py reads src/main/assets/dictionaries/<lang>_enhanced.bin,
    so non-bundled languages get a temporary copy that is removed afterwards.
    A regenerated dict with a stale prefix_boost is a correctness bug — the
    boosts encode zipf deltas against the dict's own words — so this step is
    NEVER skipped for boost-enabled languages.
    """
    assets_dict_file = ASSETS_DICT / f'{lang}_enhanced.bin'
    temp_copy = not bundled
    if temp_copy:
        ASSETS_DICT.mkdir(parents=True, exist_ok=True)
        shutil.copy2(dict_file, assets_dict_file)
    try:
        ok = run([str(SCRIPT_DIR / 'compute_prefix_boosts.py'), '--langs', lang,
                  '--threshold', '1.5'], f"compute_prefix_boosts({lang})")
    finally:
        if temp_copy and assets_dict_file.exists():
            assets_dict_file.unlink()
    return ok and (ASSETS_BOOST / f'{lang}.bin').exists()


def build_langpack(lang: str, info: dict, dict_file: Path, unigrams_file: Path,
                   output_file: Path) -> bool:
    cmd = [str(SCRIPT_DIR / 'build_langpack.py'),
           '--lang', lang, '--name', info['name'],
           '--dict', str(dict_file),
           '--output', str(output_file),
           '--version', str(info.get('version', 1))]
    if unigrams_file.exists():
        cmd.extend(['--unigrams', str(unigrams_file)])
    return run(cmd, f"build_langpack({lang})")


def build_language(lang: str, info: dict) -> dict:
    """Build all artifacts for a single language (classifier → boosts → pack)."""
    name = info['name']
    result = {'lang': lang, 'name': name, 'success': False,
              'dictionary': None, 'prefix_boost': None, 'langpack': None}

    print(f"\n{'='*60}\nBuilding {name} ({lang})\n{'='*60}")

    lang_dir = DICT_DIR / lang
    lang_dir.mkdir(parents=True, exist_ok=True)

    # Step 1: word list + CKDT V2 dictionary
    legacy_wordlist = info.get('wordlist')
    dict_file = lang_dir / f'{lang}_enhanced.bin'
    if legacy_wordlist:
        # sw: corpus-file pipeline (no wordfreq data → no classifier)
        wordlist_file = SCRIPT_DIR / legacy_wordlist
        if not wordlist_file.exists():
            print(f"  ERROR: word list not found: {wordlist_file}")
            return result
        print(f"  [1/4] Building dictionary from corpus list {legacy_wordlist}...")
        if not run([str(SCRIPT_DIR / 'build_dictionary.py'), '--lang', lang,
                    '--input', str(wordlist_file), '--output', str(dict_file),
                    '--use-wordfreq'], f"build_dictionary({lang})"):
            return result
    else:
        print(f"  [1/4] Evidence classifier (build_wordlist.py --lang {lang} --write)...")
        if not run([str(SCRIPT_DIR / 'build_wordlist.py'), '--lang', lang, '--write'],
                   f"build_wordlist({lang})"):
            return result
    if not dict_file.exists():
        print(f"  ERROR: classifier did not produce {dict_file}")
        return result
    result['dictionary'] = dict_file

    # Step 2: unigrams for language detection
    unigrams_file = lang_dir / 'unigrams.txt'
    print(f"  [2/4] Generating unigrams...")
    if legacy_wordlist:
        # wordfreq falls back to English for sw — use the corpus list head instead.
        lines = [ln for ln in (SCRIPT_DIR / legacy_wordlist).read_text(encoding='utf-8')
                 .splitlines() if ln.strip()][:UNIGRAM_COUNT]
        unigrams_file.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    else:
        build_unigrams(lang, unigrams_file)

    # Step 3: prefix boosts (fresh dict → fresh trie; see build_prefix_boosts doc)
    if info.get('boost'):
        print(f"  [3/4] Regenerating prefix boosts...")
        if build_prefix_boosts(lang, dict_file, info.get('bundle', False)):
            result['prefix_boost'] = ASSETS_BOOST / f'{lang}.bin'
        else:
            print(f"  ERROR: prefix boosts failed for {lang}")
            return result
    else:
        print(f"  [3/4] No prefix boosts for {lang} (non-Latin script or en base)")

    # Step 4: language pack
    langpack_file = DICT_DIR / f'langpack-{lang}.zip'
    print(f"  [4/4] Building language pack (manifest v{info.get('version', 1)})...")
    if build_langpack(lang, info, dict_file, unigrams_file, langpack_file):
        result['langpack'] = langpack_file

    result['success'] = result['langpack'] is not None
    dict_size = dict_file.stat().st_size / 1024 / 1024
    print(f"\n  {'✓' if result['success'] else '✗'} {name}: dictionary={dict_size:.1f}MB")
    return result


def main():
    parser = argparse.ArgumentParser(
        description='Build all language dictionaries for CleverKeys',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument('--lang', type=str,
                        help='Comma-separated list of languages to build (default: all except en)')
    parser.add_argument('--list', action='store_true',
                        help='List supported languages and exit')
    args = parser.parse_args()

    if args.list:
        print("Supported languages:")
        for code, info in SUPPORTED_LANGUAGES.items():
            marks = "".join([" [BUNDLE]" if info.get('bundle') else "",
                             " [BOOST]" if info.get('boost') else ""])
            words = info['words'] or '~survivors'
            print(f"  {code}: {info['name']} ({words} words){marks}")
        return

    if not check_required_scripts():
        sys.exit(1)
    try:
        import wordfreq  # noqa: F401
    except ImportError:
        sys.exit("Error: wordfreq not installed. Run: pip install wordfreq")

    if args.lang:
        languages = [l.strip() for l in args.lang.split(',')]
        for l in languages:
            if l not in SUPPORTED_LANGUAGES:
                sys.exit(f"Error: unknown language '{l}'. Supported: "
                         f"{', '.join(SUPPORTED_LANGUAGES)}")
    else:
        # en is frozen — bulk runs never regenerate it (see module docstring).
        languages = [l for l in SUPPORTED_LANGUAGES if l != 'en']
    if 'en' in languages and len(languages) > 1:
        sys.exit("Error: en is frozen; build it alone (--lang en) and on purpose.")

    print(f"Building {len(languages)} languages: {', '.join(languages)}")

    results = [build_language(lang, SUPPORTED_LANGUAGES[lang]) for lang in languages]

    print(f"\n{'='*60}\nBUILD SUMMARY\n{'='*60}")
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

    print("\nLanguage packs:")
    for r in results:
        if r['langpack'] and r['langpack'].exists():
            print(f"  {r['langpack']}")
    if success_count != len(languages):
        sys.exit(1)


if __name__ == '__main__':
    main()

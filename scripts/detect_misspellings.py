#!/usr/bin/env python3
"""
CleverKeys Dictionary Misspelling Detection Pipeline

Detects likely misspellings in the English dictionary by combining:
1. Multi-source whitelist (NLTK, pyspellchecker, hunspell, British English rules)
2. Contraction and possessive pattern recognition
3. Edit-distance-1 pattern matching against known-good words
4. Foreign language frequency filtering via wordfreq
5. Zipf frequency gap analysis

Usage:
    python3 detect_misspellings.py [--dict PATH] [--output PATH] [--min-gap FLOAT]

Dependencies:
    pip install wordfreq spellchecker nltk metaphone
    python3 -c "import nltk; nltk.download('words'); nltk.download('names')"
    # hunspell with en_US dictionary must be installed

Output categories:
    - WRONG PLURALS: Words ending in 's' that should be possessive/plural form
    - REVIEW CANDIDATES: Possible misspellings sorted by confidence (freq gap)
    - FOREIGN WORDS: High-frequency words in other languages (auto-whitelisted)
"""

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

# Third-party imports with helpful error messages
try:
    from wordfreq import zipf_frequency, top_n_list
except ImportError:
    sys.exit("Missing: pip install wordfreq")

try:
    from spellchecker import SpellChecker
except ImportError:
    sys.exit("Missing: pip install pyspellchecker")

try:
    from nltk.corpus import words as nltk_words, names as nltk_names
except ImportError:
    sys.exit("Missing: pip install nltk && python3 -c \"import nltk; nltk.download('words'); nltk.download('names')\"")


SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
DEFAULT_DICT = SCRIPT_DIR / "dictionaries" / "en" / "en_words.txt"
DEFAULT_CONTRACTIONS = PROJECT_ROOT / "src" / "main" / "assets" / "dictionaries" / "contractions_non_paired.json"
DEFAULT_OUTPUT = SCRIPT_DIR / "misspelling_review.txt"

# British English suffix transformations (British → American)
BRITISH_RULES = {
    'ise': 'ize', 'ised': 'ized', 'ises': 'izes', 'ising': 'izing',
    'isation': 'ization', 'isations': 'izations',
    'our': 'or', 'ours': 'ors', 'oured': 'ored', 'ouring': 'oring',
    'tre': 'ter', 'tres': 'ters', 'ogue': 'og', 'ogues': 'ogs',
    'ence': 'ense', 'yse': 'yze', 'ysed': 'yzed',
    'lled': 'led', 'lling': 'ling', 'mme': 'me',
}

# Languages to check for foreign word filtering
FOREIGN_LANGS = ['fr', 'de', 'es', 'it', 'pt', 'nl', 'sv', 'da', 'nb', 'pl', 'tr', 'id', 'fi', 'cs', 'ro']

# Contraction suffixes that indicate an apostrophe-free contraction
CONTRACTION_SUFFIXES = ['d', 'll', 're', 've', 'nt', 'dve', 'ntve']


def build_whitelist(our_words: list[str]) -> set[str]:
    """Build comprehensive whitelist from multiple English word sources."""
    # NLTK words + names
    whitelist = set(w.lower() for w in nltk_words.words())
    whitelist |= set(w.lower() for w in nltk_names.words())
    print(f"  NLTK words+names: {len(whitelist)}")

    # pyspellchecker's internal dictionary
    spell = SpellChecker()
    pyspell_known = set(spell.word_frequency.words())
    whitelist |= pyspell_known
    print(f"  pyspellchecker dict: {len(pyspell_known)}")

    # hunspell en_US — accepted words (lowercase)
    result = subprocess.run(
        ['hunspell', '-d', 'en_US', '-G'],
        input='\n'.join(our_words),
        capture_output=True, text=True
    )
    hunspell_ok = set(w.strip().lower() for w in result.stdout.strip().split('\n') if w.strip())
    whitelist |= hunspell_ok
    print(f"  hunspell en_US accepted: {len(hunspell_ok)}")

    # hunspell — capitalized forms (proper nouns)
    result2 = subprocess.run(
        ['hunspell', '-d', 'en_US', '-G'],
        input='\n'.join(w.capitalize() for w in our_words),
        capture_output=True, text=True
    )
    hunspell_cap = set(w.strip().lower() for w in result2.stdout.strip().split('\n') if w.strip())
    whitelist |= hunspell_cap
    print(f"  hunspell capitalized: {len(hunspell_cap)}")

    # wordfreq top 5k (definitely real English words)
    top5k = set(top_n_list('en', 5000))
    whitelist |= top5k
    print(f"  wordfreq top 5k: {len(top5k)}")

    # British English spellings detected via suffix rules
    check_set = hunspell_ok | set(w.lower() for w in nltk_words.words()) | pyspell_known
    british_count = 0
    for word in our_words:
        w = word.lower()
        for brit_sfx, us_sfx in BRITISH_RULES.items():
            if w.endswith(brit_sfx) and (w[:-len(brit_sfx)] + us_sfx) in check_set:
                whitelist.add(w)
                british_count += 1
                break
    print(f"  British spellings: {british_count}")

    # Contraction whitelist
    contraction_count = 0
    if DEFAULT_CONTRACTIONS.exists():
        with open(DEFAULT_CONTRACTIONS) as f:
            contraction_keys = set(k.lower() for k in json.load(f).keys())
        whitelist |= contraction_keys
        contraction_count += len(contraction_keys)

    # Detect contraction-like patterns: common_word + suffix
    for word in our_words:
        w = word.lower()
        for sfx in CONTRACTION_SUFFIXES:
            if w.endswith(sfx) and len(w) > len(sfx) + 2:
                base = w[:-len(sfx)]
                if base in whitelist or base in top5k:
                    whitelist.add(w)
                    contraction_count += 1
                    break
    print(f"  Contractions: {contraction_count}")

    # Possessive whitelist: words ending in 's' where base is recognized
    possessive_count = 0
    for word in our_words:
        w = word.lower()
        if w.endswith('s') and len(w) > 3:
            base = w[:-1]
            if base in hunspell_cap or base in set(w.lower() for w in nltk_names.words()):
                whitelist.add(w)
                possessive_count += 1
    print(f"  Possessives: {possessive_count}")

    return whitelist


def find_misspellings(candidates: list[str], known_english: set[str], min_gap: float) -> list[tuple]:
    """Find words within edit distance 1 of a known English word with large frequency gap."""
    results = []

    for word in candidates:
        best_match = None
        best_gap = 0.0
        best_rule = ''
        wfreq = zipf_frequency(word, 'en')

        # Single char substitution
        for i in range(len(word)):
            for c in 'abcdefghijklmnopqrstuvwxyz':
                if c == word[i]:
                    continue
                corrected = word[:i] + c + word[i+1:]
                if corrected in known_english:
                    gap = zipf_frequency(corrected, 'en') - wfreq
                    if gap > best_gap:
                        best_match, best_gap, best_rule = corrected, gap, 'substitution'

        # Single char deletion (word has extra char)
        for i in range(len(word)):
            corrected = word[:i] + word[i+1:]
            if corrected in known_english and len(corrected) >= 2:
                gap = zipf_frequency(corrected, 'en') - wfreq
                if gap > best_gap:
                    best_match, best_gap, best_rule = corrected, gap, 'deletion'

        # Single char insertion (word missing a char)
        for i in range(len(word) + 1):
            for c in 'abcdefghijklmnopqrstuvwxyz':
                corrected = word[:i] + c + word[i:]
                if corrected in known_english:
                    gap = zipf_frequency(corrected, 'en') - wfreq
                    if gap > best_gap:
                        best_match, best_gap, best_rule = corrected, gap, 'insertion'

        # Adjacent transposition
        for i in range(len(word) - 1):
            corrected = word[:i] + word[i+1] + word[i] + word[i+2:]
            if corrected in known_english and corrected != word:
                gap = zipf_frequency(corrected, 'en') - wfreq
                if gap > best_gap:
                    best_match, best_gap, best_rule = corrected, gap, 'transposition'

        # Extra double letter (word has unnecessary double)
        for i in range(len(word) - 1):
            if word[i] == word[i+1]:
                corrected = word[:i] + word[i+1:]
                if corrected in known_english:
                    gap = zipf_frequency(corrected, 'en') - wfreq
                    if gap > best_gap:
                        best_match, best_gap, best_rule = corrected, gap, 'extra_double'

        # Missing double letter
        for i in range(len(word)):
            corrected = word[:i] + word[i] + word[i:]
            if corrected in known_english and corrected != word:
                gap = zipf_frequency(corrected, 'en') - wfreq
                if gap > best_gap:
                    best_match, best_gap, best_rule = corrected, gap, 'missing_double'

        if best_match and best_gap >= min_gap:
            results.append((word, best_match, best_rule, best_gap, wfreq))

    return results


def filter_foreign(results: list[tuple]) -> tuple[list[tuple], list[tuple]]:
    """Remove words that have high frequency in a foreign language."""
    review = []
    foreign = []

    for word, corr, rule, gap, wfreq in results:
        max_foreign = 0.0
        max_lang = ''
        for lang in FOREIGN_LANGS:
            fz = zipf_frequency(word, lang)
            if fz > max_foreign:
                max_foreign = fz
                max_lang = lang

        en_freq = zipf_frequency(word, 'en')
        if max_foreign > 3.0 and max_foreign > en_freq:
            foreign.append((word, corr, max_lang, max_foreign))
        else:
            review.append((word, corr, rule, gap, wfreq))

    return review, foreign


def extract_wrong_plurals(results: list[tuple]) -> tuple[list[tuple], list[str]]:
    """Separate wrong possessives/plurals from other misspelling candidates."""
    wrong_plurals = []
    remaining = []

    for word, corr, rule, gap, wfreq in results:
        if word.endswith('s') and rule == 'deletion' and corr == word[:-1]:
            wrong_plurals.append(word)
        elif word.endswith('s') and len(word) > 4:
            base = word[:-1]
            base_freq = zipf_frequency(base, 'en')
            if base_freq > 2.5 and rule == 'deletion':
                wrong_plurals.append(word)
            else:
                remaining.append((word, corr, rule, gap, wfreq))
        else:
            remaining.append((word, corr, rule, gap, wfreq))

    return remaining, wrong_plurals


def main():
    parser = argparse.ArgumentParser(description='Detect misspellings in CleverKeys dictionary')
    parser.add_argument('--dict', type=Path, default=DEFAULT_DICT,
                        help='Path to word list (one word per line)')
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT,
                        help='Output report path')
    parser.add_argument('--min-gap', type=float, default=1.5,
                        help='Minimum zipf frequency gap to flag (default: 1.5)')
    parser.add_argument('--min-len', type=int, default=4,
                        help='Minimum word length to check (default: 4)')
    args = parser.parse_args()

    start = time.time()

    # Load dictionary
    with open(args.dict) as f:
        our_words = [w.strip() for w in f if w.strip()]
    print(f"Dictionary: {len(our_words)} words")

    # Stage 1: Build whitelist
    print("\n--- Building whitelist ---")
    whitelist = build_whitelist(our_words)
    print(f"MASTER WHITELIST: {len(whitelist)}")

    # Stage 2: Filter candidates
    print("\n--- Filtering candidates ---")
    candidates = []
    for word in our_words:
        w = word.lower().strip()
        if not w or len(w) < args.min_len or not w.isascii() or not w.isalpha():
            continue
        if w not in whitelist:
            candidates.append(w)
    print(f"Candidates after whitelist: {len(candidates)}")

    # Stage 3: Edit distance matching
    print(f"\n--- Edit distance analysis ({len(candidates)} candidates) ---")
    raw_results = find_misspellings(candidates, whitelist, args.min_gap)
    print(f"Raw matches: {len(raw_results)}")

    # Stage 4: Foreign language filter
    print("\n--- Foreign language filter ---")
    filtered, foreign = filter_foreign(raw_results)
    print(f"Foreign words removed: {len(foreign)}")
    print(f"After foreign filter: {len(filtered)}")

    # Stage 5: Separate wrong plurals
    review, wrong_plurals = extract_wrong_plurals(filtered)
    review.sort(key=lambda x: -x[3])
    print(f"Wrong plurals: {len(wrong_plurals)}")
    print(f"Final review candidates: {len(review)}")

    elapsed = time.time() - start

    # Output
    print(f"\n{'='*60}")
    print(f"RESULTS (completed in {elapsed:.1f}s)")
    print(f"{'='*60}")
    print(f"  Dictionary:        {len(our_words)} words")
    print(f"  Wrong plurals:     {len(wrong_plurals)}")
    print(f"  Review candidates: {len(review)}")
    print(f"  Foreign filtered:  {len(foreign)}")

    # Known misspellings verification
    known_bad = {'teh': 'the', 'wich': 'which', 'hav': 'have', 'becuase': 'because'}
    caught = {w for w, *_ in review if w in known_bad}
    missed = set(known_bad) - caught - {w for w in known_bad if len(w) < args.min_len}
    print(f"\n  Known misspellings caught: {caught or 'none in length range'}")
    if missed:
        print(f"  Known misspellings missed: {missed}")

    # Save report
    with open(args.output, 'w') as f:
        f.write("# CleverKeys Dictionary — Misspelling Review\n")
        f.write(f"# Pipeline: whitelist + edit-distance-1 + foreign-language filter\n")
        f.write(f"# Dictionary: {len(our_words)} words | Min gap: {args.min_gap}\n")
        f.write(f"# Generated: {time.strftime('%Y-%m-%d %H:%M')}\n\n")

        f.write(f"## WRONG PLURALS ({len(wrong_plurals)})\n")
        f.write("# Words ending in 's' — malformed possessives or plurals\n")
        f.write("# Action: Remove from dictionary\n")
        for w in sorted(wrong_plurals):
            f.write(f"{w}\n")

        f.write(f"\n## REVIEW CANDIDATES ({len(review)})\n")
        f.write("# Possible misspellings — needs human judgment\n")
        f.write("# Format: word<TAB>correction<TAB>rule<TAB>freq_gap<TAB>word_freq\n")
        for word, corr, rule, gap, wfreq in review:
            f.write(f"{word}\t{corr}\t{rule}\t{gap:.2f}\t{wfreq:.2f}\n")

        f.write(f"\n## FOREIGN WORDS ({len(foreign)})\n")
        f.write("# High frequency in another language — auto-whitelisted\n")
        for word, corr, lang, freq in sorted(foreign, key=lambda x: -x[3]):
            f.write(f"{word}\t{lang}\t{freq:.2f}\n")

    print(f"\nReport saved to {args.output}")


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
Generate comprehensive apostrophe word lists and contraction mappings for all languages.

This script creates:
1. Apostrophe word lists to add to dictionaries
2. Contraction mapping files (apostrophe-free -> with apostrophe)

Languages supported:
- French (FR): Heavy elision (c'est, j'ai, l'homme, qu'il, etc.)
- Italian (IT): Elision (l'uomo, un'amica, c'è, etc.)
- Portuguese (PT): Limited elision (d'água, d'olho, etc.)
- Spanish (ES): Almost none (only a few archaic forms)
- German (DE): Minimal (informal contractions)

Author: CleverKeys v1.1.87
"""

import json
import os
from typing import Dict, List, Set, Tuple

# ============================================================================
# FRENCH APOSTROPHE PATTERNS
# ============================================================================
# French uses elision extensively. The following words drop their final vowel
# before words beginning with a vowel or silent 'h':
# je, me, te, se, le, la, ce, de, ne, que, jusque, lorsque, puisque, quoique

FRENCH_ELISION_PREFIXES = {
    # Word -> (apostrophe form, can elide before)
    "je": ("j'", "vowel_h"),      # j'ai, j'aime, j'habite
    "me": ("m'", "vowel_h"),      # m'a, m'aime
    "te": ("t'", "vowel_h"),      # t'aime, t'a
    "se": ("s'", "vowel_h"),      # s'est, s'appelle
    "le": ("l'", "vowel_h"),      # l'homme, l'amour
    "la": ("l'", "vowel_h"),      # l'eau, l'amie
    "ce": ("c'", "vowel"),        # c'est, c'était (not before h)
    "de": ("d'", "vowel_h"),      # d'accord, d'abord
    "ne": ("n'", "vowel_h"),      # n'est, n'a
    "que": ("qu'", "vowel_h"),    # qu'est, qu'il
    "jusque": ("jusqu'", "vowel_h"),  # jusqu'à, jusqu'ici
    "lorsque": ("lorsqu'", "limited"),  # lorsqu'il, lorsqu'elle
    "puisque": ("puisqu'", "vowel_h"),  # puisqu'il
    "quoique": ("quoiqu'", "vowel_h"),  # quoiqu'il
    "quelque": ("quelqu'", "vowel"),    # quelqu'un
    "presque": ("presqu'", "limited"),  # presqu'île
}

# Common French words that begin with vowels or silent h
# These combine with elision prefixes
FRENCH_VOWEL_STARTERS = [
    # Common verbs (infinitive and conjugated forms)
    "ai", "aie", "aies", "ait", "aient", "avais", "avait", "avaient",
    "aura", "aurai", "auras", "aurait", "aurais", "auront", "auraient",
    "est", "es", "étais", "était", "étaient", "été", "êtes",
    "ont", "eu", "eus", "eut", "eûmes", "eurent",
    "irai", "iras", "ira", "irons", "irez", "iront", "irais", "irait",
    "aime", "aimes", "aiment", "aimais", "aimait", "aimaient",
    "arrive", "arrives", "arrivent", "arrivais", "arrivait",
    "appelle", "appelles", "appellent", "appelais", "appelait",
    "ouvre", "ouvres", "ouvrent", "ouvrais", "ouvrait",
    "entre", "entres", "entrent", "entrais", "entrait",
    "écoute", "écoutes", "écoutent", "écoutais", "écoutait",
    "espère", "espères", "espèrent", "espérais", "espérait",
    "essaie", "essaies", "essaient", "essayais", "essayait",
    "existe", "existes", "existent", "existais", "existait",
    "utilise", "utilises", "utilisent", "utilisais", "utilisait",
    "imagine", "imagines", "imaginent", "imaginais", "imaginait",
    "oublie", "oublies", "oublient", "oubliais", "oubliait",

    # Common nouns (with silent h)
    "homme", "hommes", "heure", "heures", "histoire", "histoires",
    "hôtel", "hôtels", "hôpital", "hôpitaux", "habitude", "habitudes",
    "honneur", "honneurs", "horizon", "horizons", "huile", "huiles",
    "humeur", "humeurs", "humain", "humains", "humaine", "humaines",

    # Common nouns (vowel start)
    "eau", "eaux", "ami", "amie", "amis", "amies", "amour", "amours",
    "an", "ans", "année", "années", "air", "airs", "âge", "âges",
    "argent", "art", "arts", "arbre", "arbres", "animal", "animaux",
    "enfant", "enfants", "école", "écoles", "église", "églises",
    "état", "états", "été", "étés", "étage", "étages",
    "endroit", "endroits", "ennemi", "ennemis", "ensemble",
    "esprit", "esprits", "espoir", "espoirs", "espace", "espaces",
    "exemple", "exemples", "effet", "effets", "effort", "efforts",
    "erreur", "erreurs", "escalier", "escaliers", "événement", "événements",
    "île", "îles", "idée", "idées", "image", "images",
    "instant", "instants", "intérêt", "intérêts",
    "objet", "objets", "occasion", "occasions", "œil", "œuvre", "œuvres",
    "ordre", "ordres", "oreille", "oreilles", "or", "os",
    "ombre", "ombres", "opinion", "opinions",
    "un", "une", "uns", "unes", "autre", "autres",

    # Pronouns
    "il", "ils", "elle", "elles", "on", "en", "y",

    # Adjectives
    "autre", "autres", "ancien", "anciens", "ancienne", "anciennes",
    "unique", "uniques", "utile", "utiles", "urgent", "urgents",
    "important", "importants", "importante", "importantes",
    "impossible", "intéressant", "intéressants",

    # Adverbs and others
    "ici", "ailleurs", "ainsi", "alors", "après", "aussi", "avant",
    "encore", "enfin", "ensemble", "environ", "évidemment",
]

# Fixed French contractions (historical, always written with apostrophe)
FRENCH_FIXED_CONTRACTIONS = {
    # Word without apostrophe -> word with apostrophe
    "aujourdhui": "aujourd'hui",
    "dabord": "d'abord",
    "daccord": "d'accord",
    "dailleurs": "d'ailleurs",
    "dautres": "d'autres",
    "dhabitude": "d'habitude",
    "presquile": "presqu'île",
    "quelquun": "quelqu'un",
    "quelquune": "quelqu'une",
}

# ============================================================================
# ITALIAN APOSTROPHE PATTERNS
# ============================================================================

ITALIAN_ELISION_PREFIXES = {
    "lo": ("l'", "vowel"),       # l'uomo, l'amico
    "la": ("l'", "vowel"),       # l'amica, l'acqua
    "una": ("un'", "vowel"),     # un'amica, un'idea
    "dello": ("dell'", "vowel"), # dell'uomo
    "della": ("dell'", "vowel"), # dell'amica
    "allo": ("all'", "vowel"),   # all'uomo
    "alla": ("all'", "vowel"),   # all'amica
    "nello": ("nell'", "vowel"), # nell'acqua
    "nella": ("nell'", "vowel"), # nell'aria
    "sullo": ("sull'", "vowel"), # sull'argomento
    "sulla": ("sull'", "vowel"), # sull'isola
    "dallo": ("dall'", "vowel"), # dall'alto
    "dalla": ("dall'", "vowel"), # dall'altra
    "questo": ("quest'", "vowel"), # quest'uomo
    "questa": ("quest'", "vowel"), # quest'amica
    "quello": ("quell'", "vowel"), # quell'uomo
    "quella": ("quell'", "vowel"), # quell'amica
    "bello": ("bell'", "vowel"),   # bell'uomo
    "bella": ("bell'", "vowel"),   # bell'amica
    "santo": ("sant'", "vowel"),   # Sant'Antonio
    "santa": ("sant'", "vowel"),   # Sant'Anna
    "come": ("com'", "e"),         # com'è (only before è)
    "dove": ("dov'", "e"),         # dov'è
    "ci": ("c'", "e"),             # c'è, c'era
    "di": ("d'", "vowel"),         # d'accordo, d'oro
}

ITALIAN_VOWEL_STARTERS = [
    # Common verbs
    "è", "era", "erano", "essere",
    "ho", "hai", "ha", "abbiamo", "avete", "hanno",
    "avevo", "avevi", "aveva", "avevamo", "avevate", "avevano",
    "avrò", "avrai", "avrà", "avremo", "avrete", "avranno",

    # Common nouns
    "uomo", "uomini", "amico", "amici", "amica", "amiche",
    "acqua", "aria", "anno", "anni", "amore", "anima", "anime",
    "albero", "alberi", "animale", "animali", "arte", "arti",
    "altro", "altri", "altra", "altre", "ora", "ore",
    "occhio", "occhi", "opera", "opere", "oro", "ordine", "ordini",
    "isola", "isole", "idea", "idee", "italiano", "italiana",
    "estate", "età", "esempio", "esempi", "epoca", "epoche",
    "emozione", "emozioni", "energia", "energie",
    "unica", "unico", "unici", "uniche", "università",

    # Adjectives
    "alto", "alta", "alti", "alte",
    "altro", "altra", "altri", "altre",
    "ultimo", "ultima", "ultimi", "ultime",
    "italiano", "italiana", "italiani", "italiane",
    "europeo", "europea", "europei", "europee",
]

ITALIAN_FIXED_CONTRACTIONS = {
    "ce": "c'è",           # there is
    "cera": "c'era",       # there was
    "cerano": "c'erano",   # there were
    "dove": "dov'è",       # where is
    "come": "com'è",       # how is
    "po": "po'",           # a little (poco truncated)
    "mezzora": "mezz'ora", # half hour
    "tuttaltro": "tutt'altro",
    "senzaltro": "senz'altro",
    "nientaltro": "nient'altro",
    "daccordo": "d'accordo",
    "doro": "d'oro",
    "depoca": "d'epoca",
}

# ============================================================================
# PORTUGUESE APOSTROPHE PATTERNS
# ============================================================================

PORTUGUESE_FIXED_CONTRACTIONS = {
    # Most Portuguese apostrophe words are fixed compound words
    "dagua": "d'água",
    "dangola": "d'angola",
    "dalho": "d'alho",
    "dalva": "d'alva",
    "dolho": "d'olho",
    "douro": "d'ouro",
}

# ============================================================================
# SPANISH (minimal apostrophes - mainly archaic/poetic)
# ============================================================================

SPANISH_FIXED_CONTRACTIONS = {
    # Spanish almost never uses apostrophes, but some informal/dialectal forms exist
    # These are mainly for completeness
}

# ============================================================================
# GERMAN (minimal apostrophes - informal contractions)
# ============================================================================

GERMAN_FIXED_CONTRACTIONS = {
    # German uses apostrophes mainly to mark omitted letters in informal speech
    "gehts": "geht's",      # goes it (how's it going)
    "gibts": "gibt's",      # gives it (there is)
    "hats": "hat's",        # has it
    "wies": "wie's",        # how it
    "wenns": "wenn's",      # if it
    "obs": "ob's",          # whether it
    "ans": "an's",          # to the
    "aufs": "auf's",        # on the
    "durchs": "durch's",    # through the
    "fürs": "für's",        # for the
    "hinters": "hinter's",  # behind the
    "ins": "in's",          # into the (note: standard is "ins" without apostrophe)
    "übers": "über's",      # over the
    "ums": "um's",          # around the
    "unters": "unter's",    # under the
    "vors": "vor's",        # before the
}

# ============================================================================
# GENERATION FUNCTIONS
# ============================================================================

def generate_french_apostrophe_words() -> Tuple[Set[str], Dict[str, str]]:
    """
    Generate comprehensive French apostrophe word list and contraction mappings.

    Returns:
        Tuple of (apostrophe_words, contraction_map)
    """
    apostrophe_words = set()
    contraction_map = {}  # without apostrophe -> with apostrophe

    # Generate elision combinations
    for prefix, (apostrophe_form, elision_type) in FRENCH_ELISION_PREFIXES.items():
        for starter in FRENCH_VOWEL_STARTERS:
            # Check if this combination is valid
            first_char = starter[0].lower() if starter else ''
            is_vowel = first_char in 'aeiouyàâäéèêëïîôùûüœæ'
            is_h = first_char == 'h'

            valid = False
            if elision_type == "vowel_h":
                valid = is_vowel or is_h
            elif elision_type == "vowel":
                valid = is_vowel
            elif elision_type == "limited":
                # Only with il, elle, on, un, une, en
                valid = starter.lower() in ["il", "ils", "elle", "elles", "on", "un", "une", "en"]
            elif elision_type == "e":
                valid = first_char == 'e' or first_char == 'é' or first_char == 'è'

            if valid:
                with_apostrophe = f"{apostrophe_form}{starter}"
                without_apostrophe = f"{prefix}{starter}".replace("'", "")

                apostrophe_words.add(with_apostrophe)
                contraction_map[without_apostrophe.lower()] = with_apostrophe.lower()

    # Add fixed contractions
    for without, with_apo in FRENCH_FIXED_CONTRACTIONS.items():
        apostrophe_words.add(with_apo)
        contraction_map[without] = with_apo

    return apostrophe_words, contraction_map


def generate_italian_apostrophe_words() -> Tuple[Set[str], Dict[str, str]]:
    """
    Generate comprehensive Italian apostrophe word list and contraction mappings.
    """
    apostrophe_words = set()
    contraction_map = {}

    for prefix, (apostrophe_form, elision_type) in ITALIAN_ELISION_PREFIXES.items():
        for starter in ITALIAN_VOWEL_STARTERS:
            first_char = starter[0].lower() if starter else ''
            is_vowel = first_char in 'aeiouàèéìòóù'

            valid = False
            if elision_type == "vowel":
                valid = is_vowel
            elif elision_type == "e":
                valid = first_char == 'e' or first_char == 'è' or first_char == 'é'

            if valid:
                with_apostrophe = f"{apostrophe_form}{starter}"
                without_apostrophe = f"{prefix}{starter}".replace("'", "")

                apostrophe_words.add(with_apostrophe)
                contraction_map[without_apostrophe.lower()] = with_apostrophe.lower()

    # Add fixed contractions
    for without, with_apo in ITALIAN_FIXED_CONTRACTIONS.items():
        apostrophe_words.add(with_apo)
        contraction_map[without] = with_apo

    return apostrophe_words, contraction_map


def generate_portuguese_apostrophe_words() -> Tuple[Set[str], Dict[str, str]]:
    """
    Generate Portuguese apostrophe word list (limited).
    """
    apostrophe_words = set()
    contraction_map = {}

    for without, with_apo in PORTUGUESE_FIXED_CONTRACTIONS.items():
        apostrophe_words.add(with_apo)
        contraction_map[without] = with_apo

    return apostrophe_words, contraction_map


def generate_german_apostrophe_words() -> Tuple[Set[str], Dict[str, str]]:
    """
    Generate German apostrophe word list (informal contractions).
    """
    apostrophe_words = set()
    contraction_map = {}

    for without, with_apo in GERMAN_FIXED_CONTRACTIONS.items():
        apostrophe_words.add(with_apo)
        contraction_map[without] = with_apo

    return apostrophe_words, contraction_map


def save_contraction_mappings(lang_code: str, contraction_map: Dict[str, str], output_dir: str):
    """
    Save contraction mappings to JSON file for use by OptimizedVocabulary.

    Format: { "without_apostrophe": "with_apostrophe", ... }
    """
    output_path = os.path.join(output_dir, f"contractions_{lang_code}.json")

    # Sort by key for consistent output
    sorted_map = dict(sorted(contraction_map.items()))

    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(sorted_map, f, ensure_ascii=False, indent=2)

    print(f"Saved {len(contraction_map)} contractions to {output_path}")


def save_apostrophe_words(lang_code: str, words: Set[str], output_dir: str):
    """
    Save apostrophe words to text file for dictionary building.
    """
    output_path = os.path.join(output_dir, f"apostrophe_words_{lang_code}.txt")

    sorted_words = sorted(words, key=str.lower)

    with open(output_path, 'w', encoding='utf-8') as f:
        for word in sorted_words:
            f.write(f"{word}\n")

    print(f"Saved {len(words)} apostrophe words to {output_path}")


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    output_dir = os.path.join(script_dir, "apostrophe_data")
    os.makedirs(output_dir, exist_ok=True)

    print("=" * 60)
    print("Generating apostrophe words and contraction mappings")
    print("=" * 60)

    # French
    print("\n[French]")
    fr_words, fr_map = generate_french_apostrophe_words()
    save_apostrophe_words("fr", fr_words, output_dir)
    save_contraction_mappings("fr", fr_map, output_dir)

    # Italian
    print("\n[Italian]")
    it_words, it_map = generate_italian_apostrophe_words()
    save_apostrophe_words("it", it_words, output_dir)
    save_contraction_mappings("it", it_map, output_dir)

    # Portuguese
    print("\n[Portuguese]")
    pt_words, pt_map = generate_portuguese_apostrophe_words()
    save_apostrophe_words("pt", pt_words, output_dir)
    save_contraction_mappings("pt", pt_map, output_dir)

    # German
    print("\n[German]")
    de_words, de_map = generate_german_apostrophe_words()
    save_apostrophe_words("de", de_words, output_dir)
    save_contraction_mappings("de", de_map, output_dir)

    # Spanish (minimal)
    print("\n[Spanish]")
    es_words = set()
    es_map = {}
    save_apostrophe_words("es", es_words, output_dir)
    save_contraction_mappings("es", es_map, output_dir)

    print("\n" + "=" * 60)
    print("Summary:")
    print(f"  French:     {len(fr_words):,} words, {len(fr_map):,} mappings")
    print(f"  Italian:    {len(it_words):,} words, {len(it_map):,} mappings")
    print(f"  Portuguese: {len(pt_words):,} words, {len(pt_map):,} mappings")
    print(f"  German:     {len(de_words):,} words, {len(de_map):,} mappings")
    print(f"  Spanish:    {len(es_words):,} words, {len(es_map):,} mappings")
    print("=" * 60)

    # Print sample of generated words
    print("\nSample French apostrophe words:")
    for word in sorted(list(fr_words))[:20]:
        print(f"  {word}")

    print("\nSample Italian apostrophe words:")
    for word in sorted(list(it_words))[:20]:
        print(f"  {word}")


if __name__ == "__main__":
    main()

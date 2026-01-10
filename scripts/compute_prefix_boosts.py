#!/usr/bin/env python3
"""
Compute language-specific prefix boosts for beam search.

This script analyzes dictionaries to find prefixes that are common in the target
language but rare in English. These prefixes get under-predicted by the
English-trained neural network, so we apply additive boosts to the logits.

Output format: Binary Aho-Corasick trie for O(1) zero-allocation lookups.

Algorithm:
1. Normalize all text to ASCII lowercase (strip accents)
2. Compute conditional continuation probabilities P(next_char | prefix)
3. Calculate log-odds: delta = log(P_target) - log(P_english)
4. Build Aho-Corasick trie with failure links for longest-match backoff
5. Serialize to binary format for memory-mapped loading on Android
"""

import json
import struct
import sys
import os
import unicodedata
from collections import defaultdict, deque
from math import log


def normalize_to_ascii(text: str) -> str:
    """
    Normalize text to ASCII lowercase.
    Strips accents: é→e, ü→u, ñ→n, ç→c, etc.

    This is necessary because the NN only has logits for 26 English letters.
    """
    # NFD decomposition separates base char from combining marks
    normalized = unicodedata.normalize('NFD', text.lower())
    # Remove combining marks (accents)
    ascii_text = ''.join(c for c in normalized if unicodedata.category(c) != 'Mn')
    # Keep only a-z
    return ''.join(c for c in ascii_text if 'a' <= c <= 'z')


def read_binary_dictionary(path: str) -> dict:
    """
    Read CleverKeys binary dictionary format (.bin).
    Returns: dict[word] → frequency (log-scale 0-255)
    """
    words = {}
    try:
        with open(path, 'rb') as f:
            magic = f.read(4)
            if magic != b'CKDT':
                print(f"Warning: Invalid magic in {path}", file=sys.stderr)
                return words

            f.read(2)  # version
            f.read(2)  # lang
            f.read(4 + 4 + 24)  # word count, node count, reserved

            while True:
                len_byte = f.read(1)
                if not len_byte:
                    break
                word_len = len_byte[0]
                if word_len == 0:
                    continue
                word = f.read(word_len).decode('utf-8', errors='ignore')
                freq_byte = f.read(1)
                if not freq_byte:
                    break
                freq = freq_byte[0]
                # Normalize to ASCII for consistency
                normalized = normalize_to_ascii(word)
                if normalized:
                    words[normalized] = max(1, freq)
    except Exception as e:
        print(f"Error reading {path}: {e}", file=sys.stderr)
    return words


def read_json_dictionary(path: str) -> dict:
    """Read JSON dictionary format (word → frequency)."""
    try:
        with open(path, 'r') as f:
            data = json.load(f)
            result = {}
            for word, freq in data.items():
                normalized = normalize_to_ascii(word)
                if normalized:
                    result[normalized] = freq
            return result
    except Exception as e:
        print(f"Error reading {path}: {e}", file=sys.stderr)
        return {}


def compute_prefix_continuations(words: dict, max_prefix_len: int = 4) -> dict:
    """
    Compute conditional continuation counts: P(next_char | prefix).
    Returns: dict[prefix] → dict[next_char] → count
    """
    continuations = defaultdict(lambda: defaultdict(int))

    for word, freq in words.items():
        # Weight by log frequency to prevent common words from dominating
        weight = max(1, int(log(freq + 1) * 10))

        for prefix_len in range(1, max_prefix_len + 1):
            for i in range(len(word) - prefix_len):
                prefix = word[i:i + prefix_len]
                next_char = word[i + prefix_len]
                if 'a' <= next_char <= 'z':
                    continuations[prefix][next_char] += weight

    return continuations


def compute_boost_map(
    target_dict: dict,
    english_dict: dict,
    min_prefix_len: int = 2,
    max_prefix_len: int = 4,
    alpha: float = 1.0,
    min_target_count: int = 5,
    boost_threshold: float = 1.5,
) -> dict:
    """
    Compute boost values for prefixes disadvantaged in English.
    Returns: dict[prefix] → dict[next_char] → boost_value
    """
    target_cont = compute_prefix_continuations(target_dict, max_prefix_len)
    english_cont = compute_prefix_continuations(english_dict, max_prefix_len)

    boosts = {}

    for prefix, target_chars in target_cont.items():
        if len(prefix) < min_prefix_len or len(prefix) > max_prefix_len:
            continue

        english_chars = english_cont.get(prefix, {})
        target_total = sum(target_chars.values()) + 26 * alpha
        english_total = sum(english_chars.values()) + 26 * alpha

        prefix_boosts = {}

        for char, target_count in target_chars.items():
            if target_count < min_target_count:
                continue

            english_count = english_chars.get(char, 0)
            p_target = (target_count + alpha) / target_total
            p_english = (english_count + alpha) / english_total

            delta = log(p_target) - log(p_english)

            if delta > boost_threshold:
                prefix_boosts[char] = round(delta, 4)

        if prefix_boosts:
            boosts[prefix] = prefix_boosts

    return boosts


class TrieNode:
    """Node for Aho-Corasick trie."""
    def __init__(self, node_id: int):
        self.id = node_id
        self.children = {}  # char → node_id
        self.fail = 0       # failure link node_id
        self.boost = 0.0    # boost value at this node


def build_ac_trie(boost_map: dict) -> list:
    """
    Build Aho-Corasick trie from boost map.

    The boost_map has structure: {"ve": {"u": 8.1}, ...}
    This means: when prefix is "ve" and next char is "u", boost by 8.1
    So the boost lives on the node for "veu".
    """
    nodes = [TrieNode(0)]  # Root is node 0

    # Build basic trie structure
    for prefix, char_boosts in boost_map.items():
        # Navigate/create path for prefix
        state = 0
        for char in prefix:
            if char not in nodes[state].children:
                new_id = len(nodes)
                nodes.append(TrieNode(new_id))
                nodes[state].children[char] = new_id
            state = nodes[state].children[char]

        # Create child nodes for each boosted character
        # The boost lives on the CHILD node (prefix + char)
        for char, boost_val in char_boosts.items():
            if char not in nodes[state].children:
                new_id = len(nodes)
                nodes.append(TrieNode(new_id))
                nodes[state].children[char] = new_id

            target_node_id = nodes[state].children[char]
            nodes[target_node_id].boost = boost_val

    print(f"  Trie structure built: {len(nodes)} nodes")

    # Compute failure links (BFS - Aho-Corasick algorithm)
    queue = deque()

    # Initialize depth 1 (children of root)
    for char, child_id in nodes[0].children.items():
        nodes[child_id].fail = 0
        queue.append(child_id)

    while queue:
        u = queue.popleft()

        for char, v in nodes[u].children.items():
            # Find failure link for v
            f = nodes[u].fail
            while f != 0 and char not in nodes[f].children:
                f = nodes[f].fail

            if char in nodes[f].children and nodes[f].children[char] != v:
                nodes[v].fail = nodes[f].children[char]
            else:
                nodes[v].fail = 0

            # Inherit boost from failure link if this node has no boost
            # This enables "longest suffix match" behavior
            if nodes[v].boost == 0.0:
                nodes[v].boost = nodes[nodes[v].fail].boost

            queue.append(v)

    return nodes


def serialize_trie(nodes: list, filename: str):
    """
    Serialize trie to SPARSE binary format.

    Format (little-endian):
    - Header: Magic "PBST" (4) + Version (4) + NodeCount (4) + EdgeCount (4)
    - Node Offsets: (NodeCount + 1) * 4 bytes (int32 array)
    - Edge Keys: EdgeCount bytes (char indices 0-25)
    - Edge Targets: EdgeCount * 4 bytes (int32 array)
    - Failure Links: NodeCount * 4 bytes (int32 array)
    - Boost Values: NodeCount * 4 bytes (float32 array)

    Sparse format reduces file size from ~10MB to ~2.5MB by avoiding
    storing 26 transitions per node when most nodes have only ~4 children.
    """
    # Build sparse edge lists
    node_offsets = []
    edge_keys = []
    edge_targets = []
    current_offset = 0

    for node in nodes:
        node_offsets.append(current_offset)
        # Sort children for deterministic output
        for char in sorted(node.children.keys()):
            child_id = node.children[char]
            edge_keys.append(ord(char) - ord('a'))  # Store as 0-25
            edge_targets.append(child_id)
            current_offset += 1

    # Sentinel offset for last node's range calculation
    node_offsets.append(current_offset)

    node_count = len(nodes)
    edge_count = len(edge_keys)

    with open(filename, 'wb') as f:
        # Header: Magic(4), Version(4), NodeCount(4), EdgeCount(4)
        f.write(b'PBST')
        f.write(struct.pack('<III', 2, node_count, edge_count))  # Version 2 = sparse

        # Node offsets (NodeCount + 1 ints)
        f.write(struct.pack(f'<{len(node_offsets)}i', *node_offsets))

        # Edge keys (EdgeCount bytes)
        f.write(struct.pack(f'<{len(edge_keys)}B', *edge_keys))

        # Edge targets (EdgeCount ints)
        f.write(struct.pack(f'<{len(edge_targets)}i', *edge_targets))

        # Failure links (NodeCount ints)
        for node in nodes:
            f.write(struct.pack('<i', node.fail))

        # Boost values (NodeCount floats)
        for node in nodes:
            f.write(struct.pack('<f', node.boost))

    size_kb = os.path.getsize(filename) / 1024
    avg_edges = edge_count / node_count if node_count > 0 else 0
    print(f"  Serialized: {filename} ({size_kb:.1f} KB, {node_count} nodes, {edge_count} edges, avg {avg_edges:.1f} edges/node)")


def process_language(
    lang_code: str,
    assets_path: str,
    english_dict: dict,
    threshold: float = 1.5,
    output_dir: str = None
):
    """Process a single language and generate binary trie."""
    print(f"\nProcessing {lang_code}...")

    # Find dictionary file
    bin_path = os.path.join(assets_path, f'dictionaries/{lang_code}_enhanced.bin')
    json_path = os.path.join(assets_path, f'dictionaries/{lang_code}_enhanced.json')

    if os.path.exists(bin_path):
        target_dict = read_binary_dictionary(bin_path)
        print(f"  Loaded {len(target_dict)} words from {lang_code}_enhanced.bin")
    elif os.path.exists(json_path):
        target_dict = read_json_dictionary(json_path)
        print(f"  Loaded {len(target_dict)} words from {lang_code}_enhanced.json")
    else:
        print(f"  ERROR: No dictionary found for {lang_code}")
        return False

    # Compute boosts
    boost_map = compute_boost_map(
        target_dict,
        english_dict,
        boost_threshold=threshold
    )

    total_boosts = sum(len(v) for v in boost_map.values())
    print(f"  Computed {total_boosts} boosts across {len(boost_map)} prefixes")

    if total_boosts == 0:
        print(f"  WARNING: No boosts generated for {lang_code}")
        return False

    # Show sample boosts
    if 've' in boost_map:
        print(f"  Sample 've' boosts: {boost_map['ve']}")

    # Build and serialize trie
    nodes = build_ac_trie(boost_map)

    if output_dir is None:
        output_dir = os.path.join(assets_path, 'prefix_boosts')
    os.makedirs(output_dir, exist_ok=True)

    output_path = os.path.join(output_dir, f'{lang_code}.bin')
    serialize_trie(nodes, output_path)

    # Also save JSON for debugging (optional)
    json_output = os.path.join(output_dir, f'{lang_code}.json')
    with open(json_output, 'w') as f:
        json.dump({
            'version': 2,
            'format': 'aho-corasick-trie',
            'target_language': lang_code,
            'threshold': threshold,
            'node_count': len(nodes),
            'boost_count': total_boosts,
            'boosts': boost_map
        }, f, indent=2)
    print(f"  Debug JSON: {json_output}")

    return True


def main():
    import argparse

    parser = argparse.ArgumentParser(description='Compute prefix boosts (binary trie format)')
    parser.add_argument('--langs', default='de,es,fr,it,pt', help='Comma-separated language codes')
    parser.add_argument('--threshold', type=float, default=1.5, help='Boost threshold (default 1.5)')
    parser.add_argument('--alpha', type=float, default=1.0, help='Smoothing parameter')

    args = parser.parse_args()

    base_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    assets_path = os.path.join(base_path, 'src/main/assets')

    # Load English dictionary (base language)
    print("Loading English dictionary...")
    en_json = os.path.join(assets_path, 'dictionaries/en_enhanced.json')
    en_bin = os.path.join(assets_path, 'dictionaries/en_enhanced.bin')

    if os.path.exists(en_json):
        english_dict = read_json_dictionary(en_json)
    elif os.path.exists(en_bin):
        english_dict = read_binary_dictionary(en_bin)
    else:
        print("ERROR: English dictionary not found")
        sys.exit(1)

    print(f"  Loaded {len(english_dict)} English words")

    # Process each language
    languages = [l.strip() for l in args.langs.split(',')]
    success_count = 0

    for lang in languages:
        if lang == 'en':
            print(f"\nSkipping English (base language)")
            continue

        if process_language(lang, assets_path, english_dict, args.threshold):
            success_count += 1

    print(f"\n{'='*50}")
    print(f"Generated prefix boosts for {success_count}/{len(languages)} languages")
    print(f"Output directory: {os.path.join(assets_path, 'prefix_boosts')}")


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
CLI test for CleverKeys neural swipe prediction
Tests model loading and basic inference without browser
"""

import json
import numpy as np
import onnxruntime as ort
from pathlib import Path

# Constants matching web demo and model_config.json
MAX_SEQUENCE_LENGTH = 150  # Model internal expectation
DECODER_SEQ_LENGTH = 20    # Model expects 20 for decoder target_tokens
NORMALIZED_WIDTH = 360     # From model_config.json
NORMALIZED_HEIGHT = 215    # From model_config.json

# QWERTY layout for coordinate mapping (web demo keyboard)
KEYBOARD_LAYOUT = [
    ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
    ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'],
    ['z', 'x', 'c', 'v', 'b', 'n', 'm']
]

# Key dimensions scaled to normalized space
KEY_WIDTH = 36    # 360 / 10 keys
KEY_HEIGHT = 70   # ~215 / 3 rows
ROW_OFFSETS = [0, 18, 54]  # Indentation for rows 2 and 3

def build_key_positions():
    """Build map of key -> (x, y) coordinates"""
    positions = {}
    for row_idx, row in enumerate(KEYBOARD_LAYOUT):
        for col_idx, key in enumerate(row):
            x = ROW_OFFSETS[row_idx] + col_idx * KEY_WIDTH + KEY_WIDTH / 2
            y = row_idx * KEY_HEIGHT + KEY_HEIGHT / 2
            positions[key] = (x, y)
    return positions

KEY_POSITIONS = build_key_positions()


def load_tokenizer(path: Path) -> dict:
    """Load tokenizer config"""
    with open(path) as f:
        return json.load(f)


def load_vocabulary(path: Path) -> dict:
    """Load vocabulary"""
    with open(path) as f:
        return json.load(f)


def generate_swipe_path(word: str) -> list:
    """Generate synthetic swipe path for a word"""
    path = []
    chars = list(word.lower())

    for i, char in enumerate(chars):
        if char not in KEY_POSITIONS:
            continue
        pos = KEY_POSITIONS[char]

        # Add intermediate point for smooth swipe
        if i > 0 and path:
            prev = path[-1]
            mid_x = (prev['x'] + pos[0]) / 2
            mid_y = (prev['y'] + pos[1]) / 2
            path.append({'x': mid_x, 'y': mid_y, 'key': None, 'timestamp': i * 50 - 25})

        path.append({'x': pos[0], 'y': pos[1], 'key': char, 'timestamp': i * 50})

    return path


def prepare_features(path: list, tokenizer: dict) -> dict:
    """Prepare model input features from swipe path"""
    trajectory = np.zeros((1, MAX_SEQUENCE_LENGTH, 6), dtype=np.float32)
    nearest_keys = np.zeros((1, MAX_SEQUENCE_LENGTH), dtype=np.int64)
    src_mask = np.zeros((1, MAX_SEQUENCE_LENGTH), dtype=bool)

    char_to_idx = tokenizer['char_to_idx']

    for i, point in enumerate(path[:MAX_SEQUENCE_LENGTH]):
        # Normalized coordinates
        trajectory[0, i, 0] = point['x'] / NORMALIZED_WIDTH
        trajectory[0, i, 1] = point['y'] / NORMALIZED_HEIGHT

        # Velocity
        if i > 0:
            prev = path[i - 1]
            trajectory[0, i, 2] = (point['x'] - prev['x']) / NORMALIZED_WIDTH
            trajectory[0, i, 3] = (point['y'] - prev['y']) / NORMALIZED_HEIGHT

        # Pressure and size (dummy)
        trajectory[0, i, 4] = 0.5
        trajectory[0, i, 5] = 0.1

        # Nearest key index
        key = point.get('key')
        nearest_keys[0, i] = char_to_idx.get(key, 0) if key else 0

        src_mask[0, i] = True

    return {
        'trajectory': trajectory,
        'nearest_keys': nearest_keys,
        'src_mask': src_mask
    }


def decode_tokens(token_ids: list, tokenizer: dict) -> str:
    """Decode token IDs to string"""
    idx_to_char = tokenizer['idx_to_char']
    special = tokenizer['special_tokens']
    eos_id = special['eos_idx']
    pad_id = special['pad_idx']
    sos_id = special['sos_idx']

    result = []
    for tid in token_ids:
        if tid == eos_id:
            break
        if tid in (pad_id, sos_id):
            continue
        char = idx_to_char.get(str(tid), '')
        if char and not char.startswith('<'):  # Skip special tokens
            result.append(char)

    return ''.join(result)


def greedy_decode(encoder_session, decoder_session, features: dict, tokenizer: dict, max_len: int = 15) -> str:
    """Run greedy decoding to get prediction"""
    # Encoder forward pass
    encoder_inputs = {
        'trajectory_features': features['trajectory'],
        'nearest_keys': features['nearest_keys'],
        'src_mask': features['src_mask']
    }
    encoder_outputs = encoder_session.run(None, encoder_inputs)
    memory = encoder_outputs[0]  # [1, seq_len, hidden_dim]

    # Decoder greedy search
    special = tokenizer['special_tokens']
    sos_id = special['sos_idx']  # Start of sequence
    eos_id = special['eos_idx']  # End of sequence
    vocab_size = tokenizer['vocab_size']

    current_tokens = [sos_id]

    for step in range(max_len):
        # Prepare decoder input
        padded_tokens = np.zeros((1, DECODER_SEQ_LENGTH), dtype=np.int64)
        # target_mask: False=real tokens, True=padded positions
        tgt_mask = np.ones((1, DECODER_SEQ_LENGTH), dtype=bool)  # Start all True (padded)

        for i, tok in enumerate(current_tokens[:DECODER_SEQ_LENGTH]):
            padded_tokens[0, i] = tok
            tgt_mask[0, i] = False  # Mark as real token (not padded)

        # Decoder src_mask: False=attend, True=ignore (inverted from encoder)
        # Web demo fills with 0 (False) meaning "attend to all encoder positions"
        src_mask_decoder = np.zeros((1, memory.shape[1]), dtype=bool)

        decoder_inputs = {
            'memory': memory,
            'target_tokens': padded_tokens,
            'target_mask': tgt_mask,
            'src_mask': src_mask_decoder
        }

        decoder_outputs = decoder_session.run(None, decoder_inputs)
        logits = decoder_outputs[0]  # [1, seq_len, vocab_size]

        # Get next token (greedy)
        pos = len(current_tokens) - 1
        next_token_logits = logits[0, pos, :]
        next_token = int(np.argmax(next_token_logits))

        if next_token == eos_id:
            break

        current_tokens.append(next_token)

    return decode_tokens(current_tokens, tokenizer)


def main():
    print("\n🔧 CleverKeys CLI Test (Python)\n")

    script_dir = Path(__file__).parent

    try:
        # Load configs
        print("Loading tokenizer...")
        tokenizer = load_tokenizer(script_dir / 'tokenizer_config.json')
        print(f"✓ Tokenizer loaded: {len(tokenizer['char_to_idx'])} chars")

        print("Loading vocabulary...")
        vocab = load_vocabulary(script_dir / 'swipe_vocabulary.json')
        word_count = vocab.get('metadata', {}).get('total_words', len(vocab.get('words', {})))
        print(f"✓ Vocabulary loaded: {word_count} words")

        # Load ONNX models
        print("\nLoading ONNX models...")
        sess_options = ort.SessionOptions()
        sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

        print("  Loading encoder (5.2MB)...")
        encoder_path = str(script_dir / 'swipe_model_character_quant.onnx')
        encoder_session = ort.InferenceSession(encoder_path, sess_options)
        print(f"✓ Encoder loaded. Inputs: {[i.name for i in encoder_session.get_inputs()]}")

        print("  Loading decoder (7.1MB)...")
        decoder_path = str(script_dir / 'swipe_decoder_character_quant.onnx')
        decoder_session = ort.InferenceSession(decoder_path, sess_options)
        print(f"✓ Decoder loaded. Inputs: {[i.name for i in decoder_session.get_inputs()]}")

        # Test words
        test_words = ['hello', 'world', 'the', 'quick', 'typing']

        print("\n📝 Running inference tests:\n")

        correct = 0
        for word in test_words:
            path = generate_swipe_path(word)
            features = prepare_features(path, tokenizer)
            prediction = greedy_decode(encoder_session, decoder_session, features, tokenizer)

            match = '✓' if prediction == word else '✗'
            if prediction == word:
                correct += 1
            print(f"  Swipe \"{word}\" → predicted \"{prediction}\" {match}")

        print(f"\n✅ CLI test completed: {correct}/{len(test_words)} correct\n")

    except Exception as e:
        print(f"\n❌ Test failed: {e}")
        import traceback
        traceback.print_exc()
        exit(1)


if __name__ == '__main__':
    main()

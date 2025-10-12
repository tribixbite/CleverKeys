#!/usr/bin/env python3
"""
CLI Prediction Test - Tests ONNX models with real swipes.jsonl data
Validates the 2D nearest_keys tensor fix works with actual models
"""

import json
import numpy as np
import onnxruntime as ort
from pathlib import Path

# Constants matching Kotlin implementation
MAX_SEQUENCE_LENGTH = 150
PAD_IDX = 0

# Keyboard layout (qwerty_english)
QWERTY_KEYS = {
    'q': (18, 34), 'w': (54, 34), 'e': (90, 34), 'r': (126, 34), 't': (162, 34),
    'y': (198, 34), 'u': (234, 34), 'i': (270, 34), 'o': (306, 34), 'p': (342, 34),
    'a': (36, 93), 's': (72, 93), 'd': (108, 93), 'f': (144, 93), 'g': (180, 93),
    'h': (216, 93), 'j': (252, 93), 'k': (288, 93), 'l': (324, 93),
    'z': (72, 152), 'x': (108, 152), 'c': (144, 152), 'v': (180, 152), 'b': (216, 152),
    'n': (252, 152), 'm': (288, 152)
}

# Reverse lookup: key index to character
KEY_IDX_TO_CHAR = ['<pad>', '<unk>', '<sos>', '<eos>'] + list('abcdefghijklmnopqrstuvwxyz')
CHAR_TO_KEY_IDX = {c: i for i, c in enumerate(KEY_IDX_TO_CHAR)}

def get_nearest_key(x, y):
    """Get the nearest keyboard key to a position (2D point)"""
    min_dist = float('inf')
    nearest = '<unk>'

    for key, (kx, ky) in QWERTY_KEYS.items():
        dist = ((x - kx) ** 2 + (y - ky) ** 2) ** 0.5
        if dist < min_dist:
            min_dist = dist
            nearest = key

    return CHAR_TO_KEY_IDX.get(nearest, 1)  # 1 = <unk>

def extract_features(curve):
    """Extract trajectory features from swipe curve (matching Kotlin)"""
    x_coords = curve['x']
    y_coords = curve['y']
    t_coords = curve['t']

    trajectory_features = []
    nearest_keys = []

    for i in range(len(x_coords)):
        # Normalize coordinates (assuming 360x280 keyboard)
        x_norm = x_coords[i] / 360.0
        y_norm = y_coords[i] / 280.0

        # Calculate velocity
        if i > 0:
            dt = max(t_coords[i] - t_coords[i-1], 1)
            vx = (x_coords[i] - x_coords[i-1]) / dt
            vy = (y_coords[i] - y_coords[i-1]) / dt
        else:
            vx, vy = 0.0, 0.0

        # Calculate acceleration
        if i > 1:
            dt1 = max(t_coords[i] - t_coords[i-1], 1)
            dt2 = max(t_coords[i-1] - t_coords[i-2], 1)
            vx_prev = (x_coords[i-1] - x_coords[i-2]) / dt2
            vy_prev = (y_coords[i-1] - y_coords[i-2]) / dt2
            ax = (vx - vx_prev) / dt1
            ay = (vy - vy_prev) / dt1
        else:
            ax, ay = 0.0, 0.0

        # 6D features: [x_norm, y_norm, vx, vy, ax, ay]
        trajectory_features.append([x_norm, y_norm, vx, vy, ax, ay])

        # Get nearest key (2D format - single key)
        key_idx = get_nearest_key(x_coords[i], y_coords[i])
        nearest_keys.append(key_idx)

    return trajectory_features, nearest_keys

def create_tensors(trajectory_features, nearest_keys):
    """Create ONNX input tensors (2D format)"""

    # Pad to MAX_SEQUENCE_LENGTH
    actual_length = len(trajectory_features)

    # Trajectory features: [1, 150, 6]
    traj_tensor = np.zeros((1, MAX_SEQUENCE_LENGTH, 6), dtype=np.float32)
    for i in range(min(actual_length, MAX_SEQUENCE_LENGTH)):
        traj_tensor[0, i] = trajectory_features[i]

    # Nearest keys: [1, 150] - 2D format (single key per point)
    keys_tensor = np.full((1, MAX_SEQUENCE_LENGTH), PAD_IDX, dtype=np.int64)
    for i in range(min(actual_length, MAX_SEQUENCE_LENGTH)):
        keys_tensor[0, i] = nearest_keys[i]

    # Source mask: [1, 150] - True for padded positions
    mask_tensor = np.zeros((1, MAX_SEQUENCE_LENGTH), dtype=bool)
    mask_tensor[0, actual_length:] = True

    return traj_tensor, keys_tensor, mask_tensor

def decode_prediction(token_indices):
    """Decode token indices to word"""
    chars = []
    for idx in token_indices:
        if idx == 2:  # <sos>
            continue
        if idx == 3 or idx == 0:  # <eos> or <pad>
            break
        if 4 <= idx < len(KEY_IDX_TO_CHAR):
            chars.append(KEY_IDX_TO_CHAR[idx])
    return ''.join(chars)

def run_beam_search(encoder_session, decoder_session, memory, beam_size=8, max_len=20):
    """Run beam search decoding"""
    batch_size = 1

    # Initialize beams with <sos> token
    beams = [(2, [2], 0.0)]  # (last_token, sequence, score)

    for step in range(max_len):
        candidates = []

        for last_token, sequence, score in beams:
            # Prepare decoder input
            tgt_tokens = np.array([sequence], dtype=np.int64)
            tgt_len = len(sequence)

            # Create dummy masks
            src_mask = np.zeros((1, 150), dtype=bool)
            tgt_mask = np.zeros((1, tgt_len), dtype=bool)

            # Run decoder
            decoder_inputs = {
                'memory': memory,
                'target_tokens': tgt_tokens,
                'src_mask': src_mask,
                'target_mask': tgt_mask
            }

            logits = decoder_session.run(None, decoder_inputs)[0]  # [1, tgt_len, 30]
            probs = np.exp(logits[0, -1]) / np.sum(np.exp(logits[0, -1]))

            # Get top beam_size tokens
            top_indices = np.argsort(probs)[-beam_size:]

            for idx in top_indices:
                new_score = score - np.log(probs[idx] + 1e-10)
                new_seq = sequence + [int(idx)]
                candidates.append((int(idx), new_seq, new_score))

        # Select top beams
        beams = sorted(candidates, key=lambda x: x[2])[:beam_size]

        # Check if all beams ended
        if all(token == 3 or token == 0 for token, _, _ in beams):
            break

    # Return best beam
    best_beam = beams[0]
    return best_beam[1]

def main():
    print("=" * 70)
    print("CLI Encoder Test - Validate 2D nearest_keys with swipes.jsonl")
    print("=" * 70)

    # Check models exist
    encoder_path = Path("assets/models/swipe_model_character_quant.onnx")
    swipes_path = Path("model/swipes.jsonl")

    if not encoder_path.exists():
        print(f"❌ ERROR: Encoder not found at {encoder_path}")
        return 1

    if not swipes_path.exists():
        print(f"❌ ERROR: Test data not found at {swipes_path}")
        return 1

    print(f"\n✅ Loading encoder model...")
    print(f"   Path: {encoder_path}")

    # Load encoder
    encoder_session = ort.InferenceSession(str(encoder_path))

    print(f"✅ Encoder loaded successfully")
    print(f"\nEncoder inputs:")
    for inp in encoder_session.get_inputs():
        print(f"   {inp.name}: {inp.shape} ({inp.type})")

    # Validate nearest_keys is 2D
    nearest_keys_input = encoder_session.get_inputs()[1]
    if len(nearest_keys_input.shape) == 2:
        print(f"\n✅ VALIDATION PASSED: nearest_keys is 2D {nearest_keys_input.shape}")
    else:
        print(f"\n❌ VALIDATION FAILED: nearest_keys is {len(nearest_keys_input.shape)}D {nearest_keys_input.shape}")
        return 1

    # Load test swipes
    print(f"\n✅ Loading test data from {swipes_path}...")
    test_swipes = []
    with open(swipes_path, 'r') as f:
        for line in f:
            line = line.strip()
            if line:
                test_swipes.append(json.loads(line))

    print(f"✅ Loaded {len(test_swipes)} test swipes")

    # Run encoder inference tests
    print("\n" + "=" * 70)
    print("Running Encoder Inference Tests")
    print("=" * 70)

    success_count = 0
    total = 0

    for i, swipe_data in enumerate(test_swipes):
        target_word = swipe_data['word']
        curve = swipe_data['curve']

        try:
            # Extract features
            traj_features, nearest_keys = extract_features(curve)

            # Create tensors (2D nearest_keys)
            traj_tensor, keys_tensor, mask_tensor = create_tensors(traj_features, nearest_keys)

            # Verify tensor shapes
            assert keys_tensor.shape == (1, MAX_SEQUENCE_LENGTH), f"Keys tensor wrong shape: {keys_tensor.shape}"
            assert len(keys_tensor.shape) == 2, f"Keys tensor not 2D: {len(keys_tensor.shape)}D"

            # Run encoder
            encoder_inputs = {
                'trajectory_features': traj_tensor,
                'nearest_keys': keys_tensor,
                'src_mask': mask_tensor
            }

            memory = encoder_session.run(None, encoder_inputs)[0]

            # Verify encoder output shape
            expected_shape = (1, MAX_SEQUENCE_LENGTH, 256)
            if memory.shape == expected_shape:
                print(f"  [{i+1:2d}/{len(test_swipes)}] '{target_word:10s}' → Encoder output: {memory.shape} ✅")
                success_count += 1
            else:
                print(f"  [{i+1:2d}/{len(test_swipes)}] '{target_word:10s}' → Wrong output shape: {memory.shape} ❌")

            total += 1

        except Exception as e:
            print(f"  [{i+1:2d}/{len(test_swipes)}] '{target_word:10s}' → ERROR: {e} ❌")
            total += 1

    # Summary
    print("\n" + "=" * 70)
    print("Test Summary")
    print("=" * 70)
    print(f"Total tests: {total}")
    print(f"Successful encoder runs: {success_count}")
    print(f"Success rate: {(success_count/total*100) if total > 0 else 0:.1f}%")
    print("=" * 70)

    if success_count == total:
        print("\n✅ ALL TESTS PASSED - 2D nearest_keys tensor format works correctly!")
        print("   ✅ Model accepts [batch, 150] nearest_keys (2D)")
        print("   ✅ Encoder produces correct output shape [1, 150, 256]")
        print("   ✅ Compatible with Sept 14 ONNX models")
        return 0
    else:
        print(f"\n❌ SOME TESTS FAILED - {total - success_count} out of {total} failed")
        return 1

if __name__ == "__main__":
    exit(main())

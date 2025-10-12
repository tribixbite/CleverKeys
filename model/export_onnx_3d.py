#!/usr/bin/env python3
"""
Export trained character-level swipe model to ONNX with 3D nearest_keys tensor.
CRITICAL: Uses shape [batch, sequence, 3] for top-3 nearest keys per point.
"""

import os
import json
import math
import torch
import torch.nn as nn
import numpy as np
from pathlib import Path
from typing import Dict, Tuple, List

# Optional ONNX validation (not available on some platforms)
try:
    import onnx
    ONNX_VALIDATION_AVAILABLE = True
except ImportError:
    ONNX_VALIDATION_AVAILABLE = False
    print("Warning: onnx package not available, skipping model validation")

import onnxruntime as ort


# ============================================================================
# MODEL DEFINITION (from train_character_model.py)
# ============================================================================

class CharTokenizer:
    """Character-level tokenizer for a-z."""
    def __init__(self):
        # Special tokens
        self.pad_idx = 0
        self.eos_idx = 1
        self.unk_idx = 2
        self.sos_idx = 3

        # Character mapping
        self.char_to_idx = {
            '<pad>': 0, '<eos>': 1, '<unk>': 2, '<sos>': 3
        }
        for i, char in enumerate('abcdefghijklmnopqrstuvwxyz'):
            self.char_to_idx[char] = i + 4

        self.idx_to_char = {v: k for k, v in self.char_to_idx.items()}
        self.vocab_size = len(self.char_to_idx)

    def encode(self, word: str) -> List[int]:
        """Encode word to indices."""
        tokens = [self.sos_idx]
        for char in word.lower():
            tokens.append(self.char_to_idx.get(char, self.unk_idx))
        tokens.append(self.eos_idx)
        return tokens

    def decode(self, tokens: List[int]) -> str:
        """Decode indices to word."""
        chars = []
        for token in tokens:
            if token == self.eos_idx:
                break
            if token in (self.pad_idx, self.sos_idx):
                continue
            char = self.idx_to_char.get(token, '<unk>')
            if not char.startswith('<'):
                chars.append(char)
        return ''.join(chars)


class CharacterLevelSwipeModel(nn.Module):
    """Transformer-based character-level swipe prediction model."""

    def __init__(
        self,
        traj_dim=6,
        d_model=256,
        nhead=8,
        num_encoder_layers=6,
        num_decoder_layers=4,
        dim_feedforward=1024,
        dropout=0.1,
        char_vocab_size=30,
        kb_vocab_size=30,
        max_seq_len=150
    ):
        super().__init__()

        self.d_model = d_model
        self.max_seq_len = max_seq_len

        # Trajectory and keyboard features project to d_model//2 each
        # They are concatenated to form d_model dimensional input
        self.embed_dim = d_model // 2

        # Trajectory projection
        self.traj_proj = nn.Linear(traj_dim, self.embed_dim)

        # Keyboard embedding (for nearest keys)
        self.kb_embedding = nn.Embedding(kb_vocab_size, self.embed_dim)

        # Positional encoding
        self.register_buffer('pe', self._create_positional_encoding(max_seq_len, d_model))

        # Transformer encoder
        encoder_layer = nn.TransformerEncoderLayer(
            d_model=d_model,
            nhead=nhead,
            dim_feedforward=dim_feedforward,
            dropout=dropout,
            batch_first=True
        )
        self.encoder = nn.TransformerEncoder(encoder_layer, num_encoder_layers)
        self.encoder_norm = nn.LayerNorm(d_model)

        # Character embedding for decoder
        self.char_embedding = nn.Embedding(char_vocab_size, d_model)

        # Transformer decoder
        decoder_layer = nn.TransformerDecoderLayer(
            d_model=d_model,
            nhead=nhead,
            dim_feedforward=dim_feedforward,
            dropout=dropout,
            batch_first=True
        )
        self.decoder = nn.TransformerDecoder(decoder_layer, num_decoder_layers)

        # Output projection
        self.output_proj = nn.Linear(d_model, char_vocab_size)

        self._init_weights()

    def _create_positional_encoding(self, max_len, d_model):
        """Create sinusoidal positional encoding."""
        pe = torch.zeros(1, max_len, d_model)
        position = torch.arange(0, max_len).unsqueeze(1).float()
        div_term = torch.exp(torch.arange(0, d_model, 2).float() * (-math.log(10000.0) / d_model))

        pe[0, :, 0::2] = torch.sin(position * div_term)
        pe[0, :, 1::2] = torch.cos(position * div_term)

        return pe

    def _init_weights(self):
        """Initialize weights."""
        for p in self.parameters():
            if p.dim() > 1:
                nn.init.xavier_uniform_(p)

    def encode_trajectory(self, traj_features, nearest_keys, src_mask=None):
        """
        Encode trajectory features.

        Args:
            traj_features: [batch, seq_len, 6] - x, y, vx, vy, ax, ay
            nearest_keys: [batch, seq_len, 3] - top 3 nearest keys per point
            src_mask: [batch, seq_len] - padding mask

        Returns:
            memory: [batch, seq_len, d_model]
        """
        batch_size, seq_len, _ = traj_features.shape

        # Project trajectory features
        traj_emb = self.traj_proj(traj_features) * math.sqrt(self.d_model)  # [batch, seq_len, embed_dim]

        # Embed nearest keys and average
        # nearest_keys: [batch, seq_len, 3]
        kb_emb = self.kb_embedding(nearest_keys)  # [batch, seq_len, 3, embed_dim]
        kb_emb = kb_emb.mean(dim=2)  # Average the 3 keys: [batch, seq_len, embed_dim]

        # Concatenate trajectory and keyboard embeddings to form d_model dimensional input
        combined_emb = torch.cat([traj_emb, kb_emb], dim=-1)  # [batch, seq_len, d_model]

        # Add positional encoding
        encoder_input = combined_emb + self.pe[:, :seq_len, :]

        # Encode
        memory = self.encoder(encoder_input, src_key_padding_mask=src_mask)
        memory = self.encoder_norm(memory)

        return memory

    def forward(self, traj_features, nearest_keys, target_tokens, src_mask=None, tgt_mask=None):
        """
        Full forward pass (training).

        Args:
            traj_features: [batch, seq_len, 6]
            nearest_keys: [batch, seq_len, 3]
            target_tokens: [batch, tgt_len]
            src_mask: [batch, seq_len]
            tgt_mask: [batch, tgt_len-1]
        """
        # Encode trajectory
        memory = self.encode_trajectory(traj_features, nearest_keys, src_mask)

        # Prepare target for teacher forcing
        batch_size, tgt_len = target_tokens.shape
        tgt = target_tokens[:, :-1]  # Remove last token

        # Embed target
        tgt_emb = self.char_embedding(tgt) * math.sqrt(self.d_model)
        tgt_emb = tgt_emb + self.pe[:, :tgt.shape[1], :]

        # Create causal mask
        causal_mask = nn.Transformer.generate_square_subsequent_mask(tgt.shape[1]).to(tgt_emb.device)

        # Decode
        output = self.decoder(
            tgt_emb, memory,
            tgt_mask=causal_mask,
            memory_key_padding_mask=src_mask,
            tgt_key_padding_mask=tgt_mask
        )

        # Project to vocabulary
        logits = self.output_proj(output)

        return logits


# ============================================================================
# KEYBOARD LAYOUT
# ============================================================================

class KeyboardGrid:
    """QWERTY keyboard layout with key positions."""
    def __init__(self):
        self.keys = {
            'q': (18, 111), 'w': (54, 111), 'e': (90, 111),
            'r': (126, 111), 't': (162, 111), 'y': (198, 111),
            'u': (234, 111), 'i': (270, 111), 'o': (306, 111), 'p': (342, 111),
            'a': (36, 167), 's': (72, 167), 'd': (108, 167),
            'f': (144, 167), 'g': (180, 167), 'h': (216, 167),
            'j': (252, 167), 'k': (288, 167), 'l': (324, 167),
            'z': (72, 223), 'x': (108, 223), 'c': (144, 223),
            'v': (180, 223), 'b': (216, 223), 'n': (252, 223), 'm': (288, 223)
        }

        self.width = 360
        self.height = 280

    def find_nearest_keys(self, x, y, top_k=3):
        """Find top-k nearest keys to point (x, y)."""
        distances = []
        for key, (kx, ky) in self.keys.items():
            dist = math.sqrt((x - kx)**2 + (y - ky)**2)
            distances.append((key, dist))

        distances.sort(key=lambda x: x[1])
        return [key for key, _ in distances[:top_k]]


# ============================================================================
# EXPORT FUNCTIONS
# ============================================================================

def load_checkpoint(checkpoint_path: str) -> CharacterLevelSwipeModel:
    """Load model from checkpoint."""
    print(f"Loading checkpoint: {checkpoint_path}")

    checkpoint = torch.load(checkpoint_path, map_location='cpu', weights_only=False)

    # Initialize model
    tokenizer = CharTokenizer()
    model = CharacterLevelSwipeModel(
        traj_dim=6,
        d_model=256,
        nhead=8,
        num_encoder_layers=6,
        num_decoder_layers=4,
        dim_feedforward=1024,
        dropout=0.0,  # No dropout for inference
        char_vocab_size=tokenizer.vocab_size,
        kb_vocab_size=tokenizer.vocab_size
    )

    # Load weights
    model.load_state_dict(checkpoint['model_state_dict'])
    model.eval()

    accuracy = checkpoint.get('val_word_acc', 0.0)
    print(f"Model loaded: {accuracy:.1%} word accuracy")

    return model, tokenizer, accuracy


def export_encoder_onnx(model: CharacterLevelSwipeModel, output_path: str):
    """Export encoder to ONNX with 3D nearest_keys."""
    print("\n=== Exporting Encoder ===")

    class EncoderWrapper(nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model

        def forward(self, traj_features, nearest_keys, src_mask):
            return self.model.encode_trajectory(traj_features, nearest_keys, src_mask)

    wrapper = EncoderWrapper(model)
    wrapper.eval()

    # Sample inputs with 3D nearest_keys
    batch_size = 1
    seq_len = 150
    traj_features = torch.randn(batch_size, seq_len, 6)
    nearest_keys = torch.randint(0, 30, (batch_size, seq_len, 3))  # 3D: top 3 keys
    src_mask = torch.zeros(batch_size, seq_len, dtype=torch.bool)

    # Export with workarounds for ONNX optimization errors
    # - Use opset 11 (more stable than 14)
    # - Disable constant folding to avoid optimization pass errors
    # - Simplify dynamic axes (only mark batch dimension as dynamic)
    torch.onnx.export(
        wrapper,
        (traj_features, nearest_keys, src_mask),
        output_path,
        export_params=True,
        opset_version=11,
        do_constant_folding=False,
        input_names=['trajectory_features', 'nearest_keys', 'src_mask'],
        output_names=['encoder_output'],
        dynamic_axes={
            'trajectory_features': {0: 'batch'},
            'nearest_keys': {0: 'batch'},
            'src_mask': {0: 'batch'},
            'encoder_output': {0: 'batch'}
        },
        verbose=False
    )

    # Validate (optional)
    if ONNX_VALIDATION_AVAILABLE:
        onnx_model = onnx.load(output_path)
        onnx.checker.check_model(onnx_model)
        print("   Model validation: ✅ passed")
    else:
        print("   Model validation: ⏭️  skipped (onnx package unavailable)")

    # Test inference
    ort_session = ort.InferenceSession(output_path)
    ort_inputs = {
        'trajectory_features': traj_features.numpy(),
        'nearest_keys': nearest_keys.numpy(),
        'src_mask': src_mask.numpy()
    }
    ort_outputs = ort_session.run(None, ort_inputs)

    print(f"✅ Encoder exported: {output_path}")
    print(f"   Input shapes:")
    print(f"     trajectory_features: [batch, {seq_len}, 6]")
    print(f"     nearest_keys: [batch, {seq_len}, 3] ← 3D tensor")
    print(f"     src_mask: [batch, {seq_len}]")
    print(f"   Output shape: {ort_outputs[0].shape}")

    return output_path


def export_decoder_onnx(model: CharacterLevelSwipeModel, output_path: str):
    """Export decoder to ONNX."""
    print("\n=== Exporting Decoder ===")

    class DecoderWrapper(nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model
            self.d_model = model.d_model

        def forward(self, memory, tgt_tokens, src_mask, tgt_mask):
            batch_size, tgt_len = tgt_tokens.shape

            # Embed target tokens
            tgt_emb = self.model.char_embedding(tgt_tokens) * math.sqrt(self.d_model)
            tgt_emb = tgt_emb + self.model.pe[:, :tgt_len, :]

            # Create causal mask
            causal_mask = nn.Transformer.generate_square_subsequent_mask(tgt_len).to(tgt_emb.device)

            # Decode
            output = self.model.decoder(
                tgt_emb, memory,
                tgt_mask=causal_mask,
                memory_key_padding_mask=src_mask,
                tgt_key_padding_mask=tgt_mask
            )

            # Project to vocabulary
            logits = self.model.output_proj(output)

            return logits

    decoder_wrapper = DecoderWrapper(model)
    decoder_wrapper.eval()

    # Sample inputs
    batch_size = 1
    seq_len = 150
    tgt_len = 20
    memory = torch.randn(batch_size, seq_len, 256)
    tgt_tokens = torch.randint(0, 30, (batch_size, tgt_len))
    src_mask = torch.zeros(batch_size, seq_len, dtype=torch.bool)
    tgt_mask = torch.zeros(batch_size, tgt_len, dtype=torch.bool)

    # Export with workarounds for ONNX optimization errors
    # - Use opset 11 (more stable than 14)
    # - Disable constant folding to avoid optimization pass errors
    # - Simplify dynamic axes (only mark batch dimension as dynamic)
    torch.onnx.export(
        decoder_wrapper,
        (memory, tgt_tokens, src_mask, tgt_mask),
        output_path,
        export_params=True,
        opset_version=11,
        do_constant_folding=False,
        input_names=['memory', 'target_tokens', 'src_mask', 'target_mask'],
        output_names=['logits'],
        dynamic_axes={
            'memory': {0: 'batch'},
            'target_tokens': {0: 'batch'},
            'src_mask': {0: 'batch'},
            'target_mask': {0: 'batch'},
            'logits': {0: 'batch'}
        },
        verbose=False
    )

    print(f"✅ Decoder exported: {output_path}")
    print(f"   Output shape: [batch, dec_sequence, 30]")

    return output_path


# ============================================================================
# TESTING FUNCTIONS
# ============================================================================

def extract_features(points_x, points_y, points_t):
    """Extract 6D features from swipe points."""
    features = []

    for i in range(len(points_x)):
        x = points_x[i] / 360.0  # Normalize
        y = points_y[i] / 280.0

        # Velocity
        if i > 0:
            dt = max(points_t[i] - points_t[i-1], 1)
            vx = (points_x[i] - points_x[i-1]) / dt
            vy = (points_y[i] - points_y[i-1]) / dt
        else:
            vx = vy = 0

        # Acceleration
        if i > 1:
            dt1 = max(points_t[i] - points_t[i-1], 1)
            dt2 = max(points_t[i-1] - points_t[i-2], 1)
            vx_prev = (points_x[i-1] - points_x[i-2]) / dt2
            vy_prev = (points_y[i-1] - points_y[i-2]) / dt2
            ax = (vx - vx_prev) / dt1
            ay = (vy - vy_prev) / dt1
        else:
            ax = ay = 0

        # Normalize and clip
        vx = max(-1, min(1, vx / 1000))
        vy = max(-1, min(1, vy / 1000))
        ax = max(-1, min(1, ax / 500))
        ay = max(-1, min(1, ay / 500))

        features.append([x, y, vx, vy, ax, ay])

    return np.array(features, dtype=np.float32)


def test_onnx_models(encoder_path: str, decoder_path: str, test_file: str, tokenizer: CharTokenizer):
    """Test ONNX models with real swipe data."""
    print("\n=== Testing ONNX Models ===")

    # Load ONNX models
    encoder_session = ort.InferenceSession(encoder_path)
    decoder_session = ort.InferenceSession(decoder_path)

    keyboard = KeyboardGrid()

    # Load test data
    with open(test_file, 'r') as f:
        test_samples = [json.loads(line) for line in f if line.strip()]

    print(f"Testing on {len(test_samples)} samples...")

    correct = 0
    for idx, sample in enumerate(test_samples[:10]):  # Test first 10
        word = sample['word']
        curve = sample['curve']

        # Extract features
        features = extract_features(curve['x'], curve['y'], curve['t'])

        # Find nearest keys (top 3 per point)
        nearest_keys = []
        for x, y in zip(curve['x'], curve['y']):
            top3_keys = keyboard.find_nearest_keys(x, y, top_k=3)
            top3_indices = [tokenizer.char_to_idx.get(key, tokenizer.unk_idx) for key in top3_keys]
            nearest_keys.extend(top3_indices)

        # Prepare tensors
        seq_len = len(features)
        traj_tensor = features.reshape(1, seq_len, 6)
        keys_tensor = np.array(nearest_keys, dtype=np.int64).reshape(1, seq_len, 3)
        mask_tensor = np.zeros((1, seq_len), dtype=np.bool_)

        # Run encoder
        encoder_outputs = encoder_session.run(
            None,
            {
                'trajectory_features': traj_tensor,
                'nearest_keys': keys_tensor,
                'src_mask': mask_tensor
            }
        )
        memory = encoder_outputs[0]

        # Beam search decode
        beam_size = 5
        beams = [{'tokens': [tokenizer.sos_idx], 'score': 0.0}]

        for step in range(20):
            all_candidates = []

            for beam in beams:
                if beam['tokens'][-1] == tokenizer.eos_idx:
                    all_candidates.append(beam)
                    continue

                # Run decoder
                tgt_tokens = np.array(beam['tokens'], dtype=np.int64).reshape(1, -1)
                tgt_mask = np.zeros((1, len(beam['tokens'])), dtype=np.bool_)

                decoder_outputs = decoder_session.run(
                    None,
                    {
                        'memory': memory,
                        'target_tokens': tgt_tokens,
                        'src_mask': mask_tensor,
                        'target_mask': tgt_mask
                    }
                )

                logits = decoder_outputs[0][0, -1, :]  # Last token logits
                probs = np.exp(logits) / np.sum(np.exp(logits))

                # Top-k
                top_k_indices = np.argsort(probs)[-beam_size:][::-1]
                for idx in top_k_indices:
                    score = beam['score'] + np.log(probs[idx] + 1e-10)
                    all_candidates.append({
                        'tokens': beam['tokens'] + [int(idx)],
                        'score': score
                    })

            # Keep top beams
            beams = sorted(all_candidates, key=lambda x: x['score'], reverse=True)[:beam_size]

            # Early stop if all finished
            if all(b['tokens'][-1] == tokenizer.eos_idx for b in beams):
                break

        # Decode best beam
        predicted = tokenizer.decode(beams[0]['tokens'])

        is_correct = predicted == word
        correct += is_correct

        print(f"  [{idx+1}/10] Target: '{word}' → Predicted: '{predicted}' {'✅' if is_correct else '❌'}")

    accuracy = correct / min(len(test_samples), 10)
    print(f"\nAccuracy: {accuracy:.1%} ({correct}/10)")

    return accuracy


def main():
    """Main export and test function."""
    print("="*70)
    print("ONNX Export with 3D nearest_keys Tensor")
    print("="*70)

    # Paths (relative to script location)
    script_dir = Path(__file__).parent
    checkpoint_path = script_dir / 'full-model-49-0.795.ckpt'
    output_dir = script_dir / 'onnx_output'
    output_dir.mkdir(exist_ok=True, parents=True)

    encoder_path = str(output_dir / 'swipe_model_character_quant.onnx')
    decoder_path = str(output_dir / 'swipe_decoder_character_quant.onnx')
    test_file = script_dir / 'swipes.jsonl'

    # Load model
    model, tokenizer, accuracy = load_checkpoint(str(checkpoint_path))

    # Export models
    export_encoder_onnx(model, encoder_path)
    export_decoder_onnx(model, decoder_path)

    # Test models
    test_accuracy = test_onnx_models(encoder_path, decoder_path, str(test_file), tokenizer)

    print("\n" + "="*70)
    print("✅ Export Complete!")
    print("="*70)
    print(f"Encoder: {encoder_path}")
    print(f"Decoder: {decoder_path}")
    print(f"Test accuracy: {test_accuracy:.1%}")
    print("\n✨ Models ready for Android deployment!")


if __name__ == "__main__":
    main()

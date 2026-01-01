#!/usr/bin/env python3
"""
Train a character-level swipe typing model that generates words.
Replicates the original model's clever approach: character-by-character generation
that forms complete words through beam search.
"""

import os
import json
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader
import numpy as np
from typing import Dict, List, Tuple, Optional
import math
from pathlib import Path
from tqdm import tqdm
import random


class KeyboardGrid:
    """Load and use the actual keyboard grid layout."""
    
    def __init__(self, grid_path: str = "data/data_preprocessed/gridname_to_grid.json"):
        with open(grid_path, 'r') as f:
            self.grids = json.load(f)
        self.qwerty = self.grids['qwerty_english']
        
        # Create key position lookup
        self.key_positions = {}
        for key_info in self.qwerty['keys']:
            label = key_info['label']
            hitbox = key_info['hitbox']
            # Use center of hitbox
            cx = hitbox['x'] + hitbox['w'] / 2
            cy = hitbox['y'] + hitbox['h'] / 2
            self.key_positions[label] = (cx, cy)
        
        # Add special tokens
        self.key_positions['<unk>'] = (180, 107)  # Center of keyboard
        self.key_positions['<pad>'] = (0, 0)
        
        self.width = self.qwerty['width']
        self.height = self.qwerty['height']
    
    def get_nearest_key(self, x: float, y: float) -> str:
        """Get the nearest keyboard key to a position."""
        min_dist = float('inf')
        nearest = '<unk>'
        
        for label, (kx, ky) in self.key_positions.items():
            if label in ['<unk>', '<pad>']:
                continue
            dist = ((x - kx) ** 2 + (y - ky) ** 2) ** 0.5
            if dist < min_dist:
                min_dist = dist
                nearest = label
        
        return nearest


class CharTokenizer:
    """Character-level tokenizer matching the original."""
    
    def __init__(self):
        # Basic alphabet + special tokens
        chars = list('abcdefghijklmnopqrstuvwxyz')
        special = ['<pad>', '<unk>', '<sos>', '<eos>']
        
        self.vocab = special + chars
        self.char_to_idx = {c: i for i, c in enumerate(self.vocab)}
        self.idx_to_char = {i: c for c, i in self.char_to_idx.items()}
        
        self.pad_idx = self.char_to_idx['<pad>']
        self.unk_idx = self.char_to_idx['<unk>']
        self.sos_idx = self.char_to_idx['<sos>']
        self.eos_idx = self.char_to_idx['<eos>']
        
        self.vocab_size = len(self.vocab)
    
    def encode_word(self, word: str) -> List[int]:
        """Encode a word to token indices."""
        indices = [self.sos_idx]
        for char in word.lower():
            if char in self.char_to_idx:
                indices.append(self.char_to_idx[char])
            else:
                indices.append(self.unk_idx)
        indices.append(self.eos_idx)
        return indices
    
    def decode(self, indices: List[int]) -> str:
        """Decode token indices to word."""
        chars = []
        for idx in indices:
            if idx == self.sos_idx or idx == self.eos_idx:
                continue
            if idx == self.pad_idx:
                break
            chars.append(self.idx_to_char.get(idx, '?'))
        return ''.join(chars)


class SwipeDataset(Dataset):
    """Dataset for swipe trajectories with character-level targets."""
    
    def __init__(self, data_path: str, max_seq_len: int = 150, max_word_len: int = 20, max_samples: int = None):
        self.max_seq_len = max_seq_len
        self.max_word_len = max_word_len
        
        # Load keyboard grid
        self.keyboard = KeyboardGrid()
        self.tokenizer = CharTokenizer()
        
        # Load data
        self.data = []
        with open(data_path, 'r') as f:
            for line in f:
                item = json.loads(line)
                # Handle combined dataset format with curve field
                if 'curve' in item and 'word' in item:
                    curve = item['curve']
                    if 'x' in curve and 'y' in curve and 't' in curve:
                        processed_item = {
                            'x': curve['x'],
                            'y': curve['y'],
                            't': curve['t'],
                            'word': item['word'],
                            'grid_name': 'qwerty_english'
                        }
                        self.data.append(processed_item)
                # Handle synthetic trace format
                elif 'word_seq' in item:
                    word_seq = item['word_seq']
                    if 'x' in word_seq and 'y' in word_seq and 'time' in word_seq:
                        processed_item = {
                            'x': word_seq['x'],
                            'y': word_seq['y'],
                            't': word_seq['time'],
                            'word': item.get('word', 'unknown'),
                            'grid_name': 'qwerty_english'
                        }
                        self.data.append(processed_item)
                elif 'grid_name' in item and item['grid_name'] == 'qwerty_english':
                    self.data.append(item)
                
                # Limit samples if specified (for faster iteration during development)
                if max_samples and len(self.data) >= max_samples:
                    break
        
        print(f"Loaded {len(self.data)} swipe examples")
    
    def __len__(self):
        return len(self.data)
    
    def __getitem__(self, idx):
        item = self.data[idx]
        
        # Extract trajectory
        xs = np.array(item['x'], dtype=np.float32)
        ys = np.array(item['y'], dtype=np.float32)
        ts = np.array(item['t'], dtype=np.float32)
        
        # Normalize coordinates
        xs = xs / self.keyboard.width
        ys = ys / self.keyboard.height
        
        # Compute velocities and accelerations
        dt = np.diff(ts, prepend=ts[0])
        dt = np.maximum(dt, 1e-6)  # Avoid division by zero
        
        vx = np.zeros_like(xs)
        vy = np.zeros_like(ys)
        vx[1:] = np.diff(xs) / dt[1:]
        vy[1:] = np.diff(ys) / dt[1:]
        
        ax = np.zeros_like(xs)
        ay = np.zeros_like(ys)
        ax[1:] = np.diff(vx) / dt[1:]
        ay[1:] = np.diff(vy) / dt[1:]
        
        # Clip for stability
        vx = np.clip(vx, -10, 10)
        vy = np.clip(vy, -10, 10)
        ax = np.clip(ax, -10, 10)
        ay = np.clip(ay, -10, 10)
        
        # Get nearest keys for each point
        nearest_keys = []
        for x, y in zip(item['x'], item['y']):
            key = self.keyboard.get_nearest_key(x, y)
            nearest_keys.append(self.tokenizer.char_to_idx.get(key, self.tokenizer.unk_idx))
        
        # Stack trajectory features
        traj_features = np.stack([xs, ys, vx, vy, ax, ay], axis=1)
        
        # Pad or truncate to max_seq_len
        seq_len = len(xs)
        if seq_len > self.max_seq_len:
            traj_features = traj_features[:self.max_seq_len]
            nearest_keys = nearest_keys[:self.max_seq_len]
            seq_len = self.max_seq_len
        elif seq_len < self.max_seq_len:
            pad_len = self.max_seq_len - seq_len
            traj_features = np.pad(traj_features, ((0, pad_len), (0, 0)), mode='constant')
            nearest_keys = nearest_keys + [self.tokenizer.pad_idx] * pad_len
        
        # Encode target word
        word = item['word']
        target_indices = self.tokenizer.encode_word(word)
        
        # Pad target to max_word_len
        if len(target_indices) > self.max_word_len:
            target_indices = target_indices[:self.max_word_len-1] + [self.tokenizer.eos_idx]
        else:
            pad_len = self.max_word_len - len(target_indices)
            target_indices = target_indices + [self.tokenizer.pad_idx] * pad_len
        
        return {
            'traj_features': torch.tensor(traj_features, dtype=torch.float32),
            'nearest_keys': torch.tensor(nearest_keys, dtype=torch.long),
            'target': torch.tensor(target_indices, dtype=torch.long),
            'seq_len': seq_len,
            'word': word
        }


class CharacterLevelSwipeModel(nn.Module):
    """Character-level model that generates words like the original."""
    
    def __init__(self,
                 traj_dim: int = 6,
                 d_model: int = 128,
                 nhead: int = 8,
                 num_encoder_layers: int = 4,
                 num_decoder_layers: int = 3,
                 dim_feedforward: int = 512,
                 dropout: float = 0.1,
                 kb_vocab_size: int = 30,
                 char_vocab_size: int = 30,
                 max_seq_len: int = 150):
        super().__init__()
        
        self.d_model = d_model
        
        # Encoder: Process trajectory
        self.traj_proj = nn.Linear(traj_dim, d_model // 2)
        self.kb_embedding = nn.Embedding(kb_vocab_size, d_model // 2)
        self.encoder_norm = nn.LayerNorm(d_model)
        
        # Positional encoding
        pe = torch.zeros(max_seq_len, d_model)
        position = torch.arange(0, max_seq_len).unsqueeze(1).float()
        div_term = torch.exp(torch.arange(0, d_model, 2).float() * 
                           -(math.log(10000.0) / d_model))
        pe[:, 0::2] = torch.sin(position * div_term)
        pe[:, 1::2] = torch.cos(position * div_term)
        self.register_buffer('pe', pe.unsqueeze(0))
        
        # Transformer encoder
        encoder_layer = nn.TransformerEncoderLayer(
            d_model=d_model,
            nhead=nhead,
            dim_feedforward=dim_feedforward,
            dropout=dropout,
            batch_first=True
        )
        self.encoder = nn.TransformerEncoder(encoder_layer, num_encoder_layers)
        
        # Decoder: Generate characters
        self.char_embedding = nn.Embedding(char_vocab_size, d_model)
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
    
    def _init_weights(self):
        for p in self.parameters():
            if p.dim() > 1:
                nn.init.xavier_uniform_(p)
    
    def encode_trajectory(self, traj_features, nearest_keys, src_mask=None):
        """Encode the swipe trajectory."""
        batch_size, seq_len, _ = traj_features.shape
        
        # Project features
        traj_enc = self.traj_proj(traj_features)
        kb_enc = self.kb_embedding(nearest_keys)
        
        # Combine
        combined = torch.cat([traj_enc, kb_enc], dim=-1)
        combined = self.encoder_norm(combined)
        
        # Add positional encoding
        combined = combined + self.pe[:, :seq_len, :]
        
        # Encode
        memory = self.encoder(combined, src_key_padding_mask=src_mask)
        
        return memory
    
    def forward(self, traj_features, nearest_keys, targets, src_mask=None, tgt_mask=None):
        """Forward pass with teacher forcing."""
        # Encode trajectory
        memory = self.encode_trajectory(traj_features, nearest_keys, src_mask)
        
        # Prepare target input (shift right, add <sos>)
        batch_size, tgt_len = targets.shape
        tgt_input = targets[:, :-1]  # Remove last token
        
        # Embed targets
        tgt_emb = self.char_embedding(tgt_input) * math.sqrt(self.d_model)
        tgt_emb = tgt_emb + self.pe[:, :tgt_len-1, :]
        
        # Create causal mask
        causal_mask = nn.Transformer.generate_square_subsequent_mask(tgt_len-1).to(tgt_emb.device)
        
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
    
    @torch.no_grad()
    def generate_beam(self, traj_features, nearest_keys, tokenizer, 
                     src_mask=None, beam_size=5, max_len=20):
        """Generate word using beam search."""
        self.eval()
        
        # Encode trajectory
        memory = self.encode_trajectory(traj_features, nearest_keys, src_mask)
        batch_size = memory.shape[0]
        
        # Initialize beams
        beams = [[(0.0, [tokenizer.sos_idx])] for _ in range(batch_size)]
        
        for step in range(max_len):
            new_beams = [[] for _ in range(batch_size)]
            
            for b in range(batch_size):
                for score, seq in beams[b]:
                    # Skip finished sequences
                    if seq[-1] == tokenizer.eos_idx:
                        new_beams[b].append((score, seq))
                        continue
                    
                    # Prepare input
                    tgt_input = torch.tensor([seq], device=memory.device)
                    tgt_emb = self.char_embedding(tgt_input) * math.sqrt(self.d_model)
                    tgt_emb = tgt_emb + self.pe[:, :len(seq), :]
                    
                    # Decode
                    causal_mask = nn.Transformer.generate_square_subsequent_mask(len(seq)).to(memory.device)
                    output = self.decoder(
                        tgt_emb,
                        memory[b:b+1],
                        tgt_mask=causal_mask
                    )
                    
                    # Get next token probabilities
                    logits = self.output_proj(output[:, -1, :])
                    probs = F.log_softmax(logits, dim=-1).squeeze(0)
                    
                    # Get top k tokens
                    top_probs, top_indices = torch.topk(probs, min(beam_size, probs.shape[-1]))
                    
                    for prob, idx in zip(top_probs, top_indices):
                        new_score = score - prob.item()
                        new_seq = seq + [idx.item()]
                        new_beams[b].append((new_score, new_seq))
                
                # Keep top beam_size sequences
                new_beams[b].sort(key=lambda x: x[0])
                beams[b] = new_beams[b][:beam_size]
        
        # Get best sequences
        results = []
        for b in range(batch_size):
            best_seq = beams[b][0][1]
            word = tokenizer.decode(best_seq)
            results.append(word)
        
        return results


def train_model():
    """Train the character-level swipe model."""
    # Configuration
    batch_size = 32
    learning_rate = 1e-4
    num_epochs = 20  # More epochs for larger dataset
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    
    print(f"Training on {device}")
    
    # Use combined dataset with proper splits
    train_data_path = 'data/combined_dataset/cleaned_english_swipes_train.jsonl'
    val_data_path = 'data/combined_dataset/cleaned_english_swipes_val.jsonl'
    
    # Check if dataset exists
    import os
    if not os.path.exists(train_data_path):
        print(f"Error: Training data not found at {train_data_path}")
        print("Falling back to synthetic traces...")
        import glob
        trace_files = glob.glob('data/synthetic_traces/*.jsonl')
        if trace_files:
            train_data_path = trace_files[0]
            val_data_path = trace_files[1] if len(trace_files) > 1 else trace_files[0]
        else:
            print("No data available!")
            return
    
    print(f"Using {train_data_path} for training")
    print(f"Using {val_data_path} for validation")
    
    # Create dataset and dataloader
    # Use max_samples=10000 for faster initial training, remove for full dataset
    train_dataset = SwipeDataset(train_data_path, max_samples=10000)  # Start with 10k samples
    val_dataset = SwipeDataset(val_data_path, max_samples=1000)  # 1k validation samples
    
    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=batch_size, shuffle=False)
    
    # Create model
    tokenizer = CharTokenizer()
    model = CharacterLevelSwipeModel(
        char_vocab_size=tokenizer.vocab_size,
        kb_vocab_size=tokenizer.vocab_size
    ).to(device)
    
    # Count parameters
    param_count = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"Model parameters: {param_count:,}")
    print(f"Model size (FP32): {param_count * 4 / 1024 / 1024:.2f} MB")
    
    # Loss and optimizer
    criterion = nn.CrossEntropyLoss(ignore_index=tokenizer.pad_idx)
    optimizer = torch.optim.AdamW(model.parameters(), lr=learning_rate)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=num_epochs)
    
    # Training loop
    best_val_acc = 0
    checkpoint_dir = Path('checkpoints/character_model')
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    
    for epoch in range(num_epochs):
        # Training
        model.train()
        train_loss = 0
        train_correct = 0
        train_total = 0
        
        pbar = tqdm(train_loader, desc=f'Epoch {epoch+1}/{num_epochs} [Train]')
        for batch in pbar:
            traj_features = batch['traj_features'].to(device)
            nearest_keys = batch['nearest_keys'].to(device)
            targets = batch['target'].to(device)
            
            # Create masks
            seq_lens = batch['seq_len']
            src_mask = torch.zeros(traj_features.shape[0], traj_features.shape[1], dtype=torch.bool, device=device)
            for i, seq_len in enumerate(seq_lens):
                src_mask[i, seq_len:] = True
            
            tgt_mask = (targets[:, :-1] == tokenizer.pad_idx)
            
            # Forward pass
            logits = model(traj_features, nearest_keys, targets, src_mask, tgt_mask)
            
            # Compute loss
            loss = criterion(logits.reshape(-1, logits.shape[-1]), targets[:, 1:].reshape(-1))
            
            # Backward pass
            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            
            # Track metrics
            train_loss += loss.item()
            predictions = logits.argmax(dim=-1)
            mask = (targets[:, 1:] != tokenizer.pad_idx)
            train_correct += ((predictions == targets[:, 1:]) & mask).sum().item()
            train_total += mask.sum().item()
            
            # Update progress bar
            acc = train_correct / max(train_total, 1)
            pbar.set_postfix({'loss': f'{loss.item():.4f}', 'acc': f'{acc:.2%}'})
        
        train_acc = train_correct / train_total
        avg_train_loss = train_loss / len(train_loader)
        
        # Validation
        model.eval()
        val_correct_chars = 0
        val_total_chars = 0
        val_correct_words = 0
        val_total_words = 0
        
        with torch.no_grad():
            pbar = tqdm(val_loader, desc=f'Epoch {epoch+1}/{num_epochs} [Val]')
            for batch in pbar:
                traj_features = batch['traj_features'].to(device)
                nearest_keys = batch['nearest_keys'].to(device)
                targets = batch['target'].to(device)
                words = batch['word']
                
                # Create masks
                seq_lens = batch['seq_len']
                src_mask = torch.zeros(traj_features.shape[0], traj_features.shape[1], dtype=torch.bool, device=device)
                for i, seq_len in enumerate(seq_lens):
                    src_mask[i, seq_len:] = True
                
                # Generate with beam search
                generated_words = model.generate_beam(
                    traj_features, nearest_keys, tokenizer, src_mask, beam_size=5
                )
                
                # Compute accuracy
                for gen_word, true_word in zip(generated_words, words):
                    val_total_words += 1
                    if gen_word == true_word:
                        val_correct_words += 1
                
                # Update progress bar
                word_acc = val_correct_words / max(val_total_words, 1)
                pbar.set_postfix({'word_acc': f'{word_acc:.2%}'})
        
        val_word_acc = val_correct_words / val_total_words
        
        print(f"\nEpoch {epoch+1}/{num_epochs}")
        print(f"  Train - Loss: {avg_train_loss:.4f}, Char Acc: {train_acc:.2%}")
        print(f"  Val   - Word Acc: {val_word_acc:.2%}")
        
        # Save checkpoint if improved
        if val_word_acc > best_val_acc:
            best_val_acc = val_word_acc
            checkpoint_path = checkpoint_dir / f'character-model-{epoch+1:02d}-{val_word_acc:.3f}.ckpt'
            torch.save({
                'epoch': epoch,
                'model_state_dict': model.state_dict(),
                'optimizer_state_dict': optimizer.state_dict(),
                'val_word_acc': val_word_acc,
                'train_acc': train_acc,
            }, checkpoint_path)
            print(f"  Saved checkpoint: {checkpoint_path}")
        
        scheduler.step()
        
        # Early stopping if we reach target
        if val_word_acc >= 0.70:
            print(f"\n🎉 TARGET ACHIEVED! {val_word_acc:.1%} word accuracy!")
            print("Successfully matched original model performance!")
            break
    
    print(f"\nTraining complete! Best validation accuracy: {best_val_acc:.2%}")


if __name__ == "__main__":
    train_model()